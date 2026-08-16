#pragma once

#include <optional>
#include <string>
#include <string_view>

namespace lcr {

inline constexpr std::string_view kDeviceStreamIdPrefix = "lcr/1/";
inline constexpr std::size_t kMaximumDeviceLabelBytes = 48;

inline bool is_safe_device_label(const std::string_view label) noexcept
{
    if (label.empty() || label.size() > kMaximumDeviceLabelBytes) return false;
    for (const unsigned char character : label) {
        if ((character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z') ||
            (character >= '0' && character <= '9') || character == ' ' || character == '.' ||
            character == '_' || character == '-') {
            continue;
        }
        return false;
    }
    return label.front() != ' ' && label.back() != ' ';
}

inline std::optional<std::string> parse_device_stream_id(const std::string_view stream_id)
{
    if (!stream_id.starts_with(kDeviceStreamIdPrefix)) return std::nullopt;
    const std::string_view label = stream_id.substr(kDeviceStreamIdPrefix.size());
    if (!is_safe_device_label(label)) return std::nullopt;
    return std::string(label);
}

} // namespace lcr
