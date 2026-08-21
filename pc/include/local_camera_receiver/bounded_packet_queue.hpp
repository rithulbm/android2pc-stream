#pragma once

#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <mutex>
#include <optional>
#include <span>
#include <vector>

namespace lcr {

class BoundedPacketQueue final {
public:
    static constexpr std::size_t kMaximumPacketBytes = 1316;
    static constexpr std::size_t kMaximumPackets = 4096;
    static constexpr std::size_t kMaximumQueuedBytes = 8U * 1024U * 1024U;

    [[nodiscard]] bool push(std::span<const std::uint8_t> packet);
    [[nodiscard]] std::optional<std::vector<std::uint8_t>> wait_pop();
    [[nodiscard]] std::optional<std::vector<std::uint8_t>> wait_pop_for(std::chrono::milliseconds timeout);
    void clear() noexcept;
    void cancel() noexcept;
    void reset() noexcept;
    [[nodiscard]] std::size_t size() const noexcept;
    [[nodiscard]] std::size_t bytes() const noexcept;

private:
    mutable std::mutex mutex_;
    std::condition_variable condition_;
    std::deque<std::vector<std::uint8_t>> packets_;
    std::size_t bytes_ = 0;
    bool cancelled_ = false;
};

} // namespace lcr

