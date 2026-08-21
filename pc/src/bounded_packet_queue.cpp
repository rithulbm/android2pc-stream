#include "local_camera_receiver/bounded_packet_queue.hpp"

#include <algorithm>

namespace lcr {

bool BoundedPacketQueue::push(const std::span<const std::uint8_t> packet)
{
    if (packet.empty() || packet.size() > kMaximumPacketBytes) {
        return false;
    }
    std::scoped_lock lock(mutex_);
    if (cancelled_ || packets_.size() >= kMaximumPackets || bytes_ + packet.size() > kMaximumQueuedBytes) {
        return false;
    }
    packets_.emplace_back(packet.begin(), packet.end());
    bytes_ += packet.size();
    condition_.notify_one();
    return true;
}

std::optional<std::vector<std::uint8_t>> BoundedPacketQueue::wait_pop()
{
    std::unique_lock lock(mutex_);
    condition_.wait(lock, [this] { return cancelled_ || !packets_.empty(); });
    if (packets_.empty()) {
        return std::nullopt;
    }
    auto packet = std::move(packets_.front());
    packets_.pop_front();
    bytes_ -= packet.size();
    return packet;
}

std::optional<std::vector<std::uint8_t>> BoundedPacketQueue::wait_pop_for(const std::chrono::milliseconds timeout)
{
    std::unique_lock lock(mutex_);
    if (!condition_.wait_for(lock, timeout, [this] { return cancelled_ || !packets_.empty(); })) {
        return std::nullopt;
    }
    if (packets_.empty()) {
        return std::nullopt;
    }
    auto packet = std::move(packets_.front());
    packets_.pop_front();
    bytes_ -= packet.size();
    return packet;
}

void BoundedPacketQueue::clear() noexcept
{
    std::scoped_lock lock(mutex_);
    for (auto &packet : packets_) {
        std::fill(packet.begin(), packet.end(), std::uint8_t{0});
    }
    packets_.clear();
    bytes_ = 0;
}

void BoundedPacketQueue::cancel() noexcept
{
    std::scoped_lock lock(mutex_);
    cancelled_ = true;
    condition_.notify_all();
}

void BoundedPacketQueue::reset() noexcept
{
    std::scoped_lock lock(mutex_);
    cancelled_ = false;
}

std::size_t BoundedPacketQueue::size() const noexcept
{
    std::scoped_lock lock(mutex_);
    return packets_.size();
}

std::size_t BoundedPacketQueue::bytes() const noexcept
{
    std::scoped_lock lock(mutex_);
    return bytes_;
}

} // namespace lcr
