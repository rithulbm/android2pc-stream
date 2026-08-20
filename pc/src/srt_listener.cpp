#include "local_camera_receiver/srt_listener.hpp"
#include "local_camera_receiver/device_stream_id.hpp"

#include <srt.h>
#include <ws2tcpip.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <mutex>
#include <optional>
#include <string>

namespace lcr {
namespace {

constexpr int kAesGcmMode = 2;
constexpr int kPayloadBytes = 1316;
constexpr int kPeerIdleTimeoutMs = 5000;
constexpr int kReceiveTimeoutMs = 1000;

std::mutex runtime_mutex;
unsigned runtime_users = 0;

bool acquire_runtime() noexcept
{
    std::scoped_lock lock(runtime_mutex);
    if (runtime_users == 0 && srt_startup() == SRT_ERROR) {
        return false;
    }
    ++runtime_users;
    return true;
}

void release_runtime() noexcept
{
    std::scoped_lock lock(runtime_mutex);
    if (runtime_users > 0 && --runtime_users == 0) {
        srt_cleanup();
    }
}

template <typename T>
bool set_option(const SRTSOCKET socket, const SRT_SOCKOPT option, const T &value) noexcept
{
    return srt_setsockopt(socket, 0, option, &value, static_cast<int>(sizeof(value))) != SRT_ERROR;
}

std::string base64url(const std::array<std::uint8_t, kSecretBytes> &bytes)
{
    static constexpr char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string output;
    output.reserve(43);
    std::uint32_t accumulator = 0;
    unsigned bits = 0;
    for (const auto byte : bytes) {
        accumulator = (accumulator << 8U) | byte;
        bits += 8;
        while (bits >= 6) {
            bits -= 6;
            output.push_back(alphabet[(accumulator >> bits) & 0x3fU]);
        }
    }
    if (bits > 0) {
        output.push_back(alphabet[(accumulator << (6U - bits)) & 0x3fU]);
    }
    return output;
}

bool verify_encryption(const SRTSOCKET socket, const std::atomic<bool> &running) noexcept
{
    int crypto_mode = 0;
    int mode_size = static_cast<int>(sizeof(crypto_mode));
    if (srt_getsockopt(socket, 0, SRTO_CRYPTOMODE, &crypto_mode, &mode_size) == SRT_ERROR ||
        crypto_mode != kAesGcmMode) {
        return false;
    }
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
    while (running.load() && std::chrono::steady_clock::now() < deadline) {
        int sender_state = SRT_KM_S_UNSECURED;
        int receiver_state = SRT_KM_S_UNSECURED;
        int sender_size = static_cast<int>(sizeof(sender_state));
        int receiver_size = static_cast<int>(sizeof(receiver_state));
        if (srt_getsockopt(socket, 0, SRTO_SNDKMSTATE, &sender_state, &sender_size) == SRT_ERROR ||
            srt_getsockopt(socket, 0, SRTO_RCVKMSTATE, &receiver_state, &receiver_size) == SRT_ERROR) {
            return false;
        }
        if (sender_state == SRT_KM_S_SECURED && receiver_state == SRT_KM_S_SECURED) {
            return true;
        }
        if (sender_state >= SRT_KM_S_NOSECRET || receiver_state >= SRT_KM_S_NOSECRET) {
            return false;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    return false;
}

std::optional<std::string> authenticated_device_label(const SRTSOCKET socket) noexcept
{
    std::array<char, kDeviceStreamIdPrefix.size() + kMaximumDeviceLabelBytes + 1U> stream_id{};
    int length = static_cast<int>(stream_id.size());
    if (srt_getsockopt(socket, 0, SRTO_STREAMID, stream_id.data(), &length) == SRT_ERROR ||
        length <= 0 || length >= static_cast<int>(stream_id.size())) {
        return std::nullopt;
    }
    std::size_t text_length = static_cast<std::size_t>(length);
    if (stream_id[text_length - 1U] == '\0') --text_length;
    return parse_device_stream_id(std::string_view(stream_id.data(), text_length));
}

bool valid_ts_group(const std::span<const std::uint8_t> bytes) noexcept
{
    if (bytes.empty() || bytes.size() > kPayloadBytes || bytes.size() % 188U != 0) {
        return false;
    }
    for (std::size_t offset = 0; offset < bytes.size(); offset += 188U) {
        if (bytes[offset] != 0x47U) {
            return false;
        }
    }
    return true;
}

void close_socket(std::atomic<std::int64_t> &slot) noexcept
{
    const auto value = slot.exchange(SRT_INVALID_SOCK);
    if (value != SRT_INVALID_SOCK) {
        srt_close(static_cast<SRTSOCKET>(value));
    }
}

} // namespace

SrtListener::SrtListener(NamedPipeSink &sink, StatusCallback callback)
    : sink_(sink), callback_(std::move(callback)) {}

SrtListener::~SrtListener()
{
    stop();
}

bool SrtListener::start(const ReceiverConfig &config)
{
    if (validate_config(config, now_epoch_seconds()) != ConfigError::none) {
        publish(ReceiverState::failed, ReceiverError::invalid_config);
        return false;
    }
    bool expected = false;
    if (!running_.compare_exchange_strong(expected, true)) {
        return false;
    }
    if (worker_.joinable()) {
        worker_.join();
    }
    worker_ = std::thread(&SrtListener::run, this, config);
    return true;
}

void SrtListener::stop() noexcept
{
    running_.store(false);
    close_socket(peer_socket_);
    close_socket(listener_socket_);
    if (worker_.joinable()) {
        worker_.join();
    }
    publish(ReceiverState::stopped);
}

ReceiverStatus SrtListener::status() const noexcept
{
    std::scoped_lock lock(status_mutex_);
    return status_;
}

void SrtListener::publish(const ReceiverState state, const ReceiverError error) noexcept
{
    ReceiverStatus snapshot{};
    {
        std::scoped_lock lock(status_mutex_);
        status_.state = state;
        status_.error = error;
        if (state == ReceiverState::stopped || state == ReceiverState::listening || state == ReceiverState::failed) {
            status_.connected_device.clear();
            status_.peer_address.clear();
        }
        snapshot = status_;
    }
    if (callback_) {
        callback_(snapshot);
    }
}

void SrtListener::run(ReceiverConfig config) noexcept
{
    if (!acquire_runtime()) {
        publish(ReceiverState::failed, ReceiverError::startup);
        running_.store(false);
        config.clear_secret();
        return;
    }

    const SRTSOCKET listener = srt_create_socket();
    listener_socket_.store(listener);
    const int live = SRTT_LIVE;
    const bool enabled = true;
    const bool synchronous = true;
    const int key_length = 32;
    const int minimum_version = SRT_MAKE_VERSION(1, 5, 6);
    const int crypto_mode = kAesGcmMode;
    std::string passphrase = base64url(config.secret);
    const bool configured = listener != SRT_INVALID_SOCK &&
        set_option(listener, SRTO_TRANSTYPE, live) &&
        set_option(listener, SRTO_TSBPDMODE, enabled) &&
        set_option(listener, SRTO_MESSAGEAPI, enabled) &&
        set_option(listener, SRTO_RCVSYN, synchronous) &&
        set_option(listener, SRTO_PAYLOADSIZE, kPayloadBytes) &&
        set_option(listener, SRTO_LATENCY, static_cast<int>(config.latency_ms)) &&
        set_option(listener, SRTO_PEERLATENCY, static_cast<int>(config.latency_ms)) &&
        set_option(listener, SRTO_RCVTIMEO, kReceiveTimeoutMs) &&
        set_option(listener, SRTO_PEERIDLETIMEO, kPeerIdleTimeoutMs) &&
        set_option(listener, SRTO_MINVERSION, minimum_version) &&
        set_option(listener, SRTO_PBKEYLEN, key_length) &&
        set_option(listener, SRTO_ENFORCEDENCRYPTION, enabled) &&
        set_option(listener, SRTO_CRYPTOMODE, crypto_mode) &&
        srt_setsockopt(listener, 0, SRTO_PASSPHRASE, passphrase.data(), static_cast<int>(passphrase.size())) != SRT_ERROR;
    std::fill(passphrase.begin(), passphrase.end(), '\0');
    config.clear_secret();
    if (!configured) {
        close_socket(listener_socket_);
        publish(ReceiverState::failed, ReceiverError::startup);
        running_.store(false);
        release_runtime();
        return;
    }

    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(config.port);
    if (InetPtonA(AF_INET, config.host.c_str(), &address.sin_addr) != 1 ||
        srt_bind(listener, reinterpret_cast<const sockaddr *>(&address), static_cast<int>(sizeof(address))) == SRT_ERROR) {
        close_socket(listener_socket_);
        publish(ReceiverState::failed, ReceiverError::bind);
        running_.store(false);
        release_runtime();
        return;
    }
    if (srt_listen(listener, 1) == SRT_ERROR) {
        close_socket(listener_socket_);
        publish(ReceiverState::failed, ReceiverError::listen);
        running_.store(false);
        release_runtime();
        return;
    }
    publish(ReceiverState::listening);

    bool previously_streamed = false;
    while (running_.load()) {
        sockaddr_in peer_address{};
        int peer_length = static_cast<int>(sizeof(peer_address));
        const SRTSOCKET peer = srt_accept(listener, reinterpret_cast<sockaddr *>(&peer_address), &peer_length);
        if (!running_.load()) {
            if (peer != SRT_INVALID_SOCK) srt_close(peer);
            break;
        }
        if (peer == SRT_INVALID_SOCK) {
            publish(previously_streamed ? ReceiverState::reconnecting : ReceiverState::listening);
            continue;
        }
        peer_socket_.store(peer);
        // These are post-connect options. Set them explicitly on the accepted socket so
        // receive blocking behavior never depends on listener-option inheritance rules.
        if (!set_option(peer, SRTO_RCVSYN, synchronous) || !set_option(peer, SRTO_RCVTIMEO, kReceiveTimeoutMs)) {
            close_socket(peer_socket_);
            publish(previously_streamed ? ReceiverState::reconnecting : ReceiverState::listening,
                    ReceiverError::transport);
            continue;
        }
        std::array<char, INET_ADDRSTRLEN> peer_host{};
        if (peer_address.sin_family != AF_INET ||
            InetNtopA(AF_INET, &peer_address.sin_addr, peer_host.data(), peer_host.size()) == nullptr ||
            !is_canonical_private_ipv4(peer_host.data()) ||
            now_epoch_seconds() >= config.credential_expires_epoch_seconds) {
            close_socket(peer_socket_);
            publish(ReceiverState::listening, ReceiverError::authentication);
            continue;
        }
        publish(ReceiverState::authenticating);
        if (!verify_encryption(peer, running_)) {
            close_socket(peer_socket_);
            publish(ReceiverState::listening, ReceiverError::encryption_downgrade);
            continue;
        }
        const auto device_label = authenticated_device_label(peer);
        if (!device_label) {
            close_socket(peer_socket_);
            publish(ReceiverState::listening, ReceiverError::authentication);
            continue;
        }
        {
            std::scoped_lock lock(status_mutex_);
            status_.connected_device = *device_label;
            status_.peer_address = peer_host.data();
        }
        std::array<std::uint8_t, kPayloadBytes> packet{};
        bool media_started = false;
        while (running_.load()) {
            if (now_epoch_seconds() >= config.credential_expires_epoch_seconds) {
                publish(ReceiverState::failed, ReceiverError::invalid_config);
                running_.store(false);
                break;
            }
            SRT_MSGCTRL control = srt_msgctrl_default;
            const int received = srt_recvmsg2(
                peer,
                reinterpret_cast<char *>(packet.data()),
                static_cast<int>(packet.size()),
                &control);
            if (received == SRT_ERROR) {
                if (srt_getsockstate(peer) == SRTS_CONNECTED) {
                    continue;
                }
                break;
            }
            const auto bytes = std::span<const std::uint8_t>(packet.data(), static_cast<std::size_t>(received));
            if (!valid_ts_group(bytes)) {
                publish(ReceiverState::reconnecting, ReceiverError::transport);
                break;
            }
            if (!sink_.enqueue(bytes)) {
                {
                    std::scoped_lock lock(status_mutex_);
                    ++status_.dropped_packets;
                }
                publish(ReceiverState::reconnecting, ReceiverError::backpressure);
                break;
            }
            {
                std::scoped_lock lock(status_mutex_);
                ++status_.accepted_packets;
            }
            if (!media_started && sink_.client_connected()) {
                // Streaming means the authenticated sender is producing valid TS and
                // OBS's private FFmpeg child is actually attached to the handoff pipe.
                media_started = true;
                previously_streamed = true;
                publish(ReceiverState::streaming);
            }
        }
        SecureZeroMemory(packet.data(), packet.size());
        close_socket(peer_socket_);
        if (running_.load()) publish(previously_streamed ? ReceiverState::reconnecting : ReceiverState::listening);
    }
    close_socket(peer_socket_);
    close_socket(listener_socket_);
    release_runtime();
}

} // namespace lcr
