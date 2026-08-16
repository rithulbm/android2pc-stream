#pragma once

#include <algorithm>
#include <array>
#include <cstddef>
#include <span>

namespace lcr {

inline constexpr std::array<std::byte, 12> kShowQrCommand{
    std::byte{'L'}, std::byte{'C'}, std::byte{'R'}, std::byte{'C'},
    std::byte{1}, std::byte{0}, std::byte{0}, std::byte{0},
    std::byte{'Q'}, std::byte{'R'}, std::byte{'!'}, std::byte{0},
};

[[nodiscard]] constexpr bool is_show_qr_command(std::span<const std::byte> bytes) noexcept
{
    return bytes.size() == kShowQrCommand.size() &&
        std::equal(bytes.begin(), bytes.end(), kShowQrCommand.begin());
}

} // namespace lcr
