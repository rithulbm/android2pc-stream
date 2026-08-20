#include "local_camera_receiver/named_pipe_sink.hpp"

#include <Windows.h>
#include <sddl.h>

#include <array>
#include <chrono>
#include <thread>
#include <vector>

namespace lcr {
namespace {

struct SecurityDescriptor final {
    PSECURITY_DESCRIPTOR descriptor = nullptr;
    SECURITY_ATTRIBUTES attributes{sizeof(SECURITY_ATTRIBUTES), nullptr, FALSE};

    ~SecurityDescriptor()
    {
        if (descriptor != nullptr) {
            LocalFree(descriptor);
        }
    }
};

bool current_user_only(SecurityDescriptor &security) noexcept
{
    HANDLE token = nullptr;
    if (OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token) == FALSE) {
        return false;
    }
    DWORD required = 0;
    GetTokenInformation(token, TokenUser, nullptr, 0, &required);
    if (required == 0 || required > 64U * 1024U) {
        CloseHandle(token);
        return false;
    }
    std::vector<std::uint8_t> buffer(required);
    const bool queried = GetTokenInformation(token, TokenUser, buffer.data(), required, &required) != FALSE;
    CloseHandle(token);
    if (!queried) {
        return false;
    }
    const auto *user = reinterpret_cast<const TOKEN_USER *>(buffer.data());
    LPWSTR sid = nullptr;
    if (ConvertSidToStringSidW(user->User.Sid, &sid) == FALSE || sid == nullptr) {
        return false;
    }
    const std::wstring sddl = L"D:P(A;;GA;;;" + std::wstring(sid) + L")";
    LocalFree(sid);
    if (ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl.c_str(),
            SDDL_REVISION_1,
            &security.descriptor,
            nullptr) == FALSE) {
        return false;
    }
    security.attributes.lpSecurityDescriptor = security.descriptor;
    return true;
}

} // namespace

NamedPipeSink::NamedPipeSink(std::wstring pipe_name) : pipe_name_(std::move(pipe_name)) {}

NamedPipeSink::~NamedPipeSink()
{
    stop();
}

bool NamedPipeSink::start()
{
    if (pipe_name_.size() < 24 || pipe_name_.size() > 180 ||
        !pipe_name_.starts_with(L"\\\\.\\pipe\\local-camera-receiver-")) {
        return false;
    }
    bool expected = false;
    if (!running_.compare_exchange_strong(expected, true)) {
        return true;
    }
    if (worker_.joinable()) {
        worker_.join();
    }
    restart_transport_.store(false);
    client_connected_.store(false);
    queue_.reset();
    worker_ = std::thread(&NamedPipeSink::run, this);
    return true;
}

void NamedPipeSink::stop() noexcept
{
    running_.store(false);
    restart_transport_.store(false);
    client_connected_.store(false);
    queue_.cancel();
    const auto raw = pipe_.exchange(nullptr);
    if (raw != nullptr && raw != INVALID_HANDLE_VALUE) {
        const auto handle = static_cast<HANDLE>(raw);
        CancelIoEx(handle, nullptr);
        DisconnectNamedPipe(handle);
        CloseHandle(handle);
    }
    if (worker_.joinable()) {
        worker_.join();
    }
    queue_.clear();
}

bool NamedPipeSink::enqueue(const std::span<const std::uint8_t> packet)
{
    if (!running_.load()) return false;
    if (restart_transport_.exchange(false)) {
        // The decoder pipe disappeared while media was live. Reject one TS group so
        // SrtListener tears down the peer. The sender then reconnects with a fresh
        // keyframe and transport tables instead of feeding a new decoder mid-GOP.
        queue_.clear();
        return false;
    }
    return queue_.push(packet);
}

const std::wstring &NamedPipeSink::pipe_name() const noexcept
{
    return pipe_name_;
}

void NamedPipeSink::run() noexcept
{
    while (running_.load()) {
        SecurityDescriptor security{};
        if (!current_user_only(security)) {
            running_.store(false);
            break;
        }
        const HANDLE handle = CreateNamedPipeW(
            pipe_name_.c_str(),
            PIPE_ACCESS_OUTBOUND,
            PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
            1,
            1024U * 1024U,
            1024U * 1024U,
            0,
            &security.attributes);
        if (handle == INVALID_HANDLE_VALUE) {
            if (running_.load()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(75));
            }
            continue;
        }
        pipe_.store(handle);
        const bool connected = ConnectNamedPipe(handle, nullptr) != FALSE || GetLastError() == ERROR_PIPE_CONNECTED;
        client_connected_.store(connected);
        if (connected) {
            bool pipe_broken = false;
            while (running_.load()) {
                auto packet = queue_.wait_pop();
                if (!packet) {
                    break;
                }
                std::size_t offset = 0;
                while (running_.load() && offset < packet->size()) {
                    DWORD written = 0;
                    const auto remaining = packet->size() - offset;
                    if (WriteFile(
                            handle,
                            packet->data() + offset,
                            static_cast<DWORD>(remaining),
                            &written,
                            nullptr) == FALSE || written == 0) {
                        pipe_broken = true;
                        offset = packet->size();
                        break;
                    }
                    offset += written;
                }
                SecureZeroMemory(packet->data(), packet->size());
                if (pipe_broken) {
                    client_connected_.store(false);
                    queue_.clear();
                    restart_transport_.store(true);
                    break;
                }
            }
        }
        client_connected_.store(false);
        void *expected = handle;
        if (pipe_.compare_exchange_strong(expected, nullptr)) {
            DisconnectNamedPipe(handle);
            CloseHandle(handle);
        }
    }
    client_connected_.store(false);
}

} // namespace lcr
