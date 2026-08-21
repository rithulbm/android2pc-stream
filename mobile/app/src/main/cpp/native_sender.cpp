#include "native_sender.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <srt.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cstring>
#include <limits>
#include <string>
#include <utility>

namespace local_sender {
namespace {

constexpr int kInvalidSocket = -1;
constexpr int kAesGcmMode = 2;
constexpr int kPayloadBytes = 1316;
constexpr int kConnectTimeoutMilliseconds = 3'000;
constexpr int kSendTimeoutMilliseconds = 1'500;
constexpr int kPeerIdleTimeoutMilliseconds = 5'000;
constexpr int kVerifyTimeoutMilliseconds = 4'000;
constexpr int kWorkerPollMilliseconds = 100;
constexpr std::array<int, 10> kReconnectDelaySeconds{1, 2, 4, 8, 16, 30, 30, 30, 30, 30};

template <typename T>
bool set_option(int socket, SRT_SOCKOPT option, const T& value) {
    return srt_setsockopt(socket, 0, option, &value, static_cast<int>(sizeof(T))) != SRT_ERROR;
}

std::string encode_base64_url(const std::array<std::uint8_t, 32>& bytes) {
    static constexpr char alphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string output;
    output.reserve(43);
    std::uint32_t accumulator = 0;
    int bits = 0;
    for (const std::uint8_t byte : bytes) {
        accumulator = (accumulator << 8U) | byte;
        bits += 8;
        while (bits >= 6) {
            bits -= 6;
            const auto index = static_cast<std::size_t>((accumulator >> bits) & 0x3FU);
            output.push_back(alphabet[index]);
        }
    }
    if (bits > 0) {
        const auto index = static_cast<std::size_t>((accumulator << (6 - bits)) & 0x3FU);
        output.push_back(alphabet[index]);
    }
    return output;
}

bool is_private_ipv4(const std::string& host, in_addr& address) {
    if (inet_pton(AF_INET, host.c_str(), &address) != 1) {
        return false;
    }
    std::array<char, INET_ADDRSTRLEN> canonical{};
    if (inet_ntop(AF_INET, &address, canonical.data(), canonical.size()) == nullptr || host != canonical.data()) {
        return false;
    }
    const std::uint32_t value = ntohl(address.s_addr);
    const bool private_ten = (value & 0xFF00'0000U) == 0x0A00'0000U;
    const bool private_172 = (value & 0xFFF0'0000U) == 0xAC10'0000U;
    const bool private_192 = (value & 0xFFFF'0000U) == 0xC0A8'0000U;
    const bool link_local = (value & 0xFFFF'0000U) == 0xA9FE'0000U;
    return private_ten || private_172 || private_192 || link_local;
}

bool is_authentication_error(SenderError error) {
    return error == SenderError::kEncryptionRejected;
}

}  // namespace

NativeSender::NativeSender(SenderConfig config)
    : config_(std::move(config)),
      muxer_(config_.video_codec, config_.audio_enabled, config_.audio_sample_rate, config_.audio_channels) {}

NativeSender::~NativeSender() {
    stop();
    std::fill(config_.secret.begin(), config_.secret.end(), 0);
}

bool NativeSender::validate_config(const SenderConfig& config) {
    in_addr address{};
    if (!is_private_ipv4(config.host, address) || config.port < 1024U) {
        return false;
    }
    if (config.device_name.empty() || config.device_name.size() > 48U ||
        !std::all_of(config.device_name.begin(), config.device_name.end(), [](const unsigned char character) {
            return (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z') ||
                   (character >= '0' && character <= '9') || character == ' ' || character == '.' ||
                   character == '_' || character == '-';
        })) {
        return false;
    }
    const bool secret_is_empty = std::all_of(config.secret.begin(), config.secret.end(), [](std::uint8_t byte) {
        return byte == 0U;
    });
    if (secret_is_empty || config.latency_milliseconds < 60 || config.latency_milliseconds > 2'000) {
        return false;
    }
    if (config.video_codec != VideoCodec::kHevc && config.video_codec != VideoCodec::kAvc) {
        return false;
    }
    if (!config.audio_enabled) {
        return true;
    }
    return config.audio_sample_rate == 48'000 && config.audio_channels == 1;
}

bool NativeSender::start() {
    std::lock_guard lock(lifecycle_mutex_);
    if (worker_.joinable()) {
        return !stopping_.load();
    }
    if (!validate_config(config_)) {
        set_failure(SenderStatus::kFailed, SenderError::kInvalidConfiguration);
        return false;
    }
    stopping_.store(false);
    error_.store(SenderError::kNone);
    status_.store(SenderStatus::kConnecting);
    queue_.reset();
    worker_ = std::thread(&NativeSender::worker_main, this);
    return true;
}

void NativeSender::stop() {
    std::unique_lock lock(lifecycle_mutex_);
    stopping_.store(true);
    queue_.cancel();
    reconnect_wait_.notify_all();
    close_socket();
    std::thread worker = std::move(worker_);
    lock.unlock();
    if (worker.joinable()) {
        worker.join();
    }
    status_.store(SenderStatus::kStopped);
    error_.store(SenderError::kNone);
    std::fill(config_.secret.begin(), config_.secret.end(), 0);
}

bool NativeSender::write_video(
    std::span<const std::uint8_t> access_unit,
    std::int64_t presentation_time_microseconds,
    bool key_frame) {
    const SenderStatus current_status = status_.load();
    if (stopping_.load() || current_status == SenderStatus::kStopped || current_status == SenderStatus::kFailed ||
        current_status == SenderStatus::kAuthenticationFailed || presentation_time_microseconds < 0 ||
        access_unit.empty()) {
        return false;
    }
    if (current_status == SenderStatus::kConnecting || current_status == SenderStatus::kReconnecting) {
        // The encoder may be running before SRT finishes its handshake. Drop those
        // frames without backpressuring the encoder; a fresh IDR is requested once
        // the secure transport transitions to kNeedsKeyFrame.
        return true;
    }

    std::lock_guard lock(mux_mutex_);
    if (!video_started_) {
        if (!key_frame) {
            status_.store(SenderStatus::kNeedsKeyFrame);
            return false;
        }
        muxer_.reset(presentation_time_microseconds);
    }

    // Mux into a copy first. Continuity counters are committed only when the complete
    // access unit fits in the bounded queue; partial PES insertion is never allowed.
    MpegTsMuxer staged = muxer_;
    PacketBatch packets;
    if (!staged.write_video(access_unit, presentation_time_microseconds, key_frame, packets)) {
        error_.store(SenderError::kMux);
        return false;
    }
    if (!queue_.push_batch(std::move(packets))) {
        error_.store(SenderError::kBackpressure);
        return false;
    }
    muxer_ = staged;
    video_started_ = true;
    return true;
}

bool NativeSender::write_audio(
    std::span<const std::uint8_t> access_unit,
    std::int64_t presentation_time_microseconds) {
    const SenderStatus current_status = status_.load();
    if (stopping_.load() || current_status == SenderStatus::kStopped || current_status == SenderStatus::kFailed ||
        current_status == SenderStatus::kAuthenticationFailed || presentation_time_microseconds < 0 ||
        access_unit.empty()) {
        return false;
    }
    if (current_status != SenderStatus::kConnected) {
        return true;
    }

    std::lock_guard lock(mux_mutex_);
    if (!video_started_) {
        return true;
    }
    MpegTsMuxer staged = muxer_;
    PacketBatch packets;
    if (!staged.write_audio(access_unit, presentation_time_microseconds, packets)) {
        error_.store(SenderError::kMux);
        return false;
    }
    if (!queue_.push_batch(std::move(packets))) {
        error_.store(SenderError::kBackpressure);
        return false;
    }
    muxer_ = staged;
    return true;
}

SenderStatus NativeSender::status() const {
    return status_.load();
}

SenderError NativeSender::error() const {
    return error_.load();
}

int NativeSender::queue_percent() const {
    const std::size_t bytes = queue_.size_bytes();
    const std::size_t percent = std::min<std::size_t>(100U, (bytes * 100U) / kMaximumQueueBytes);
    return static_cast<int>(percent);
}

void NativeSender::worker_main() {
    std::size_t consecutive_failures = 0;
    bool has_streamed = false;
    while (!stopping_.load()) {
        status_.store(has_streamed ? SenderStatus::kReconnecting : SenderStatus::kConnecting);
        const int connected_socket = connect_socket();
        if (connected_socket == kInvalidSocket) {
            reset_mux_after_disconnect();
            if (is_authentication_error(error_.load())) {
                set_failure(SenderStatus::kAuthenticationFailed, SenderError::kEncryptionRejected);
                break;
            }
            if (consecutive_failures >= kReconnectDelaySeconds.size()) {
                set_failure(SenderStatus::kFailed, SenderError::kReconnectExhausted);
                break;
            }
            if (!wait_for_reconnect(consecutive_failures)) {
                break;
            }
            ++consecutive_failures;
            continue;
        }

        socket_.store(connected_socket);
        error_.store(SenderError::kNone);
        has_streamed = true;
        // First make the queue/mux epoch clean while producers still see Connecting or
        // Reconnecting and therefore drop frames. Only then expose the keyframe demand.
        reset_mux_after_disconnect();
        status_.store(SenderStatus::kNeedsKeyFrame);

        Packet packet;
        bool send_failed = false;
        bool sent_media = false;
        while (!stopping_.load()) {
            if (!queue_.wait_pop_for(packet, std::chrono::milliseconds(kWorkerPollMilliseconds))) {
                if (stopping_.load()) break;
                int state = SRTS_INIT;
                int state_size = static_cast<int>(sizeof(state));
                if (srt_getsockopt(connected_socket, 0, SRTO_STATE, &state, &state_size) != SRT_ERROR &&
                    (state == SRTS_BROKEN || state == SRTS_CLOSING || state == SRTS_CLOSED)) {
                    send_failed = true;
                    error_.store(SenderError::kSend);
                    break;
                }
                continue;
            }
            if (packet.empty() || packet.size() > static_cast<std::size_t>(kPayloadBytes)) {
                continue;
            }
            SRT_MSGCTRL control = srt_msgctrl_default;
            const int sent = srt_sendmsg2(
                connected_socket,
                reinterpret_cast<const char*>(packet.data()),
                static_cast<int>(packet.size()),
                &control);
            if (sent != static_cast<int>(packet.size())) {
                send_failed = true;
                error_.store(SenderError::kSend);
                break;
            }
            sent_media = true;
            if (status_.load() == SenderStatus::kNeedsKeyFrame) {
                status_.store(SenderStatus::kConnected);
            }
        }
        close_socket();

        if (stopping_.load()) {
            break;
        }
        reset_mux_after_disconnect();
        if (sent_media) {
            consecutive_failures = 0;
        }
        if (!send_failed) {
            if (!wait_for_reconnect(consecutive_failures)) break;
            ++consecutive_failures;
            continue;
        }
        if (consecutive_failures >= kReconnectDelaySeconds.size()) {
            set_failure(SenderStatus::kFailed, SenderError::kReconnectExhausted);
            break;
        }
        if (!wait_for_reconnect(consecutive_failures)) {
            break;
        }
        ++consecutive_failures;
    }
}

int NativeSender::connect_socket() {
    const int socket = srt_create_socket();
    if (socket == SRT_INVALID_SOCK) {
        error_.store(SenderError::kConnection);
        return kInvalidSocket;
    }

    const int live = SRTT_LIVE;
    const bool enabled = true;
    const bool synchronous = true;
    const int key_length = 32;
    const int crypto_mode = kAesGcmMode;
    const int minimum_version = SRT_MAKE_VERSION(1, 5, 6);
    const std::string stream_id = "lcr/1/" + config_.device_name;
    std::string passphrase = encode_base64_url(config_.secret);
    int adaptive_send_timeout = std::max(kSendTimeoutMilliseconds, config_.latency_milliseconds * 3 / 2);
    const bool base_configured =
        set_option(socket, SRTO_TRANSTYPE, live) && set_option(socket, SRTO_SENDER, enabled) &&
        set_option(socket, SRTO_TSBPDMODE, enabled) && set_option(socket, SRTO_MESSAGEAPI, enabled) &&
        set_option(socket, SRTO_SNDSYN, synchronous) && set_option(socket, SRTO_PAYLOADSIZE, kPayloadBytes) &&
        set_option(socket, SRTO_LATENCY, config_.latency_milliseconds) &&
        set_option(socket, SRTO_PEERLATENCY, config_.latency_milliseconds) &&
        set_option(socket, SRTO_CONNTIMEO, kConnectTimeoutMilliseconds) &&
        set_option(socket, SRTO_SNDTIMEO, adaptive_send_timeout) &&
        set_option(socket, SRTO_PEERIDLETIMEO, kPeerIdleTimeoutMilliseconds) &&
        set_option(socket, SRTO_MINVERSION, minimum_version) && set_option(socket, SRTO_PBKEYLEN, key_length) &&
        set_option(socket, SRTO_ENFORCEDENCRYPTION, enabled) && set_option(socket, SRTO_CRYPTOMODE, crypto_mode);
    const bool stream_id_configured = base_configured &&
        srt_setsockopt(
            socket,
            0,
            SRTO_STREAMID,
            stream_id.data(),
            static_cast<int>(stream_id.size())) != SRT_ERROR;
    const bool passphrase_configured = stream_id_configured &&
        srt_setsockopt(
            socket,
            0,
            SRTO_PASSPHRASE,
            passphrase.data(),
            static_cast<int>(passphrase.size())) != SRT_ERROR;
    std::fill(passphrase.begin(), passphrase.end(), '\0');
    const bool configured = base_configured && stream_id_configured && passphrase_configured;
    if (!configured) {
        srt_close(socket);
        error_.store(SenderError::kSocketConfiguration);
        return kInvalidSocket;
    }

    sockaddr_in peer{};
    peer.sin_family = AF_INET;
    peer.sin_port = htons(config_.port);
    if (inet_pton(AF_INET, config_.host.c_str(), &peer.sin_addr) != 1 ||
        srt_connect(socket, reinterpret_cast<const sockaddr*>(&peer), static_cast<int>(sizeof(peer))) == SRT_ERROR) {
        srt_close(socket);
        error_.store(SenderError::kConnection);
        return kInvalidSocket;
    }
    if (!verify_encryption(socket)) {
        srt_close(socket);
        error_.store(SenderError::kEncryptionRejected);
        return kInvalidSocket;
    }
    return socket;
}

bool NativeSender::verify_encryption(int socket) const {
    int crypto_mode = 0;
    int crypto_mode_size = static_cast<int>(sizeof(crypto_mode));
    if (srt_getsockopt(socket, 0, SRTO_CRYPTOMODE, &crypto_mode, &crypto_mode_size) == SRT_ERROR ||
        crypto_mode != kAesGcmMode) {
        return false;
    }
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(kVerifyTimeoutMilliseconds);
    while (!stopping_.load() && std::chrono::steady_clock::now() < deadline) {
        int sender_key_state = SRT_KM_S_UNSECURED;
        int receiver_key_state = SRT_KM_S_UNSECURED;
        int sender_size = static_cast<int>(sizeof(sender_key_state));
        int receiver_size = static_cast<int>(sizeof(receiver_key_state));
        const bool read_states =
            srt_getsockopt(socket, 0, SRTO_SNDKMSTATE, &sender_key_state, &sender_size) != SRT_ERROR &&
            srt_getsockopt(socket, 0, SRTO_RCVKMSTATE, &receiver_key_state, &receiver_size) != SRT_ERROR;
        if (!read_states) {
            return false;
        }
        if (sender_key_state == SRT_KM_S_SECURED && receiver_key_state == SRT_KM_S_SECURED) {
            return true;
        }
        if (sender_key_state >= SRT_KM_S_NOSECRET || receiver_key_state >= SRT_KM_S_NOSECRET) {
            return false;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    return false;
}

bool NativeSender::wait_for_reconnect(std::size_t attempt) {
    const int delay = kReconnectDelaySeconds[attempt];
    std::unique_lock lock(reconnect_mutex_);
    return !reconnect_wait_.wait_for(lock, std::chrono::seconds(delay), [this] { return stopping_.load(); });
}

void NativeSender::close_socket() {
    const int socket = socket_.exchange(kInvalidSocket);
    if (socket != kInvalidSocket) {
        srt_close(socket);
    }
}

void NativeSender::set_failure(SenderStatus status, SenderError error) {
    status_.store(status);
    error_.store(error);
}

void NativeSender::reset_mux_after_disconnect() {
    std::lock_guard lock(mux_mutex_);
    queue_.clear();
    video_started_ = false;
}

}  // namespace local_sender
