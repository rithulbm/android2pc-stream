#ifndef LOCAL_CAMERA_SENDER_NATIVE_SENDER_H
#define LOCAL_CAMERA_SENDER_NATIVE_SENDER_H

#include "bounded_packet_queue.h"
#include "mpeg_ts_muxer.h"

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <span>
#include <string>
#include <thread>

namespace local_sender {

struct SenderConfig final {
    std::string host;
    std::string device_name;
    std::uint16_t port = 0;
    std::array<std::uint8_t, 32> secret{};
    int latency_milliseconds = 0;
    VideoCodec video_codec = VideoCodec::kHevc;
    bool audio_enabled = false;
    int audio_sample_rate = 48'000;
    int audio_channels = 1;
};

enum class SenderStatus : int {
    kStopped = 0,
    kConnecting = 1,
    kConnected = 2,
    kReconnecting = 3,
    kAuthenticationFailed = 4,
    kFailed = 5,
    kNeedsKeyFrame = 6,
};

enum class SenderError : int {
    kNone = 0,
    kInvalidConfiguration = 1,
    kSocketConfiguration = 2,
    kConnection = 3,
    kEncryptionRejected = 4,
    kSend = 5,
    kReconnectExhausted = 6,
    kMux = 7,
    kBackpressure = 8,
};

class NativeSender final {
  public:
    explicit NativeSender(SenderConfig config);
    ~NativeSender();

    NativeSender(const NativeSender&) = delete;
    NativeSender& operator=(const NativeSender&) = delete;

    [[nodiscard]] bool start();
    void stop();
    [[nodiscard]] bool write_video(
        std::span<const std::uint8_t> access_unit,
        std::int64_t presentation_time_microseconds,
        bool key_frame);
    [[nodiscard]] bool write_audio(
        std::span<const std::uint8_t> access_unit,
        std::int64_t presentation_time_microseconds);

    [[nodiscard]] SenderStatus status() const;
    [[nodiscard]] SenderError error() const;
    [[nodiscard]] int queue_percent() const;

    [[nodiscard]] static bool validate_config(const SenderConfig& config);

  private:
    static constexpr std::size_t kMaximumQueuePackets = 16'384;
    static constexpr std::size_t kMaximumQueueBytes = 16U * 1024U * 1024U;

    void worker_main();
    [[nodiscard]] int connect_socket();
    [[nodiscard]] bool verify_encryption(int socket) const;
    [[nodiscard]] bool wait_for_reconnect(std::size_t attempt);
    void close_socket();
    void set_failure(SenderStatus status, SenderError error);
    void reset_mux_after_disconnect();

    SenderConfig config_;
    BoundedPacketQueue queue_{kMaximumQueuePackets, kMaximumQueueBytes};
    mutable std::mutex mux_mutex_;
    MpegTsMuxer muxer_;
    bool video_started_ = false;
    std::atomic<SenderStatus> status_{SenderStatus::kStopped};
    std::atomic<SenderError> error_{SenderError::kNone};
    std::atomic<bool> stopping_{false};
    std::atomic<int> socket_{-1};
    std::thread worker_;
    mutable std::mutex lifecycle_mutex_;
    std::mutex reconnect_mutex_;
    std::condition_variable reconnect_wait_;
};

}  // namespace local_sender

#endif
