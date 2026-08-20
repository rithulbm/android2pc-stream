#pragma once

#include "local_camera_receiver/named_pipe_sink.hpp"
#include "local_camera_receiver/receiver_config.hpp"

#include <atomic>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

namespace lcr {

enum class ReceiverState {
    stopped,
    waiting_for_pairing,
    listening,
    authenticating,
    streaming,
    reconnecting,
    failed,
};

enum class ReceiverError {
    none,
    invalid_config,
    startup,
    bind,
    listen,
    authentication,
    encryption_downgrade,
    backpressure,
    transport,
};

struct ReceiverStatus final {
    ReceiverState state = ReceiverState::stopped;
    ReceiverError error = ReceiverError::none;
    std::uint64_t accepted_packets = 0;
    std::uint64_t dropped_packets = 0;
    std::string connected_device;
    std::string peer_address;
};

class SrtListener final {
public:
    using StatusCallback = std::function<void(const ReceiverStatus &)>;
    using MediaReadyCallback = std::function<bool()>;

    SrtListener(NamedPipeSink &sink, StatusCallback callback, MediaReadyCallback media_ready = {});
    ~SrtListener();

    SrtListener(const SrtListener &) = delete;
    SrtListener &operator=(const SrtListener &) = delete;

    [[nodiscard]] bool start(const ReceiverConfig &config);
    void stop() noexcept;
    [[nodiscard]] ReceiverStatus status() const noexcept;

private:
    void run(ReceiverConfig config) noexcept;
    void publish(ReceiverState state, ReceiverError error = ReceiverError::none) noexcept;

    NamedPipeSink &sink_;
    StatusCallback callback_;
    MediaReadyCallback media_ready_;
    mutable std::mutex status_mutex_;
    ReceiverStatus status_{};
    std::atomic<bool> running_{false};
    std::thread worker_;
    std::atomic<std::int64_t> listener_socket_{-1};
    std::atomic<std::int64_t> peer_socket_{-1};
};

} // namespace lcr
