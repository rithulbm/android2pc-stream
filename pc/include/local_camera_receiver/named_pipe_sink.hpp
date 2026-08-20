#pragma once

#include "local_camera_receiver/bounded_packet_queue.hpp"

#include <atomic>
#include <memory>
#include <string>
#include <thread>

namespace lcr {

class NamedPipeSink final {
public:
    explicit NamedPipeSink(std::wstring pipe_name);
    ~NamedPipeSink();

    NamedPipeSink(const NamedPipeSink &) = delete;
    NamedPipeSink &operator=(const NamedPipeSink &) = delete;

    [[nodiscard]] bool start();
    void stop() noexcept;
    [[nodiscard]] bool enqueue(std::span<const std::uint8_t> packet);
    [[nodiscard]] bool client_connected() const noexcept { return client_connected_.load(); }
    [[nodiscard]] const std::wstring &pipe_name() const noexcept;

private:
    void run() noexcept;

    std::wstring pipe_name_;
    BoundedPacketQueue queue_;
    std::atomic<bool> running_{false};
    std::atomic<bool> restart_transport_{false};
    std::atomic<bool> client_connected_{false};
    std::thread worker_;
    std::atomic<void *> pipe_{nullptr};
};

} // namespace lcr
