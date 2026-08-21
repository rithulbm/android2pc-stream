#ifndef LOCAL_CAMERA_SENDER_MPEG_TS_MUXER_H
#define LOCAL_CAMERA_SENDER_MPEG_TS_MUXER_H

#include "bounded_packet_queue.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace local_sender {

enum class VideoCodec : std::uint8_t { kHevc = 1, kAvc = 2 };

class MpegTsMuxer final {
  public:
    static constexpr std::size_t kTransportPacketBytes = 188;
    static constexpr std::size_t kSrtPayloadBytes = 1316;
    static constexpr std::size_t kMaximumVideoAccessUnitBytes = 6U * 1024U * 1024U;
    static constexpr std::size_t kMaximumAudioAccessUnitBytes = 64U * 1024U;

    MpegTsMuxer(VideoCodec video_codec, bool audio_enabled, int audio_sample_rate, int audio_channels);

    void reset(std::int64_t origin_microseconds);
    [[nodiscard]] bool write_video(
        std::span<const std::uint8_t> access_unit,
        std::int64_t presentation_time_microseconds,
        bool key_frame,
        PacketBatch& output);
    [[nodiscard]] bool write_audio(
        std::span<const std::uint8_t> raw_aac,
        std::int64_t presentation_time_microseconds,
        PacketBatch& output);

  private:
    using TsPacket = std::array<std::uint8_t, kTransportPacketBytes>;

    void emit_tables(std::vector<TsPacket>& packets);
    void emit_psi(std::uint16_t pid, std::span<const std::uint8_t> section, std::vector<TsPacket>& packets);
    [[nodiscard]] bool emit_pes(
        std::uint16_t pid,
        std::uint8_t stream_id,
        std::span<const std::uint8_t> payload,
        std::int64_t presentation_time_microseconds,
        bool key_frame,
        bool include_pcr,
        std::vector<TsPacket>& packets);
    [[nodiscard]] std::uint8_t next_continuity(std::uint16_t pid);
    [[nodiscard]] std::uint64_t pts90(std::int64_t microseconds) const;
    [[nodiscard]] std::uint64_t pcr27(std::int64_t microseconds) const;
    void group_packets(std::span<const TsPacket> packets, PacketBatch& output) const;

    VideoCodec video_codec_;
    bool audio_enabled_;
    int audio_sample_rate_;
    int audio_channels_;
    std::int64_t origin_microseconds_ = -1;
    std::int64_t last_tables_microseconds_ = -1;
    std::array<std::uint8_t, 8192> continuity_{};
    // Set by reset() and consumed on the first PES packet of each PID: marks the
    // adaptation field discontinuity_indicator so downstream demuxers resync
    // continuity counters and timestamps after a sender reconnect instead of
    // flagging the stream corrupt.
    std::array<bool, 8192> discontinuity_pending_{};
};

}  // namespace local_sender

#endif

