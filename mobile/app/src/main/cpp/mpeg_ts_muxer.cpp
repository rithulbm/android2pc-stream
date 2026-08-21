#include "mpeg_ts_muxer.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <limits>

namespace local_sender {
namespace {

constexpr std::uint16_t kPatPid = 0x0000;
constexpr std::uint16_t kPmtPid = 0x1000;
constexpr std::uint16_t kVideoPid = 0x0100;
constexpr std::uint16_t kAudioPid = 0x0101;
constexpr std::uint16_t kProgramNumber = 1;
// Tables repeat at ~100 ms (matching FFmpeg mpegtsenc defaults) plus on every
// keyframe, so a joining demuxer acquires PAT/PMT without waiting out a long
// interval on a live, non-seekable pipe.
constexpr std::int64_t kTableIntervalMicroseconds = 100'000;
constexpr std::uint64_t kPtsMask = (1ULL << 33U) - 1ULL;

std::uint32_t crc32_mpeg(const std::span<const std::uint8_t> bytes) {
    std::uint32_t crc = 0xFFFFFFFFU;
    for (const std::uint8_t byte : bytes) {
        crc ^= static_cast<std::uint32_t>(byte) << 24U;
        for (int bit = 0; bit < 8; ++bit) {
            const bool high = (crc & 0x80000000U) != 0U;
            crc <<= 1U;
            if (high) crc ^= 0x04C11DB7U;
        }
    }
    return crc;
}

void append_u16(std::vector<std::uint8_t>& output, const std::uint16_t value) {
    output.push_back(static_cast<std::uint8_t>((value >> 8U) & 0xFFU));
    output.push_back(static_cast<std::uint8_t>(value & 0xFFU));
}

void append_crc(std::vector<std::uint8_t>& section) {
    const std::uint32_t crc = crc32_mpeg(section);
    section.push_back(static_cast<std::uint8_t>((crc >> 24U) & 0xFFU));
    section.push_back(static_cast<std::uint8_t>((crc >> 16U) & 0xFFU));
    section.push_back(static_cast<std::uint8_t>((crc >> 8U) & 0xFFU));
    section.push_back(static_cast<std::uint8_t>(crc & 0xFFU));
}

void append_pts(std::vector<std::uint8_t>& output, const std::uint64_t pts) {
    const std::uint64_t value = pts & kPtsMask;
    output.push_back(static_cast<std::uint8_t>(0x20U | (((value >> 30U) & 0x07U) << 1U) | 0x01U));
    output.push_back(static_cast<std::uint8_t>((value >> 22U) & 0xFFU));
    output.push_back(static_cast<std::uint8_t>((((value >> 15U) & 0x7FU) << 1U) | 0x01U));
    output.push_back(static_cast<std::uint8_t>((value >> 7U) & 0xFFU));
    output.push_back(static_cast<std::uint8_t>(((value & 0x7FU) << 1U) | 0x01U));
}

int aac_frequency_index(const int sample_rate) {
    switch (sample_rate) {
        case 96'000: return 0;
        case 88'200: return 1;
        case 64'000: return 2;
        case 48'000: return 3;
        case 44'100: return 4;
        case 32'000: return 5;
        case 24'000: return 6;
        case 22'050: return 7;
        case 16'000: return 8;
        default: return -1;
    }
}

bool build_adts(
    const std::span<const std::uint8_t> raw,
    const int sample_rate,
    const int channels,
    std::vector<std::uint8_t>& output) {
    const int frequency_index = aac_frequency_index(sample_rate);
    if (frequency_index < 0 || channels < 1 || channels > 2 ||
        raw.size() > 8191U - 7U) {
        return false;
    }
    const std::size_t frame_length = raw.size() + 7U;
    output.resize(frame_length);
    output[0] = 0xFFU;
    output[1] = 0xF1U;  // MPEG-4, layer 0, no CRC.
    output[2] = static_cast<std::uint8_t>(
        (1U << 6U) | (static_cast<unsigned int>(frequency_index) << 2U) |
        (static_cast<unsigned int>(channels) >> 2U));
    output[3] = static_cast<std::uint8_t>(
        ((static_cast<unsigned int>(channels) & 0x03U) << 6U) |
        ((static_cast<unsigned int>(frame_length) >> 11U) & 0x03U));
    output[4] = static_cast<std::uint8_t>((static_cast<unsigned int>(frame_length) >> 3U) & 0xFFU);
    output[5] = static_cast<std::uint8_t>(
        ((static_cast<unsigned int>(frame_length) & 0x07U) << 5U) | 0x1FU);
    output[6] = 0xFCU;
    std::copy(raw.begin(), raw.end(), output.begin() + 7);
    return true;
}

}  // namespace

MpegTsMuxer::MpegTsMuxer(
    const VideoCodec video_codec,
    const bool audio_enabled,
    const int audio_sample_rate,
    const int audio_channels)
    : video_codec_(video_codec),
      audio_enabled_(audio_enabled),
      audio_sample_rate_(audio_sample_rate),
      audio_channels_(audio_channels) {}

void MpegTsMuxer::reset(const std::int64_t origin_microseconds) {
    origin_microseconds_ = origin_microseconds;
    last_tables_microseconds_ = -1;
    continuity_.fill(0);
    discontinuity_pending_.fill(true);
}

bool MpegTsMuxer::write_video(
    const std::span<const std::uint8_t> access_unit,
    const std::int64_t presentation_time_microseconds,
    const bool key_frame,
    PacketBatch& output) {
    if (access_unit.empty() || access_unit.size() > kMaximumVideoAccessUnitBytes ||
        presentation_time_microseconds < 0) {
        return false;
    }
    if (origin_microseconds_ < 0) reset(presentation_time_microseconds);
    if (presentation_time_microseconds < origin_microseconds_) return false;

    std::vector<TsPacket> packets;
    const bool tables_due = last_tables_microseconds_ < 0 || key_frame ||
        presentation_time_microseconds - last_tables_microseconds_ >= kTableIntervalMicroseconds;
    if (tables_due) {
        emit_tables(packets);
        last_tables_microseconds_ = presentation_time_microseconds;
    }
    if (!emit_pes(
            kVideoPid,
            0xE0U,
            access_unit,
            presentation_time_microseconds,
            key_frame,
            true,
            packets)) {
        return false;
    }
    group_packets(packets, output);
    return !output.empty();
}

bool MpegTsMuxer::write_audio(
    const std::span<const std::uint8_t> raw_aac,
    const std::int64_t presentation_time_microseconds,
    PacketBatch& output) {
    if (!audio_enabled_ || raw_aac.empty() || raw_aac.size() > kMaximumAudioAccessUnitBytes ||
        origin_microseconds_ < 0 || presentation_time_microseconds < origin_microseconds_) {
        return false;
    }
    std::vector<std::uint8_t> adts;
    if (!build_adts(raw_aac, audio_sample_rate_, audio_channels_, adts)) return false;
    std::vector<TsPacket> packets;
    if (!emit_pes(
            kAudioPid,
            0xC0U,
            adts,
            presentation_time_microseconds,
            false,
            false,
            packets)) {
        return false;
    }
    group_packets(packets, output);
    return !output.empty();
}

void MpegTsMuxer::emit_tables(std::vector<TsPacket>& packets) {
    std::vector<std::uint8_t> pat{
        0x00U,
        0xB0U,
        0x0DU,
        0x00U,
        0x01U,
        0xC1U,
        0x00U,
        0x00U,
    };
    append_u16(pat, kProgramNumber);
    append_u16(pat, static_cast<std::uint16_t>(0xE000U | kPmtPid));
    append_crc(pat);
    emit_psi(kPatPid, pat, packets);

    const std::uint16_t stream_bytes = static_cast<std::uint16_t>(audio_enabled_ ? 10U : 5U);
    const std::uint16_t section_length = static_cast<std::uint16_t>(13U + stream_bytes);
    std::vector<std::uint8_t> pmt{
        0x02U,
        static_cast<std::uint8_t>(0xB0U | ((section_length >> 8U) & 0x0FU)),
        static_cast<std::uint8_t>(section_length & 0xFFU),
    };
    append_u16(pmt, kProgramNumber);
    pmt.insert(pmt.end(), {0xC1U, 0x00U, 0x00U});
    append_u16(pmt, static_cast<std::uint16_t>(0xE000U | kVideoPid));
    pmt.insert(pmt.end(), {0xF0U, 0x00U});
    pmt.push_back(video_codec_ == VideoCodec::kHevc ? 0x24U : 0x1BU);
    append_u16(pmt, static_cast<std::uint16_t>(0xE000U | kVideoPid));
    pmt.insert(pmt.end(), {0xF0U, 0x00U});
    if (audio_enabled_) {
        pmt.push_back(0x0FU);
        append_u16(pmt, static_cast<std::uint16_t>(0xE000U | kAudioPid));
        pmt.insert(pmt.end(), {0xF0U, 0x00U});
    }
    append_crc(pmt);
    emit_psi(kPmtPid, pmt, packets);
}

void MpegTsMuxer::emit_psi(
    const std::uint16_t pid,
    const std::span<const std::uint8_t> section,
    std::vector<TsPacket>& packets) {
    if (section.size() + 1U > 184U) return;
    TsPacket packet{};
    packet.fill(0xFFU);
    packet[0] = 0x47U;
    packet[1] = static_cast<std::uint8_t>(0x40U | ((pid >> 8U) & 0x1FU));
    packet[2] = static_cast<std::uint8_t>(pid & 0xFFU);
    packet[3] = static_cast<std::uint8_t>(0x10U | next_continuity(pid));
    packet[4] = 0x00U;
    std::copy(section.begin(), section.end(), packet.begin() + 5);
    packets.push_back(packet);
}

bool MpegTsMuxer::emit_pes(
    const std::uint16_t pid,
    const std::uint8_t stream_id,
    const std::span<const std::uint8_t> payload,
    const std::int64_t presentation_time_microseconds,
    const bool key_frame,
    const bool include_pcr,
    std::vector<TsPacket>& packets) {
    if (payload.size() > kMaximumVideoAccessUnitBytes) return false;
    std::vector<std::uint8_t> pes;
    if (payload.size() > std::numeric_limits<std::size_t>::max() - 14U) return false;
    pes.reserve(payload.size() + 14U);
    pes.insert(pes.end(), {0x00U, 0x00U, 0x01U, stream_id});
    const std::size_t packet_length = payload.size() + 8U;
    const std::uint16_t encoded_length =
        packet_length <= 0xFFFFU ? static_cast<std::uint16_t>(packet_length) : 0U;
    append_u16(pes, encoded_length);
    pes.insert(pes.end(), {0x80U, 0x80U, 0x05U});
    append_pts(pes, pts90(presentation_time_microseconds));
    pes.insert(pes.end(), payload.begin(), payload.end());

    std::size_t offset = 0;
    bool first = true;
    const bool discont = pid < discontinuity_pending_.size() && discontinuity_pending_[pid];
    while (offset < pes.size()) {
        TsPacket packet{};
        packet.fill(0xFFU);
        packet[0] = 0x47U;
        packet[1] = static_cast<std::uint8_t>(((first ? 0x40U : 0x00U)) | ((pid >> 8U) & 0x1FU));
        packet[2] = static_cast<std::uint8_t>(pid & 0xFFU);

        const std::size_t remaining = pes.size() - offset;
        const bool pcr_here = first && include_pcr;
        // A discontinuity_indicator needs a flags byte, so the adaptation field
        // must be at least two bytes (length + flags).
        const std::size_t minimum_adaptation_total =
            pcr_here ? 8U : (discont && first ? 2U : 0U);
        std::size_t adaptation_total = minimum_adaptation_total;
        const std::size_t capacity_with_minimum = 184U - minimum_adaptation_total;
        if (remaining < capacity_with_minimum) {
            adaptation_total = 184U - remaining;
        }
        const bool has_adaptation = adaptation_total > 0U;
        packet[3] = static_cast<std::uint8_t>(
            (has_adaptation ? 0x30U : 0x10U) | next_continuity(pid));

        std::size_t payload_offset = 4U;
        if (has_adaptation) {
            packet[4] = static_cast<std::uint8_t>(adaptation_total - 1U);
            payload_offset += adaptation_total;
            if (adaptation_total > 1U) {
                packet[5] = static_cast<std::uint8_t>(
                    (key_frame && first ? 0x40U : 0x00U) | (pcr_here ? 0x10U : 0x00U) |
                    (discont && first ? 0x80U : 0x00U));
                if (pcr_here) {
                    const std::uint64_t pcr = pcr27(presentation_time_microseconds);
                    const std::uint64_t base = (pcr / 300U) & kPtsMask;
                    const std::uint16_t extension = static_cast<std::uint16_t>(pcr % 300U);
                    packet[6] = static_cast<std::uint8_t>((base >> 25U) & 0xFFU);
                    packet[7] = static_cast<std::uint8_t>((base >> 17U) & 0xFFU);
                    packet[8] = static_cast<std::uint8_t>((base >> 9U) & 0xFFU);
                    packet[9] = static_cast<std::uint8_t>((base >> 1U) & 0xFFU);
                    packet[10] = static_cast<std::uint8_t>(((base & 0x01U) << 7U) | 0x7EU |
                        ((extension >> 8U) & 0x01U));
                    packet[11] = static_cast<std::uint8_t>(extension & 0xFFU);
                }
            }
        }
        const std::size_t writable = kTransportPacketBytes - payload_offset;
        const std::size_t copied = std::min(writable, remaining);
        std::copy_n(pes.begin() + static_cast<std::ptrdiff_t>(offset), copied,
                    packet.begin() + static_cast<std::ptrdiff_t>(payload_offset));
        offset += copied;
        packets.push_back(packet);
        first = false;
    }
    if (discont && pid < discontinuity_pending_.size()) {
        discontinuity_pending_[pid] = false;
    }
    return true;
}

std::uint8_t MpegTsMuxer::next_continuity(const std::uint16_t pid) {
    if (pid >= continuity_.size()) return 0;
    const std::uint8_t current = continuity_[pid];
    continuity_[pid] = static_cast<std::uint8_t>((current + 1U) & 0x0FU);
    return current;
}

std::uint64_t MpegTsMuxer::pts90(const std::int64_t microseconds) const {
    const std::uint64_t relative = static_cast<std::uint64_t>(microseconds - origin_microseconds_);
    return ((relative * 90U) / 1000U) & kPtsMask;
}

std::uint64_t MpegTsMuxer::pcr27(const std::int64_t microseconds) const {
    const std::uint64_t relative = static_cast<std::uint64_t>(microseconds - origin_microseconds_);
    return relative * 27U;
}

void MpegTsMuxer::group_packets(
    const std::span<const TsPacket> packets,
    PacketBatch& output) const {
    output.clear();
    output.reserve((packets.size() + 6U) / 7U);
    std::size_t index = 0;
    while (index < packets.size()) {
        const std::size_t count = std::min<std::size_t>(7U, packets.size() - index);
        Packet payload(count * kTransportPacketBytes);
        for (std::size_t packet_index = 0; packet_index < count; ++packet_index) {
            std::copy(
                packets[index + packet_index].begin(),
                packets[index + packet_index].end(),
                payload.begin() + static_cast<std::ptrdiff_t>(packet_index * kTransportPacketBytes));
        }
        output.push_back(std::move(payload));
        index += count;
    }
}

}  // namespace local_sender

