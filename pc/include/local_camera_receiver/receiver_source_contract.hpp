#pragma once

#include <obs-module.h>

#include <cstdint>

namespace lcr {

// A composite source renders a private FFmpeg child and attaches that child for
// audio. OBS rejects composite sources that also claim direct OBS_SOURCE_AUDIO.
inline constexpr std::uint32_t kReceiverSourceOutputFlags =
    OBS_SOURCE_VIDEO | OBS_SOURCE_COMPOSITE | OBS_SOURCE_DO_NOT_DUPLICATE;

[[nodiscard]] constexpr bool receiver_source_registration_contract(
    std::uint32_t flags,
    bool has_audio_render) noexcept
{
    const bool composite = (flags & OBS_SOURCE_COMPOSITE) != 0U;
    const bool direct_audio = (flags & OBS_SOURCE_AUDIO) != 0U;
    return !(composite && direct_audio) && (!composite || has_audio_render);
}

static_assert((kReceiverSourceOutputFlags & OBS_SOURCE_COMPOSITE) != 0U);
static_assert((kReceiverSourceOutputFlags & OBS_SOURCE_AUDIO) == 0U);
static_assert(receiver_source_registration_contract(kReceiverSourceOutputFlags, true));

} // namespace lcr
