#include "bounded_packet_queue.h"

#include <algorithm>
#include <limits>
#include <utility>

namespace local_sender {

BoundedPacketQueue::BoundedPacketQueue(
    const std::size_t maximum_packets,
    const std::size_t maximum_bytes)
    : maximum_packets_(maximum_packets), maximum_bytes_(maximum_bytes) {}

bool BoundedPacketQueue::push_batch(PacketBatch&& batch) {
    std::size_t incoming_bytes = 0;
    for (const Packet& packet : batch) {
        if (packet.empty() || packet.size() > 1316U ||
            incoming_bytes > std::numeric_limits<std::size_t>::max() - packet.size()) {
            return false;
        }
        incoming_bytes += packet.size();
    }

    std::lock_guard lock(mutex_);
    if (cancelled_ || batch.size() > maximum_packets_ - std::min(maximum_packets_, packets_.size()) ||
        incoming_bytes > maximum_bytes_ - std::min(maximum_bytes_, bytes_)) {
        return false;
    }
    for (Packet& packet : batch) {
        packets_.push_back(std::move(packet));
    }
    bytes_ += incoming_bytes;
    high_water_bytes_ = std::max(high_water_bytes_, bytes_);
    available_.notify_one();
    return true;
}

bool BoundedPacketQueue::wait_pop(Packet& packet) {
    std::unique_lock lock(mutex_);
    available_.wait(lock, [this] { return cancelled_ || !packets_.empty(); });
    if (cancelled_) return false;
    packet = std::move(packets_.front());
    packets_.pop_front();
    bytes_ -= packet.size();
    return true;
}

void BoundedPacketQueue::clear() {
    std::lock_guard lock(mutex_);
    packets_.clear();
    bytes_ = 0;
}

void BoundedPacketQueue::cancel() {
    std::lock_guard lock(mutex_);
    cancelled_ = true;
    packets_.clear();
    bytes_ = 0;
    available_.notify_all();
}

void BoundedPacketQueue::reset() {
    std::lock_guard lock(mutex_);
    packets_.clear();
    bytes_ = 0;
    high_water_bytes_ = 0;
    cancelled_ = false;
}

std::size_t BoundedPacketQueue::size_bytes() const {
    std::lock_guard lock(mutex_);
    return bytes_;
}

std::size_t BoundedPacketQueue::high_water_bytes() const {
    std::lock_guard lock(mutex_);
    return high_water_bytes_;
}

}  // namespace local_sender

