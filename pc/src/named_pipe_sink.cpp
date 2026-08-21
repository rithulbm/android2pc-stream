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
    // The worker stores the handle after CreateNamedPipeW and before
    // ConnectNamedPipe; a stop() racing that window exchanges nothing above. The
    // worker re-checks running_ after storing, but sweep once more post-join so no
    // handle or listening instance can outlive this object under any interleaving.
    const auto leftover = pipe_.exchange(nullptr);
    if (leftover != nullptr && leftover != INVALID_HANDLE_VALUE) {
        const auto handle = static_cast<HANDLE>(leftover);
        CancelIoEx(handle, nullptr);
        DisconnectNamedPipe(handle);
        CloseHandle(handle);
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
    std::chrono::milliseconds create_backoff{75};
    while (running_.load()) {
        SecurityDescriptor security{};
        if (!current_user_only(security)) {
            running_.store(false);
            break;
        }
        // FILE_FLAG_FIRST_PIPE_INSTANCE claims the name exclusively: a second
        // creator (duplicate source settings, second OBS instance) fails loudly
        // with ERROR_ACCESS_DENIED instead of silently coexisting.
        const HANDLE handle = CreateNamedPipeW(
            pipe_name_.c_str(),
            PIPE_ACCESS_OUTBOUND | FILE_FLAG_FIRST_PIPE_INSTANCE,
            PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
            1,
            1024U * 1024U,
            1024U * 1024U,
            0,
            &security.attributes);
        if (handle == INVALID_HANDLE_VALUE) {
            if (running_.load()) {
                create_error_.store(GetLastError());
                // Exponential backoff: 75ms..800ms. A squatted name never
                // recovers by spinning hot; a transient conflict recovers on the
                // next attempt either way.
                std::this_thread::sleep_for(create_backoff);
                create_backoff = std::min<std::chrono::milliseconds>(create_backoff * 2, std::chrono::milliseconds{800});
            }
            continue;
        }
        create_error_.store(0);
        create_backoff = std::chrono::milliseconds{75};
        pipe_.store(handle);
        if (!running_.load()) {
            // stop() may have exchanged nothing while this handle was being
            // created; fall through to the shared cleanup below instead of
            // blocking in ConnectNamedPipe forever.
            client_connected_.store(false);
            void *expected = handle;
            if (pipe_.compare_exchange_strong(expected, nullptr)) {
                DisconnectNamedPipe(handle);
                CloseHandle(handle);
            }
            break;
        }
        const bool connected = ConnectNamedPipe(handle, nullptr) != FALSE || GetLastError() == ERROR_PIPE_CONNECTED;
        client_connected_.store(connected);
        bool pipe_broken = false;
        if (connected) {
            while (running_.load()) {
                auto packet = queue_.wait_pop_for(std::chrono::milliseconds{250});
                if (!packet) {
                    if (!running_.load()) {
                        break;
                    }
                    // Idle window. The client can disconnect while no writes are
                    // in flight, which WriteFile would never observe; probe so
                    // client_connected_ stays truthful and stale media is flushed.
                    DWORD dummy = 0;
                    if (PeekNamedPipe(handle, nullptr, 0, nullptr, &dummy, nullptr) == FALSE &&
                        GetLastError() == ERROR_BROKEN_PIPE) {
                        pipe_broken = true;
                        break;
                    }
                    continue;
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
                    break;
                }
            }
        }
        if (pipe_broken) {
            // The decoder pipe disappeared while media was live. Flush stale
            // media and reject one TS group so SrtListener tears down the peer;
            // the sender then reconnects with a fresh keyframe and transport
            // tables instead of feeding a new decoder mid-GOP.
            client_connected_.store(false);
            queue_.clear();
            restart_transport_.store(true);
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
