#include "local_camera_receiver/obs_api.hpp"
#include "local_camera_receiver/pairing_control_protocol.hpp"
#include "local_camera_receiver/receiver_config.hpp"
#include "local_camera_receiver/receiver_source_contract.hpp"
#include "local_camera_receiver/receiver_status_text.hpp"
#include "local_camera_receiver/srt_listener.hpp"

#include <Windows.h>
#include <bcrypt.h>
#include <sddl.h>
#include <shellapi.h>

#include <array>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <memory>
#include <string>
#include <thread>
#include <vector>

namespace lcr {
namespace {

constexpr char kSourceId[] = "local_camera_receiver_source";
constexpr char kPipeIdSetting[] = "pipe_id";
constexpr char kListenHiddenSetting[] = "listen_when_hidden";
constexpr char kHardwareDecodeSetting[] = "hardware_decode";
constexpr char kSoftwareDecoderMigrationSetting[] = "software_decoder_migration_v1";

std::string new_uuid() noexcept
{
    std::array<std::uint8_t, 16> bytes{};
    if (BCryptGenRandom(nullptr, bytes.data(), static_cast<ULONG>(bytes.size()), BCRYPT_USE_SYSTEM_PREFERRED_RNG) != 0) return {};
    bytes[6] = static_cast<std::uint8_t>((bytes[6] & 0x0fU) | 0x40U);
    bytes[8] = static_cast<std::uint8_t>((bytes[8] & 0x3fU) | 0x80U);
    std::array<char, 37> text{};
    const int written = std::snprintf(
        text.data(), text.size(),
        "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
        bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
        bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]);
    return written == 36 ? std::string(text.data(), 36) : std::string{};
}

std::string utf8(const std::wstring &text)
{
    if (text.empty()) return {};
    const int required = WideCharToMultiByte(
        CP_UTF8, WC_ERR_INVALID_CHARS, text.data(), static_cast<int>(text.size()), nullptr, 0, nullptr, nullptr);
    if (required <= 0) return {};
    std::string output(static_cast<std::size_t>(required), '\0');
    if (WideCharToMultiByte(
            CP_UTF8, WC_ERR_INVALID_CHARS, text.data(), static_cast<int>(text.size()), output.data(), required,
            nullptr, nullptr) != required) return {};
    return output;
}

std::filesystem::path module_directory() noexcept
{
    static int anchor = 0;
    HMODULE module = nullptr;
    if (GetModuleHandleExW(
            GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
            reinterpret_cast<LPCWSTR>(&anchor), &module) == FALSE) return {};
    std::wstring path(32768, L'\0');
    const DWORD length = GetModuleFileNameW(module, path.data(), static_cast<DWORD>(path.size()));
    if (length == 0 || length >= path.size()) return {};
    path.resize(length);
    return std::filesystem::path(path).parent_path();
}

bool open_pairing_app(obs_properties_t *, obs_property_t *, void *)
{
    const auto executable = module_directory() / L"LocalCameraReceiver.exe";
    if (!std::filesystem::is_regular_file(executable)) return false;
    return reinterpret_cast<std::intptr_t>(
        ShellExecuteW(nullptr, L"open", executable.c_str(), L"/show-qr", executable.parent_path().c_str(), SW_SHOWNORMAL)) > 32;
}

bool refresh_receiver_status(obs_properties_t *, obs_property_t *, void *) { return true; }

std::wstring current_user_control_pipe_name() noexcept
{
    HANDLE token = nullptr;
    if (OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token) == FALSE) return {};
    DWORD required = 0;
    GetTokenInformation(token, TokenUser, nullptr, 0, &required);
    if (required == 0 || required > 64U * 1024U) {
        CloseHandle(token);
        return {};
    }
    std::vector<std::uint8_t> buffer(required);
    const bool queried = GetTokenInformation(token, TokenUser, buffer.data(), required, &required) != FALSE;
    CloseHandle(token);
    if (!queried) return {};
    const auto *user = reinterpret_cast<const TOKEN_USER *>(buffer.data());
    LPWSTR sid = nullptr;
    if (ConvertSidToStringSidW(user->User.Sid, &sid) == FALSE || sid == nullptr) return {};
    std::wstring name = L"\\\\.\\pipe\\local-camera-receiver-control-" + std::wstring(sid);
    LocalFree(sid);
    return name;
}

PairingReceiverState pairing_state(const ReceiverState state) noexcept
{
    switch (state) {
    case ReceiverState::stopped: return PairingReceiverState::stopped;
    case ReceiverState::waiting_for_pairing: return PairingReceiverState::waiting_for_pairing;
    case ReceiverState::listening: return PairingReceiverState::listening;
    case ReceiverState::authenticating: return PairingReceiverState::authenticating;
    case ReceiverState::streaming: return PairingReceiverState::streaming;
    case ReceiverState::reconnecting: return PairingReceiverState::reconnecting;
    case ReceiverState::failed: return PairingReceiverState::failed;
    }
    return PairingReceiverState::failed;
}

void publish_pairing_helper_status(const ReceiverStatus &status) noexcept
{
    const std::wstring pipe_name = current_user_control_pipe_name();
    if (pipe_name.empty() || WaitNamedPipeW(pipe_name.c_str(), 25) == FALSE) return;
    const HANDLE pipe = CreateFileW(
        pipe_name.c_str(), GENERIC_WRITE, 0, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (pipe == INVALID_HANDLE_VALUE) return;
    const auto command = receiver_status_command(pairing_state(status.state));
    DWORD written = 0;
    WriteFile(pipe, command.data(), static_cast<DWORD>(command.size()), &written, nullptr);
    CloseHandle(pipe);
}

class ReceiverSource final {
public:
    ReceiverSource(obs_data_t *settings, obs_source_t *source) : source_(source)
    {
        auto &api = obs_api();
        std::string pipe_id = api.data_get_string(settings, kPipeIdSetting);
        if (!is_canonical_uuid(pipe_id)) {
            pipe_id = new_uuid();
            if (!is_canonical_uuid(pipe_id)) return;
            api.data_set_string(settings, kPipeIdSetting, pipe_id.c_str());
        }
        listen_when_hidden_.store(api.data_get_bool(settings, kListenHiddenSetting));

        // Builds before 0.2.0 could persist hardware decoding as enabled. Changing
        // the default did not alter those saved OBS source settings, and HEVC/SRT
        // hardware decode has known reliability problems in OBS. Migrate every
        // existing/new source exactly once to the reliable software default. A user
        // can explicitly enable hardware decoding again after this marker is saved.
        if (!api.data_get_bool(settings, kSoftwareDecoderMigrationSetting)) {
            api.data_set_bool(settings, kHardwareDecodeSetting, false);
            api.data_set_bool(settings, kSoftwareDecoderMigrationSetting, true);
        }
        hardware_decode_.store(api.data_get_bool(settings, kHardwareDecodeSetting));

        const std::wstring pipe_name = L"\\\\.\\pipe\\local-camera-receiver-" + std::wstring(pipe_id.begin(), pipe_id.end());
        sink_ = std::make_unique<NamedPipeSink>(pipe_name);
        if (!sink_->start()) return;

        obs_data_t *child_settings = api.data_create();
        if (child_settings == nullptr) return;
        const std::string pipe_utf8 = utf8(pipe_name);
        api.data_set_bool(child_settings, "is_local_file", false);
        api.data_set_string(child_settings, "input", pipe_utf8.c_str());
        api.data_set_string(child_settings, "input_format", "mpegts");
        api.data_set_bool(child_settings, "restart_on_activate", false);
        api.data_set_bool(child_settings, "close_when_inactive", false);
        api.data_set_bool(child_settings, "clear_on_media_end", true);
        api.data_set_bool(child_settings, "hw_decode", hardware_decode_.load());
        api.data_set_bool(child_settings, "log_changes", true);
        api.data_set_int(child_settings, "buffering_mb", 1);
        api.data_set_int(child_settings, "reconnect_delay_sec", 1);
        child_ = api.source_create_private("ffmpeg_source", "Local Camera Receiver media", child_settings);
        api.data_release(child_settings);
        if (child_ == nullptr) return;
        if (!api.source_add_active_child(source_, child_)) return;
        child_active_.store(true);

        listener_ = std::make_unique<SrtListener>(
            *sink_, publish_pairing_helper_status,
            [this]() noexcept { return decoded_ready_cache_.load(); });
        valid_.store(true);
        watcher_ = std::thread(&ReceiverSource::watch_config, this);
    }

    ~ReceiverSource()
    {
        stopping_.store(true);
        if (watcher_.joinable()) watcher_.join();
        if (listener_) listener_->stop();
        if (child_active_.exchange(false) && child_) obs_api().source_remove_active_child(source_, child_);
        if (child_) obs_api().source_release(child_);
        if (sink_) sink_->stop();
    }

    [[nodiscard]] bool valid() const noexcept { return valid_.load(); }
    [[nodiscard]] ReceiverStatus status() const noexcept { return listener_ ? listener_->status() : ReceiverStatus{}; }
    void activate() noexcept { active_.store(true); }
    void deactivate() noexcept { active_.store(false); }

    void enum_child(obs_source_enum_proc_t callback, void *param) const noexcept
    {
        if (child_ != nullptr && callback != nullptr) callback(source_, child_, param);
    }
    void tick() noexcept
    {
        if (child_ == nullptr) {
            decoded_ready_cache_.store(false);
            return;
        }
        auto &api = obs_api();
        obs_source_frame *frame = api.source_get_frame(child_);
        if (frame == nullptr) {
            decoded_ready_cache_.store(false);
            return;
        }
        const bool ready = frame->width > 0U && frame->height > 0U;
        api.source_release_frame(child_, frame);
        decoded_ready_cache_.store(ready);
    }

    void update(obs_data_t *settings) noexcept
    {
        auto &api = obs_api();
        listen_when_hidden_.store(api.data_get_bool(settings, kListenHiddenSetting));
        const bool requested_hw = api.data_get_bool(settings, kHardwareDecodeSetting);
        if (requested_hw == hardware_decode_.exchange(requested_hw) || child_ == nullptr) return;
        obs_data_t *child_settings = api.source_get_settings(child_);
        if (child_settings == nullptr) return;
        api.data_set_bool(child_settings, "hw_decode", requested_hw);
        api.source_update(child_, child_settings);
        api.data_release(child_settings);
    }

    void render() noexcept { if (child_) obs_api().source_video_render(child_); }
    [[nodiscard]] std::uint32_t width() const noexcept { return child_ ? obs_api().source_get_width(child_) : 0; }
    [[nodiscard]] std::uint32_t height() const noexcept { return child_ ? obs_api().source_get_height(child_) : 0; }

    [[nodiscard]] bool audio_render(
        std::uint64_t *timestamp_out, obs_source_audio_mix *audio_output, std::uint32_t mixers,
        std::size_t channels, std::size_t sample_rate) const noexcept
    {
        if (child_ == nullptr || timestamp_out == nullptr || audio_output == nullptr) return false;
        auto &api = obs_api();
        if (api.source_audio_pending(child_)) return false;
        const std::uint64_t timestamp = api.source_get_audio_timestamp(child_);
        if (timestamp == 0U) return false;
        obs_source_audio_mix child_audio{};
        api.source_get_audio_mix(child_, &child_audio);
        for (std::size_t mix = 0; mix < MAX_AUDIO_MIXES; ++mix) {
            if ((mixers & (1U << mix)) == 0U) continue;
            for (std::size_t channel = 0; channel < channels; ++channel) {
                std::memcpy(audio_output->output[mix].data[channel], child_audio.output[mix].data[channel],
                            AUDIO_OUTPUT_FRAMES * sizeof(float));
            }
        }
        *timestamp_out = timestamp;
        (void)sample_rate;
        return true;
    }

private:
    [[nodiscard]] bool decoded_frame_ready() const noexcept
    {
        if (child_ == nullptr) return false;
        auto &api = obs_api();
        obs_source_frame *frame = api.source_get_frame(child_);
        if (frame == nullptr) return false;
        const bool ready = frame->width > 0U && frame->height > 0U;
        api.source_release_frame(child_, frame);
        return ready;
    }

    void watch_config() noexcept
    {
        std::filesystem::file_time_type last_write{};
        bool listener_started = false;
        while (!stopping_.load()) {
            const bool should_listen = active_.load() || listen_when_hidden_.load();
            if (listener_started) {
                const ReceiverStatus snapshot = listener_->status();
                publish_pairing_helper_status(snapshot); // heartbeat makes helper state eventually consistent.
                if (snapshot.state == ReceiverState::failed || snapshot.state == ReceiverState::stopped) {
                    listener_->stop();
                    listener_started = false;
                }
            }
            const auto path = default_config_path();
            std::error_code error;
            const auto current_write = std::filesystem::last_write_time(path, error);
            const bool changed = !error && current_write != last_write;
            if (!should_listen && listener_started) {
                listener_->stop();
                listener_started = false;
            } else if (should_listen && (changed || !listener_started)) {
                if (listener_started) listener_->stop();
                ConfigError load_error = ConfigError::none;
                auto config = load_config_dpapi(path, now_epoch_seconds(), load_error);
                if (config) {
                    listener_started = listener_->start(*config);
                    config->clear_secret();
                    last_write = current_write;
                } else {
                    listener_started = false;
                }
            }
            for (int step = 0; step < 10 && !stopping_.load(); ++step) {
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
        }
        if (listener_started) listener_->stop();
    }

    obs_source_t *source_ = nullptr;
    obs_source_t *child_ = nullptr;
    std::unique_ptr<NamedPipeSink> sink_;
    std::unique_ptr<SrtListener> listener_;
    std::atomic<bool> valid_{false};
    std::atomic<bool> active_{false};
    std::atomic<bool> listen_when_hidden_{true};
    std::atomic<bool> hardware_decode_{false};
    std::atomic<bool> child_active_{false};
    std::atomic<bool> stopping_{false};
    std::atomic<bool> decoded_ready_cache_{false};
    std::thread watcher_;
};

const char *source_name(void *) { return "Local Camera Receiver"; }
void *source_create(obs_data_t *settings, obs_source_t *source)
{
    auto receiver = std::make_unique<ReceiverSource>(settings, source);
    return receiver->valid() ? receiver.release() : nullptr;
}
void source_destroy(void *data) { delete static_cast<ReceiverSource *>(data); }
void source_update(void *data, obs_data_t *settings) { static_cast<ReceiverSource *>(data)->update(settings); }
void source_activate(void *data) { static_cast<ReceiverSource *>(data)->activate(); }
void source_deactivate(void *data) { static_cast<ReceiverSource *>(data)->deactivate(); }
void source_tick(void *data, float seconds)
{
    static_cast<ReceiverSource *>(data)->tick();
    (void)seconds;
}
void source_enum_sources(void *data, obs_source_enum_proc_t callback, void *param)
{
    if (data == nullptr) return;
    static_cast<ReceiverSource *>(data)->enum_child(callback, param);
}
void source_render(void *data, gs_effect_t *) { static_cast<ReceiverSource *>(data)->render(); }
std::uint32_t source_width(void *data) { return static_cast<ReceiverSource *>(data)->width(); }
std::uint32_t source_height(void *data) { return static_cast<ReceiverSource *>(data)->height(); }
bool source_audio_render(
    void *data, std::uint64_t *timestamp_out, obs_source_audio_mix *audio_output,
    std::uint32_t mixers, std::size_t channels, std::size_t sample_rate)
{
    return static_cast<ReceiverSource *>(data)->audio_render(timestamp_out, audio_output, mixers, channels, sample_rate);
}

void source_defaults(obs_data_t *settings)
{
    obs_api().data_set_default_bool(settings, kListenHiddenSetting, true);
    obs_api().data_set_default_bool(settings, kHardwareDecodeSetting, false);
    obs_api().data_set_default_string(settings, kPipeIdSetting, "");
}

obs_properties_t *source_properties(void *data)
{
    auto &api = obs_api();
    obs_properties_t *properties = api.properties_create();
    if (properties == nullptr) return nullptr;
    const auto *receiver = static_cast<ReceiverSource *>(data);
    const std::string status = receiver_status_text(receiver != nullptr ? receiver->status() : ReceiverStatus{});
    obs_property_t *connection = api.properties_add_text(properties, "connection_status", status.c_str(), OBS_TEXT_INFO);
    if (connection != nullptr) {
        api.property_set_long_description(connection,
            "Streaming is reported only after an authenticated connection is producing valid MPEG-TS and OBS has decoded a video frame.");
    }
    api.properties_add_button2(properties, "refresh_status", "Refresh connection status", refresh_receiver_status, nullptr);
    obs_property_t *info = api.properties_add_text(
        properties, "pairing_help",
        "No video yet? Show the pairing QR and scan it in the phone app. Streaming starts automatically.", OBS_TEXT_INFO);
    if (info != nullptr) {
        api.property_set_long_description(info,
            "The QR opens over OBS and the saved pairing secret stays encrypted for this Windows user.");
    }
    api.properties_add_button2(properties, "open_pairing", "Show pairing QR", open_pairing_app, nullptr);
    obs_property_t *hardware = api.properties_add_bool(properties, kHardwareDecodeSetting, "Use hardware decoding");
    if (hardware != nullptr) {
        api.property_set_long_description(hardware,
            "Software decoding is the reliability default for HEVC/SRT. Existing sources are migrated to it once; enable hardware decoding only after the software path works on this PC.");
    }
    api.properties_add_bool(properties, kListenHiddenSetting, "Keep listening when hidden");
    return properties;
}

} // namespace

bool register_receiver_source() noexcept
{
    obs_source_info info{};
    info.id = kSourceId;
    info.type = OBS_SOURCE_TYPE_INPUT;
    info.output_flags = kReceiverSourceOutputFlags;
    info.get_name = source_name;
    info.create = source_create;
    info.destroy = source_destroy;
    info.get_width = source_width;
    info.get_height = source_height;
    info.get_defaults = source_defaults;
    info.get_properties = source_properties;
    info.update = source_update;
    info.activate = source_activate;
    info.deactivate = source_deactivate;
    info.enum_active_sources = source_enum_sources;
    info.enum_all_sources = source_enum_sources;
    info.video_tick = source_tick;
    info.video_render = source_render;
    info.audio_render = source_audio_render;
    obs_api().register_source_s(&info, sizeof(info));
    return true;
}

} // namespace lcr
