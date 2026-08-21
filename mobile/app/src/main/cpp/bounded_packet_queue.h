#ifndef LOCAL_CAMERA_SENDER_BOUNDED_PACKET_QUEUE_H
#define LOCAL_CAMERA_SENDER_BOUNDED_PACKET_QUEUE_H

#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <mutex>
#include <vector>

namespace local_sender {

using Packet = std::vector<std::uint8_t>;
using PacketBatch = std::vector<Packet>;

class BoundedPacketQueue final {
  public:
    BoundedPacketQueue(std::size_t maximum_packets, std::size_t maximum_bytes);

    BoundedPacketQueue(const BoundedPacketQueue&) = delete;
    BoundedPacketQueue& operator=(const BoundedPacketQueue&) = delete;

    [[nodiscard]] bool push_batch(PacketBatch&& batch);
    [[nodiscard]] bool wait_pop(Packet& packet);
    [[nodiscard]] bool wait_pop_for(Packet& packet, std::chrono::milliseconds timeout);
    void clear();
    void cancel();
    void reset();

    [[nodiscard]] std::size_t size_bytes() const;
    [[nodiscard]] std::size_t high_water_bytes() const;

  private:
    const std::size_t maximum_packets_;
    const std::size_t maximum_bytes_;
    mutable std::mutex mutex_;
    std::condition_variable available_;
    std::deque<Packet> packets_;
    std::size_t bytes_ = 0;
    std::size_t high_water_bytes_ = 0;
    bool cancelled_ = false;
};

}  // namespace local_sender

#endif

