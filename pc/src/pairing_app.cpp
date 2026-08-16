#include "local_camera_receiver/network_addresses.hpp"
#include "local_camera_receiver/pairing_control_protocol.hpp"
#include "local_camera_receiver/pairing_launch.hpp"
#include "local_camera_receiver/receiver_config.hpp"

#include "qrcodegen.hpp"

#include <Windows.h>
#include <CommCtrl.h>
#include <Shlwapi.h>
#include <shellapi.h>
#include <sddl.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <charconv>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cwctype>
#include <filesystem>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

namespace {

constexpr wchar_t kWindowClass[] = L"LocalCameraReceiverPairingWindow";
constexpr wchar_t kWindowTitle[] = L"Local Camera Receiver";
constexpr UINT_PTR kCountdownTimer = 1;
constexpr UINT kTrayCallbackMessage = WM_APP + 1;
constexpr UINT kActivateWindowMessage = WM_APP + 2;
constexpr UINT kShowQrMessage = WM_APP + 3;
constexpr UINT kTrayIconId = 1;
constexpr int kControlNetwork = 101;
constexpr int kControlLabel = 102;
constexpr int kControlPort = 103;
constexpr int kControlLatency = 104;
constexpr int kControlRotate = 105;
constexpr int kControlOpenObs = 106;
constexpr int kControlFinish = 107;
constexpr int kTrayOpen = 201;
constexpr int kTrayOpenObs = 202;
constexpr int kTrayExit = 203;
constexpr std::uint16_t kReceiverPort = 9000;
constexpr std::uint64_t kCredentialLifetimeSeconds = 365ULL * 24ULL * 60ULL * 60ULL;
constexpr std::uint64_t kQrLifetimeSeconds = 10ULL * 60ULL;

struct CurrentUserPipeIdentity final {
    std::wstring name;
    PSECURITY_DESCRIPTOR descriptor = nullptr;
    SECURITY_ATTRIBUTES attributes{sizeof(SECURITY_ATTRIBUTES), nullptr, FALSE};

    ~CurrentUserPipeIdentity()
    {
        if (descriptor != nullptr) LocalFree(descriptor);
    }

    CurrentUserPipeIdentity() = default;
    CurrentUserPipeIdentity(const CurrentUserPipeIdentity &) = delete;
    CurrentUserPipeIdentity &operator=(const CurrentUserPipeIdentity &) = delete;
};

bool load_current_user_pipe_identity(CurrentUserPipeIdentity &identity) noexcept
{
    HANDLE token = nullptr;
    if (OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token) == FALSE) return false;
    DWORD required = 0;
    GetTokenInformation(token, TokenUser, nullptr, 0, &required);
    if (required == 0 || required > 64U * 1024U) {
        CloseHandle(token);
        return false;
    }
    std::vector<std::uint8_t> buffer(required);
    const bool queried = GetTokenInformation(token, TokenUser, buffer.data(), required, &required) != FALSE;
    CloseHandle(token);
    if (!queried) return false;

    const auto *user = reinterpret_cast<const TOKEN_USER *>(buffer.data());
    LPWSTR sid = nullptr;
    if (ConvertSidToStringSidW(user->User.Sid, &sid) == FALSE || sid == nullptr) return false;
    const std::wstring sid_text(sid);
    LocalFree(sid);
    if (sid_text.size() < 8 || sid_text.size() > 184) return false;

    identity.name = L"\\\\.\\pipe\\local-camera-receiver-control-" + sid_text;
    const std::wstring descriptor_text = L"D:P(A;;GA;;;" + sid_text + L")";
    if (ConvertStringSecurityDescriptorToSecurityDescriptorW(
            descriptor_text.c_str(),
            SDDL_REVISION_1,
            &identity.descriptor,
            nullptr) == FALSE) {
        identity.name.clear();
        return false;
    }
    identity.attributes.lpSecurityDescriptor = identity.descriptor;
    return true;
}

bool send_show_qr_control_command() noexcept
{
    CurrentUserPipeIdentity identity{};
    if (!load_current_user_pipe_identity(identity)) return false;
    if (WaitNamedPipeW(identity.name.c_str(), 750) == FALSE) return false;
    const HANDLE pipe = CreateFileW(
        identity.name.c_str(),
        GENERIC_WRITE,
        0,
        nullptr,
        OPEN_EXISTING,
        FILE_ATTRIBUTE_NORMAL,
        nullptr);
    if (pipe == INVALID_HANDLE_VALUE) return false;
    DWORD written = 0;
    const bool sent = WriteFile(
        pipe,
        lcr::kShowQrCommand.data(),
        static_cast<DWORD>(lcr::kShowQrCommand.size()),
        &written,
        nullptr) != FALSE && written == lcr::kShowQrCommand.size();
    CloseHandle(pipe);
    return sent;
}

class PairingControlServer final {
public:
    PairingControlServer(HWND window, UINT message) : window_(window), message_(message) {}
    ~PairingControlServer() { stop(); }

    PairingControlServer(const PairingControlServer &) = delete;
    PairingControlServer &operator=(const PairingControlServer &) = delete;

    [[nodiscard]] bool start()
    {
        bool expected = false;
        if (!running_.compare_exchange_strong(expected, true)) return true;
        worker_ = std::thread(&PairingControlServer::run, this);
        return true;
    }

    void stop() noexcept
    {
        running_.store(false);
        const auto raw = pipe_.exchange(nullptr);
        if (raw != nullptr && raw != INVALID_HANDLE_VALUE) {
            const auto pipe = static_cast<HANDLE>(raw);
            CancelIoEx(pipe, nullptr);
            DisconnectNamedPipe(pipe);
            CloseHandle(pipe);
        }
        if (worker_.joinable()) worker_.join();
    }

private:
    void run() noexcept
    {
        CurrentUserPipeIdentity identity{};
        if (!load_current_user_pipe_identity(identity)) {
            running_.store(false);
            return;
        }
        while (running_.load()) {
            const HANDLE pipe = CreateNamedPipeW(
                identity.name.c_str(),
                PIPE_ACCESS_INBOUND | FILE_FLAG_FIRST_PIPE_INSTANCE,
                PIPE_TYPE_MESSAGE | PIPE_READMODE_MESSAGE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
                1,
                0,
                256,
                0,
                &identity.attributes);
            if (pipe == INVALID_HANDLE_VALUE) {
                running_.store(false);
                return;
            }
            pipe_.store(pipe);
            const bool connected = ConnectNamedPipe(pipe, nullptr) != FALSE || GetLastError() == ERROR_PIPE_CONNECTED;
            if (connected && running_.load()) {
                std::array<std::byte, 64> command{};
                DWORD read = 0;
                if (ReadFile(pipe, command.data(), static_cast<DWORD>(command.size()), &read, nullptr) != FALSE &&
                    lcr::is_show_qr_command(std::span<const std::byte>(command.data(), read))) {
                    PostMessageW(window_, message_, 0, 0);
                }
                SecureZeroMemory(command.data(), command.size());
            }
            void *expected = pipe;
            if (pipe_.compare_exchange_strong(expected, nullptr)) {
                DisconnectNamedPipe(pipe);
                CloseHandle(pipe);
            }
        }
    }

    HWND window_ = nullptr;
    UINT message_ = 0;
    std::atomic<bool> running_{false};
    std::atomic<void *> pipe_{nullptr};
    std::thread worker_;
};

struct AppState final {
    std::vector<lcr::NetworkAddress> addresses;
    std::unique_ptr<qrcodegen::QrCode> qr;
    std::string pairing_payload;
    std::uint64_t qr_expires = 0;
    std::wstring status = L"Choose this computer's local network, then show the pairing QR.";
    HFONT title_font = nullptr;
    HFONT body_font = nullptr;
    HFONT small_font = nullptr;
    HFONT mono_font = nullptr;
    HBRUSH background_brush = nullptr;
    HWND network = nullptr;
    HWND label = nullptr;
    HWND port = nullptr;
    HWND latency = nullptr;
    NOTIFYICONDATAW tray{};
    bool tray_added = false;
    bool allow_exit = false;
    std::unique_ptr<PairingControlServer> control_server;

    ~AppState()
    {
        clear_pairing_material();
        if (title_font != nullptr) DeleteObject(title_font);
        if (body_font != nullptr) DeleteObject(body_font);
        if (small_font != nullptr) DeleteObject(small_font);
        if (mono_font != nullptr) DeleteObject(mono_font);
        if (background_brush != nullptr) DeleteObject(background_brush);
    }

    void clear_pairing_material() noexcept
    {
        qr.reset();
        if (!pairing_payload.empty()) {
            SecureZeroMemory(pairing_payload.data(), pairing_payload.size());
            pairing_payload.clear();
            pairing_payload.shrink_to_fit();
        }
        qr_expires = 0;
    }
};

std::string utf8(std::wstring_view input)
{
    if (input.empty()) return {};
    if (input.size() > static_cast<std::size_t>(INT_MAX)) return {};
    const int required = WideCharToMultiByte(
        CP_UTF8, WC_ERR_INVALID_CHARS, input.data(), static_cast<int>(input.size()), nullptr, 0, nullptr, nullptr);
    if (required <= 0) return {};
    std::string output(static_cast<std::size_t>(required), '\0');
    const int written = WideCharToMultiByte(
        CP_UTF8,
        WC_ERR_INVALID_CHARS,
        input.data(),
        static_cast<int>(input.size()),
        output.data(),
        required,
        nullptr,
        nullptr);
    return written == required ? output : std::string{};
}

std::wstring wide(std::string_view input)
{
    if (input.empty()) return {};
    if (input.size() > static_cast<std::size_t>(INT_MAX)) return {};
    const int required = MultiByteToWideChar(
        CP_UTF8, MB_ERR_INVALID_CHARS, input.data(), static_cast<int>(input.size()), nullptr, 0);
    if (required <= 0) return {};
    std::wstring output(static_cast<std::size_t>(required), L'\0');
    const int written = MultiByteToWideChar(
        CP_UTF8, MB_ERR_INVALID_CHARS, input.data(), static_cast<int>(input.size()), output.data(), required);
    return written == required ? output : std::wstring{};
}

std::wstring window_text(HWND control)
{
    const int length = GetWindowTextLengthW(control);
    if (length <= 0 || length > 512) return {};
    std::wstring value(static_cast<std::size_t>(length) + 1U, L'\0');
    const int written = GetWindowTextW(control, value.data(), static_cast<int>(value.size()));
    if (written <= 0) return {};
    value.resize(static_cast<std::size_t>(written));
    return value;
}

std::optional<std::uint16_t> parse_u16(HWND control)
{
    const std::wstring text = window_text(control);
    if (text.empty()) return std::nullopt;
    const std::string narrow = utf8(text);
    if (narrow.empty()) return std::nullopt;
    unsigned int parsed = 0;
    const auto [end, error] = std::from_chars(narrow.data(), narrow.data() + narrow.size(), parsed);
    if (error != std::errc{} || end != narrow.data() + narrow.size() || parsed > UINT16_MAX) {
        return std::nullopt;
    }
    return static_cast<std::uint16_t>(parsed);
}

HFONT make_font(int point_size, int weight, const wchar_t *face)
{
    HDC dc = GetDC(nullptr);
    const int dpi = dc != nullptr ? GetDeviceCaps(dc, LOGPIXELSY) : 96;
    if (dc != nullptr) ReleaseDC(nullptr, dc);
    return CreateFontW(
        -MulDiv(point_size, dpi, 72),
        0,
        0,
        0,
        weight,
        FALSE,
        FALSE,
        FALSE,
        DEFAULT_CHARSET,
        OUT_DEFAULT_PRECIS,
        CLIP_DEFAULT_PRECIS,
        CLEARTYPE_QUALITY,
        DEFAULT_PITCH | FF_DONTCARE,
        face);
}

HWND add_control(
    HWND parent,
    DWORD extended_style,
    const wchar_t *class_name,
    const wchar_t *text,
    DWORD style,
    int x,
    int y,
    int width,
    int height,
    int id,
    HFONT font)
{
    HWND control = CreateWindowExW(
        extended_style,
        class_name,
        text,
        WS_CHILD | WS_VISIBLE | style,
        x,
        y,
        width,
        height,
        parent,
        reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)),
        GetModuleHandleW(nullptr),
        nullptr);
    if (control != nullptr && font != nullptr) {
        SendMessageW(control, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
    }
    return control;
}

void set_status(HWND window, AppState &state, std::wstring message)
{
    state.status = std::move(message);
    InvalidateRect(window, nullptr, FALSE);
}

void populate_networks(AppState &state)
{
    state.addresses = lcr::enumerate_private_ipv4_addresses();
    SendMessageW(state.network, CB_RESETCONTENT, 0, 0);
    for (const auto &address : state.addresses) {
        const std::wstring label = wide(address.display_name + " — " + address.address);
        SendMessageW(state.network, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(label.c_str()));
    }
    if (!state.addresses.empty()) SendMessageW(state.network, CB_SETCURSEL, 0, 0);
}

enum class SavedSettingsResult { none, restored, network_changed };

SavedSettingsResult load_saved_settings(AppState &state)
{
    lcr::ConfigError error = lcr::ConfigError::none;
    auto config = lcr::load_config_dpapi(lcr::default_config_path(), lcr::now_epoch_seconds(), error);
    if (!config) return SavedSettingsResult::none;

    const std::wstring label = wide(config->label);
    const std::wstring port = std::to_wstring(config->port);
    const std::wstring latency = std::to_wstring(config->latency_ms);
    SetWindowTextW(state.label, label.c_str());
    SetWindowTextW(state.port, port.c_str());
    SetWindowTextW(state.latency, latency.c_str());
    const auto saved_index = lcr::saved_network_index(state.addresses, config->host);
    if (saved_index) SendMessageW(state.network, CB_SETCURSEL, static_cast<WPARAM>(*saved_index), 0);
    config->clear_secret();
    return saved_index ? SavedSettingsResult::restored : SavedSettingsResult::network_changed;
}

std::optional<lcr::ReceiverConfig> load_or_create_config(HWND window, AppState &state)
{
    const LRESULT selected = SendMessageW(state.network, CB_GETCURSEL, 0, 0);
    if (selected == CB_ERR || static_cast<std::size_t>(selected) >= state.addresses.size()) {
        set_status(window, state, L"Connect this PC to a private Wi-Fi or Ethernet network first.");
        return std::nullopt;
    }

    const std::string label = utf8(window_text(state.label));
    const auto port = parse_u16(state.port);
    const auto latency = parse_u16(state.latency);
    if (!port || *port != kReceiverPort) {
        set_status(window, state, L"This version securely listens on port 9000.");
        return std::nullopt;
    }
    if (!latency || *latency < lcr::kMinimumLatencyMs || *latency > lcr::kMaximumLatencyMs) {
        set_status(window, state, L"Choose latency from 60 to 2000 milliseconds.");
        return std::nullopt;
    }

    const std::uint64_t now = lcr::now_epoch_seconds();
    const std::string &host = state.addresses[static_cast<std::size_t>(selected)].address;
    lcr::ConfigError load_error = lcr::ConfigError::none;
    auto config = lcr::load_config_dpapi(lcr::default_config_path(), now, load_error);
    if (config && config->label == label && config->host == host &&
        config->port == *port && config->latency_ms == *latency) {
        return config;
    }
    if (config) config->clear_secret();

    config = lcr::generate_config(
        label,
        host,
        *port,
        *latency,
        now);
    if (!config) {
        set_status(window, state, L"Check the receiver name and network settings, then try again.");
        return std::nullopt;
    }
    config->credential_expires_epoch_seconds = now + kCredentialLifetimeSeconds;
    if (lcr::validate_config(*config, now) != lcr::ConfigError::none) {
        config->clear_secret();
        set_status(window, state, L"Those receiver settings could not be saved safely.");
        return std::nullopt;
    }
    if (!lcr::save_config_dpapi(lcr::default_config_path(), *config)) {
        config->clear_secret();
        set_status(window, state, L"Windows could not securely save this pairing. Try again from this user account.");
        return std::nullopt;
    }
    return config;
}

void create_pairing_qr(HWND window, AppState &state)
{
    auto config = load_or_create_config(window, state);
    if (!config) return;

    state.clear_pairing_material();
    const std::uint64_t now = lcr::now_epoch_seconds();
    state.qr_expires = now + kQrLifetimeSeconds;
    state.pairing_payload = lcr::build_pairing_payload(*config, state.qr_expires, now);
    config->clear_secret();
    if (state.pairing_payload.empty()) {
        state.clear_pairing_material();
        set_status(window, state, L"Could not create the pairing QR. Check the settings and try again.");
        return;
    }

    try {
        state.qr = std::make_unique<qrcodegen::QrCode>(
            qrcodegen::QrCode::encodeText(state.pairing_payload.c_str(), qrcodegen::QrCode::Ecc::MEDIUM));
    } catch (...) {
        state.clear_pairing_material();
        set_status(window, state, L"The pairing QR was too large to create.");
        return;
    }
    set_status(window, state, L"Ready. Scan this QR in Local Camera Sender within 10 minutes.");
    MessageBeep(MB_OK);
}

std::wstring clean_executable_value(std::wstring value)
{
    std::wstring lowered = value;
    std::transform(lowered.begin(), lowered.end(), lowered.begin(), [](const wchar_t character) {
        return static_cast<wchar_t>(std::towlower(character));
    });
    const auto executable_end = lowered.find(L".exe");
    if (executable_end == std::wstring::npos) return {};
    value.resize(executable_end + 4U);
    while (!value.empty() && (value.front() == L'"' || std::iswspace(value.front()) != 0)) value.erase(value.begin());
    while (!value.empty() && (value.back() == L'"' || std::iswspace(value.back()) != 0)) value.pop_back();
    return value;
}

std::optional<std::wstring> read_registry_string(
    HKEY root,
    const wchar_t *subkey,
    const wchar_t *name,
    REGSAM view) noexcept
{
    HKEY key = nullptr;
    if (RegOpenKeyExW(root, subkey, 0, KEY_QUERY_VALUE | view, &key) != ERROR_SUCCESS) return std::nullopt;
    DWORD type = 0;
    DWORD bytes = 0;
    const LSTATUS measured = RegQueryValueExW(key, name, nullptr, &type, nullptr, &bytes);
    if (measured != ERROR_SUCCESS || (type != REG_SZ && type != REG_EXPAND_SZ) || bytes < sizeof(wchar_t) || bytes > 32768) {
        RegCloseKey(key);
        return std::nullopt;
    }
    std::wstring value(bytes / sizeof(wchar_t), L'\0');
    const LSTATUS loaded = RegQueryValueExW(
        key, name, nullptr, &type, reinterpret_cast<BYTE *>(value.data()), &bytes);
    RegCloseKey(key);
    if (loaded != ERROR_SUCCESS) return std::nullopt;
    while (!value.empty() && value.back() == L'\0') value.pop_back();
    return value;
}

std::vector<std::filesystem::path> obs_candidates()
{
    constexpr wchar_t uninstall_key[] = L"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\OBS Studio";
    std::vector<std::filesystem::path> candidates;
    for (const REGSAM view : {KEY_WOW64_32KEY, KEY_WOW64_64KEY}) {
        if (auto display_icon = read_registry_string(HKEY_LOCAL_MACHINE, uninstall_key, L"DisplayIcon", view)) {
            const std::wstring cleaned = clean_executable_value(std::move(*display_icon));
            if (!cleaned.empty()) candidates.emplace_back(cleaned);
        }
        if (auto location = read_registry_string(HKEY_LOCAL_MACHINE, uninstall_key, L"InstallLocation", view)) {
            candidates.emplace_back(std::filesystem::path(*location) / L"bin" / L"64bit" / L"obs64.exe");
        }
    }
    wchar_t *program_files = nullptr;
    std::size_t program_files_length = 0;
    if (_wdupenv_s(&program_files, &program_files_length, L"ProgramFiles") == 0 && program_files != nullptr) {
        candidates.emplace_back(std::filesystem::path(program_files) / L"obs-studio" / L"bin" / L"64bit" / L"obs64.exe");
    }
    std::free(program_files);
    return candidates;
}

void open_obs(HWND window)
{
    const auto candidates = obs_candidates();
    for (const auto &candidate : candidates) {
        if (!std::filesystem::is_regular_file(candidate)) continue;
        const auto result = reinterpret_cast<std::intptr_t>(ShellExecuteW(
            window, L"open", candidate.c_str(), nullptr, candidate.parent_path().c_str(), SW_SHOWNORMAL));
        if (result > 32) return;
    }
    MessageBoxW(
        window,
        L"Open OBS Studio, then add “Local Camera Receiver” from the Sources + menu.",
        kWindowTitle,
        MB_OK | MB_ICONINFORMATION);
}

void add_tray_icon(HWND window, AppState &state)
{
    if (state.tray_added) return;
    state.tray = {};
    state.tray.cbSize = sizeof(state.tray);
    state.tray.hWnd = window;
    state.tray.uID = kTrayIconId;
    state.tray.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP | NIF_SHOWTIP;
    state.tray.uCallbackMessage = kTrayCallbackMessage;
    state.tray.hIcon = reinterpret_cast<HICON>(GetClassLongPtrW(window, GCLP_HICONSM));
    wcscpy_s(state.tray.szTip, L"Local Camera Receiver — settings saved");
    state.tray_added = Shell_NotifyIconW(NIM_ADD, &state.tray) != FALSE;
    if (state.tray_added) {
        state.tray.uVersion = NOTIFYICON_VERSION_4;
        Shell_NotifyIconW(NIM_SETVERSION, &state.tray);
    }
}

void remove_tray_icon(AppState &state) noexcept
{
    if (!state.tray_added) return;
    Shell_NotifyIconW(NIM_DELETE, &state.tray);
    state.tray_added = false;
}

void show_main_window(HWND window)
{
    ShowWindow(window, SW_SHOW);
    ShowWindow(window, SW_RESTORE);
    SetWindowPos(window, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW);
    SetForegroundWindow(window);
}

void hide_to_tray(HWND window, AppState &state)
{
    state.clear_pairing_material();
    add_tray_icon(window, state);
    ShowWindow(window, SW_HIDE);
}

void show_tray_menu(HWND window)
{
    HMENU menu = CreatePopupMenu();
    if (menu == nullptr) return;
    AppendMenuW(menu, MF_STRING | MF_DEFAULT, kTrayOpen, L"Open receiver");
    AppendMenuW(menu, MF_STRING, kTrayOpenObs, L"Open OBS Studio");
    AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(menu, MF_STRING, kTrayExit, L"Exit background helper");
    POINT cursor{};
    GetCursorPos(&cursor);
    SetForegroundWindow(window);
    TrackPopupMenu(menu, TPM_RIGHTBUTTON | TPM_BOTTOMALIGN | TPM_LEFTALIGN, cursor.x, cursor.y, 0, window, nullptr);
    DestroyMenu(menu);
}

void paint_qr(HDC dc, const RECT &client, const AppState &state)
{
    constexpr RECT frame{36, 314, 350, 628};
    HBRUSH panel = CreateSolidBrush(RGB(255, 255, 252));
    FillRect(dc, &frame, panel);
    DeleteObject(panel);
    HPEN border = CreatePen(PS_SOLID, 1, RGB(194, 198, 190));
    const auto prior_pen = SelectObject(dc, border);
    const auto prior_brush = SelectObject(dc, GetStockObject(NULL_BRUSH));
    Rectangle(dc, frame.left, frame.top, frame.right, frame.bottom);
    SelectObject(dc, prior_brush);
    SelectObject(dc, prior_pen);
    DeleteObject(border);

    if (!state.qr) {
        SetTextColor(dc, RGB(31, 43, 51));
        const auto prior_font = SelectObject(dc, state.mono_font);
        RECT empty_title{64, 422, 322, 456};
        DrawTextW(dc, L"PAIRING QR", -1, &empty_title, DT_CENTER | DT_SINGLELINE | DT_VCENTER);
        SelectObject(dc, state.small_font);
        SetTextColor(dc, RGB(91, 99, 99));
        RECT empty_copy{72, 462, 314, 512};
        DrawTextW(dc, L"Your saved connection stays private until you choose Show pairing QR.", -1,
                  &empty_copy, DT_CENTER | DT_WORDBREAK);
        SelectObject(dc, prior_font);
        return;
    }
    constexpr int quiet_zone = 4;
    const int qr_size = state.qr->getSize();
    const int available = std::min(static_cast<int>(client.right) - 80, 282);
    const int scale = std::max(1, available / (qr_size + 2 * quiet_zone));
    const int extent = (qr_size + 2 * quiet_zone) * scale;
    const int origin_x = frame.left + (frame.right - frame.left - extent) / 2;
    const int origin_y = frame.top + (frame.bottom - frame.top - extent) / 2;
    RECT background{origin_x, origin_y, origin_x + extent, origin_y + extent};
    FillRect(dc, &background, reinterpret_cast<HBRUSH>(GetStockObject(WHITE_BRUSH)));
    HBRUSH black = reinterpret_cast<HBRUSH>(GetStockObject(BLACK_BRUSH));
    for (int y = 0; y < qr_size; ++y) {
        for (int x = 0; x < qr_size; ++x) {
            if (!state.qr->getModule(x, y)) continue;
            const int left = origin_x + (x + quiet_zone) * scale;
            const int top = origin_y + (y + quiet_zone) * scale;
            RECT module{left, top, left + scale, top + scale};
            FillRect(dc, &module, black);
        }
    }
}

void paint_window(HWND window, const AppState &state)
{
    PAINTSTRUCT paint{};
    HDC dc = BeginPaint(window, &paint);
    RECT client{};
    GetClientRect(window, &client);
    FillRect(dc, &client, state.background_brush);
    SetBkMode(dc, TRANSPARENT);
    HBRUSH header_brush = CreateSolidBrush(RGB(24, 35, 44));
    RECT header{0, 0, client.right, 106};
    FillRect(dc, &header, header_brush);
    DeleteObject(header_brush);
    HBRUSH accent_brush = CreateSolidBrush(RGB(31, 181, 125));
    RECT accent{0, 0, 8, 106};
    FillRect(dc, &accent, accent_brush);
    RECT status_dot{client.right - 58, 40, client.right - 46, 52};
    FillRect(dc, &status_dot, accent_brush);
    DeleteObject(accent_brush);

    HFONT prior = reinterpret_cast<HFONT>(SelectObject(dc, state.title_font));
    SetTextColor(dc, RGB(250, 251, 247));
    RECT title{32, 22, client.right - 84, 60};
    DrawTextW(dc, L"LOCAL CAMERA RECEIVER", -1, &title, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
    SelectObject(dc, state.body_font);
    SetTextColor(dc, RGB(190, 201, 202));
    RECT intro{32, 62, client.right - 84, 92};
    DrawTextW(
        dc,
        L"Encrypted phone camera input for OBS · local network only",
        -1,
        &intro,
        DT_LEFT | DT_SINGLELINE | DT_VCENTER);

    HBRUSH card_brush = CreateSolidBrush(RGB(250, 248, 242));
    RECT settings_card{24, 126, client.right - 24, 282};
    FillRect(dc, &settings_card, card_brush);
    DeleteObject(card_brush);
    HPEN divider = CreatePen(PS_SOLID, 1, RGB(205, 205, 196));
    const auto old_pen = SelectObject(dc, divider);
    MoveToEx(dc, 24, 282, nullptr);
    LineTo(dc, client.right - 24, 282);
    SelectObject(dc, old_pen);
    DeleteObject(divider);

    SelectObject(dc, state.mono_font);
    SetTextColor(dc, RGB(31, 43, 51));
    RECT setup_label{36, 136, 280, 158};
    DrawTextW(dc, L"01  RECEIVER SETTINGS", -1, &setup_label, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
    RECT pair_label{36, 292, 330, 314};
    DrawTextW(dc, L"02  PAIR THIS PHONE", -1, &pair_label, DT_LEFT | DT_SINGLELINE | DT_VCENTER);

    SelectObject(dc, state.small_font);
    SetTextColor(dc, RGB(67, 76, 78));
    RECT action_copy{382, 330, client.right - 36, 386};
    DrawTextW(dc, L"Settings are encrypted for this Windows account. Closing this window keeps the helper available in the tray.",
              -1, &action_copy, DT_LEFT | DT_WORDBREAK);

    paint_qr(dc, client, state);
    if (state.qr && state.qr_expires > 0) {
        const std::uint64_t now = lcr::now_epoch_seconds();
        const std::uint64_t remaining = state.qr_expires > now ? state.qr_expires - now : 0;
        const std::wstring countdown = remaining > 0
            ? L"QR expires in " + std::to_wstring((remaining + 59) / 60) + L" minute(s)"
            : L"QR expired — create a new one";
        RECT timer{36, 634, 350, 660};
        DrawTextW(dc, countdown.c_str(), -1, &timer, DT_CENTER | DT_SINGLELINE | DT_VCENTER);
    }

    HBRUSH status_brush = CreateSolidBrush(RGB(228, 234, 226));
    RECT status_box{24, 668, client.right - 24, 718};
    FillRect(dc, &status_box, status_brush);
    DeleteObject(status_brush);
    HBRUSH status_accent = CreateSolidBrush(RGB(31, 181, 125));
    RECT status_bar{24, 668, 30, 718};
    FillRect(dc, &status_bar, status_accent);
    DeleteObject(status_accent);
    SelectObject(dc, state.body_font);
    SetTextColor(dc, RGB(31, 43, 51));
    RECT status{44, 676, client.right - 44, 710};
    DrawTextW(dc, state.status.c_str(), -1, &status, DT_LEFT | DT_WORDBREAK | DT_VCENTER);
    SelectObject(dc, prior);
    EndPaint(window, &paint);
}

void draw_button(const DRAWITEMSTRUCT &item, const AppState &state)
{
    const bool pressed = (item.itemState & ODS_SELECTED) != 0;
    COLORREF background = RGB(24, 35, 44);
    COLORREF foreground = RGB(250, 251, 247);
    if (item.CtlID == kControlRotate) background = pressed ? RGB(22, 145, 100) : RGB(31, 181, 125);
    if (item.CtlID == kControlOpenObs) {
        background = pressed ? RGB(218, 216, 208) : RGB(250, 248, 242);
        foreground = RGB(31, 43, 51);
    }
    HBRUSH brush = CreateSolidBrush(background);
    FillRect(item.hDC, &item.rcItem, brush);
    DeleteObject(brush);
    HPEN pen = CreatePen(PS_SOLID, 1, item.CtlID == kControlOpenObs ? RGB(138, 145, 143) : background);
    const auto old_pen = SelectObject(item.hDC, pen);
    const auto old_brush = SelectObject(item.hDC, GetStockObject(NULL_BRUSH));
    Rectangle(item.hDC, item.rcItem.left, item.rcItem.top, item.rcItem.right, item.rcItem.bottom);
    SelectObject(item.hDC, old_brush);
    SelectObject(item.hDC, old_pen);
    DeleteObject(pen);

    wchar_t text[96]{};
    GetWindowTextW(item.hwndItem, text, static_cast<int>(std::size(text)));
    SetBkMode(item.hDC, TRANSPARENT);
    SetTextColor(item.hDC, foreground);
    const auto old_font = SelectObject(item.hDC, state.body_font);
    RECT text_rect = item.rcItem;
    if (pressed) OffsetRect(&text_rect, 0, 1);
    DrawTextW(item.hDC, text, -1, &text_rect, DT_CENTER | DT_SINGLELINE | DT_VCENTER);
    if ((item.itemState & ODS_FOCUS) != 0) {
        RECT focus = item.rcItem;
        InflateRect(&focus, -4, -4);
        DrawFocusRect(item.hDC, &focus);
    }
    SelectObject(item.hDC, old_font);
}

LRESULT CALLBACK window_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam)
{
    auto *state = reinterpret_cast<AppState *>(GetWindowLongPtrW(window, GWLP_USERDATA));
    switch (message) {
    case WM_NCCREATE: {
        const auto *create = reinterpret_cast<const CREATESTRUCTW *>(lparam);
        SetWindowLongPtrW(window, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(create->lpCreateParams));
        return DefWindowProcW(window, message, wparam, lparam);
    }
    case WM_CREATE: {
        state = reinterpret_cast<AppState *>(GetWindowLongPtrW(window, GWLP_USERDATA));
        if (state == nullptr) return -1;
        state->title_font = make_font(20, FW_SEMIBOLD, L"Bahnschrift SemiBold");
        state->body_font = make_font(10, FW_NORMAL, L"Segoe UI Variable Text");
        state->small_font = make_font(9, FW_NORMAL, L"Segoe UI Variable Text");
        state->mono_font = make_font(9, FW_SEMIBOLD, L"Consolas");
        state->background_brush = CreateSolidBrush(RGB(242, 239, 231));
        add_control(window, 0, WC_STATICW, L"Network", SS_LEFT, 36, 168, 106, 24, 0, state->small_font);
        state->network = add_control(
            window, WS_EX_CLIENTEDGE, WC_COMBOBOXW, L"", CBS_DROPDOWNLIST | WS_TABSTOP,
            150, 164, 490, 240, kControlNetwork, state->body_font);
        add_control(window, 0, WC_STATICW, L"Receiver name", SS_LEFT, 36, 210, 106, 24, 0, state->small_font);
        state->label = add_control(
            window, WS_EX_CLIENTEDGE, WC_EDITW, L"My PC", ES_AUTOHSCROLL | WS_TABSTOP,
            150, 206, 260, 28, kControlLabel, state->body_font);
        SendMessageW(state->label, EM_SETLIMITTEXT, 48, 0);
        add_control(window, 0, WC_STATICW, L"Port", SS_LEFT, 428, 210, 42, 24, 0, state->small_font);
        state->port = add_control(
            window, WS_EX_CLIENTEDGE, WC_EDITW, L"9000", ES_NUMBER | ES_AUTOHSCROLL | ES_READONLY,
            474, 206, 72, 28, kControlPort, state->body_font);
        SendMessageW(state->port, EM_SETLIMITTEXT, 5, 0);
        add_control(window, 0, WC_STATICW, L"Latency", SS_LEFT, 36, 250, 106, 24, 0, state->small_font);
        state->latency = add_control(
            window, WS_EX_CLIENTEDGE, WC_EDITW, L"120", ES_NUMBER | ES_AUTOHSCROLL | WS_TABSTOP,
            150, 246, 80, 28, kControlLatency, state->body_font);
        SendMessageW(state->latency, EM_SETLIMITTEXT, 4, 0);
        add_control(window, 0, WC_STATICW, L"milliseconds", SS_LEFT, 240, 250, 100, 24, 0, state->small_font);
        add_control(
            window, 0, WC_BUTTONW, L"Show pairing QR", BS_OWNERDRAW | WS_TABSTOP,
            382, 402, 266, 44, kControlRotate, state->body_font);
        add_control(
            window, 0, WC_BUTTONW, L"Open OBS Studio", BS_OWNERDRAW | WS_TABSTOP,
            382, 458, 266, 44, kControlOpenObs, state->body_font);
        add_control(
            window, 0, WC_BUTTONW, L"Finish && run in background", BS_OWNERDRAW | WS_TABSTOP,
            382, 514, 266, 44, kControlFinish, state->body_font);
        populate_networks(*state);
        if (state->addresses.empty()) {
            state->status = L"Connect this PC to a private Wi-Fi or Ethernet network first.";
        } else {
            const SavedSettingsResult saved = load_saved_settings(*state);
            if (saved == SavedSettingsResult::restored) {
                state->status = L"Saved pairing restored. Show a QR only when pairing another phone.";
            } else if (saved == SavedSettingsResult::network_changed) {
                state->status = L"A better local network was found. Show a new QR once to update your phone.";
            }
        }
        add_tray_icon(window, *state);
        state->control_server = std::make_unique<PairingControlServer>(window, kShowQrMessage);
        if (!state->control_server->start()) {
            state->status = L"The OBS pairing button is unavailable. Open this helper from the system tray.";
        }
        SetTimer(window, kCountdownTimer, 1000, nullptr);
        return 0;
    }
    case WM_COMMAND:
        if (state != nullptr && (HIWORD(wparam) == BN_CLICKED || HIWORD(wparam) == 0)) {
            if (LOWORD(wparam) == kControlRotate) {
                create_pairing_qr(window, *state);
                return 0;
            }
            if (LOWORD(wparam) == kControlOpenObs) {
                open_obs(window);
                return 0;
            }
            if (LOWORD(wparam) == kControlFinish) {
                auto config = load_or_create_config(window, *state);
                if (!config) return 0;
                config->clear_secret();
                set_status(window, *state, L"Setup saved. The helper is still available from the system tray.");
                MessageBeep(MB_OK);
                hide_to_tray(window, *state);
                return 0;
            }
            if (LOWORD(wparam) == kTrayOpen) {
                show_main_window(window);
                return 0;
            }
            if (LOWORD(wparam) == kTrayOpenObs) {
                open_obs(window);
                return 0;
            }
            if (LOWORD(wparam) == kTrayExit) {
                state->allow_exit = true;
                DestroyWindow(window);
                return 0;
            }
        }
        break;
    case WM_TIMER:
        if (wparam == kCountdownTimer) {
            if (state != nullptr && state->qr && state->qr_expires <= lcr::now_epoch_seconds()) {
                state->clear_pairing_material();
                state->status = L"QR expired. Your saved receiver settings are still ready.";
            }
            InvalidateRect(window, nullptr, FALSE);
            return 0;
        }
        break;
    case WM_DRAWITEM:
        if (state != nullptr && lparam != 0) {
            const auto *item = reinterpret_cast<const DRAWITEMSTRUCT *>(lparam);
            if (item->CtlType == ODT_BUTTON) {
                draw_button(*item, *state);
                return TRUE;
            }
        }
        break;
    case WM_CTLCOLORSTATIC:
        if (state != nullptr && state->background_brush != nullptr) {
            SetBkMode(reinterpret_cast<HDC>(wparam), TRANSPARENT);
            SetTextColor(reinterpret_cast<HDC>(wparam), RGB(45, 54, 57));
            return reinterpret_cast<LRESULT>(state->background_brush);
        }
        break;
    case kTrayCallbackMessage:
        if (state != nullptr) {
            const UINT event = LOWORD(lparam);
            if (event == WM_LBUTTONUP || event == NIN_SELECT || event == NIN_KEYSELECT) {
                show_main_window(window);
                return 0;
            }
            if (event == WM_RBUTTONUP || event == WM_CONTEXTMENU) {
                show_tray_menu(window);
                return 0;
            }
        }
        break;
    case kActivateWindowMessage:
        show_main_window(window);
        return 0;
    case kShowQrMessage:
        if (state != nullptr) {
            show_main_window(window);
            create_pairing_qr(window, *state);
        }
        return 0;
    case WM_PAINT:
        if (state != nullptr) {
            paint_window(window, *state);
            return 0;
        }
        break;
    case WM_ERASEBKGND:
        return 1;
    case WM_CLOSE:
        if (state != nullptr && !state->allow_exit) {
            hide_to_tray(window, *state);
            return 0;
        }
        break;
    case WM_QUERYENDSESSION:
        return TRUE;
    case WM_ENDSESSION:
        if (wparam != FALSE && state != nullptr) {
            state->allow_exit = true;
            DestroyWindow(window);
            return 0;
        }
        break;
    case WM_DESTROY:
        KillTimer(window, kCountdownTimer);
        if (state != nullptr) remove_tray_icon(*state);
        PostQuitMessage(0);
        return 0;
    default:
        break;
    }
    return DefWindowProcW(window, message, wparam, lparam);
}

} // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int show_command)
{
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    INITCOMMONCONTROLSEX controls{sizeof(controls), ICC_STANDARD_CLASSES};
    InitCommonControlsEx(&controls);

    int argument_count = 0;
    LPWSTR *arguments = CommandLineToArgvW(GetCommandLineW(), &argument_count);
    bool background_start = false;
    bool show_qr_start = false;
    if (arguments != nullptr) {
        for (int index = 1; index < argument_count; ++index) {
            switch (lcr::pairing_launch_action(arguments[index])) {
            case lcr::PairingLaunchAction::background:
                background_start = true;
                break;
            case lcr::PairingLaunchAction::show_qr:
                show_qr_start = true;
                background_start = false;
                break;
            case lcr::PairingLaunchAction::none:
                break;
            }
        }
        LocalFree(arguments);
    }

    HANDLE instance_mutex = CreateMutexW(nullptr, FALSE, L"Local\\LocalCameraReceiverPairing-18C4262B");
    if (instance_mutex == nullptr) return 1;
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        if (show_qr_start && send_show_qr_control_command()) {
            CloseHandle(instance_mutex);
            return 0;
        }
        if (show_qr_start || !background_start) {
            if (HWND existing = FindWindowW(kWindowClass, nullptr)) {
                PostMessageW(existing, show_qr_start ? kShowQrMessage : kActivateWindowMessage, 0, 0);
            }
        }
        CloseHandle(instance_mutex);
        return 0;
    }

    WNDCLASSEXW window_class{};
    window_class.cbSize = sizeof(window_class);
    window_class.style = CS_HREDRAW | CS_VREDRAW;
    window_class.lpfnWndProc = window_proc;
    window_class.hInstance = instance;
    window_class.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    window_class.hIcon = LoadIconW(nullptr, IDI_APPLICATION);
    window_class.hIconSm = LoadIconW(nullptr, IDI_APPLICATION);
    window_class.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
    window_class.lpszClassName = kWindowClass;
    if (RegisterClassExW(&window_class) == 0) return 1;

    auto state = std::make_unique<AppState>();
    HWND window = CreateWindowExW(
        0,
        kWindowClass,
        kWindowTitle,
        WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
        CW_USEDEFAULT,
        CW_USEDEFAULT,
        700,
        770,
        nullptr,
        nullptr,
        instance,
        state.get());
    if (window == nullptr) {
        CloseHandle(instance_mutex);
        return 1;
    }
    if (!background_start) {
        ShowWindow(window, show_command == SW_HIDE ? SW_SHOWNORMAL : show_command);
        UpdateWindow(window);
    }
    if (show_qr_start) PostMessageW(window, kShowQrMessage, 0, 0);

    MSG message{};
    while (GetMessageW(&message, nullptr, 0, 0) > 0) {
        if (!IsDialogMessageW(window, &message)) {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
    }
    state.reset();
    CloseHandle(instance_mutex);
    return static_cast<int>(message.wParam);
}
