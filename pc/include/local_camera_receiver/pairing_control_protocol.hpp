#pragma once

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>

namespace lcr {

inline constexpr std::array<std::byte, 12> kShowQrCommand{
    std::byte{'L'}, std::byte{'C'}, std::byte{'R'}, std::byte{'C'},
    std::byte{1}, std::byte{0}, std::byte{0}, std::byte{0},
    std::byte{'Q'}, std::byte{'R'}, std::byte{'!'}, std::byte{0},
};

inline constexpr std::array<std::byte, 8> kControlPrefix{
    std::byte{'L'}, std::byte{'C'}, std::byte{'R'}, std::byte{'C'},
    std::byte{1}, std::byte{0}, std::byte{0}, std::byte{0},
};

enum class PairingReceiverState : std::uint8_t {
    stopped = 0,
    waiting_for_pairing = 1,
    listening = 2,
    authenticating = 3,
    streaming = 4,
    reconnecting = 5,
    failed = 6,
};

[[nodiscard]] constexpr std::array<std::byte, 12> receiver_status_command(PairingReceiverState state) noexcept
{
    return {
        std::byte{'L'}, std::byte{'C'}, std::byte{'R'}, std::byte{'C'},
        std::byte{1}, std::byte{0}, std::byte{0}, std::byte{0},
        std::byte{'S'}, std::byte{'T'}, std::byte{'A'}, static_cast<std::byte>(state),
    };
}

[[nodiscard]] constexpr bool is_show_qr_command(std::span<const std::byte> bytes) noexcept
{
    return bytes.size() == kShowQrCommand.size() &&
        std::equal(bytes.begin(), bytes.end(), kShowQrCommand.begin());
}

[[nodiscard]] constexpr std::optional<PairingReceiverState> parse_receiver_status_command(
    std::span<const std::byte> bytes) noexcept
{
    if (bytes.size() != 12 || !std::equal(kControlPrefix.begin(), kControlPrefix.end(), bytes.begin()) ||
        bytes[8] != std::byte{'S'} || bytes[9] != std::byte{'T'} || bytes[10] != std::byte{'A'}) {
        return std::nullopt;
    }
    const auto raw = std::to_integer<std::uint8_t>(bytes[11]);
    if (raw > static_cast<std::uint8_t>(PairingReceiverState::failed)) return std::nullopt;
    return static_cast<PairingReceiverState>(raw);
}

} // namespace lcr
