#pragma once

#include <string_view>

namespace lcr {

enum class PairingLaunchAction {
    none,
    background,
    show_qr,
};

[[nodiscard]] constexpr wchar_t ascii_lower(wchar_t value) noexcept
{
    return value >= L'A' && value <= L'Z' ? static_cast<wchar_t>(value + (L'a' - L'A')) : value;
}

[[nodiscard]] constexpr bool ascii_iequals(std::wstring_view left, std::wstring_view right) noexcept
{
    if (left.size() != right.size()) return false;
    for (std::size_t index = 0; index < left.size(); ++index) {
        if (ascii_lower(left[index]) != ascii_lower(right[index])) return false;
    }
    return true;
}

[[nodiscard]] constexpr PairingLaunchAction pairing_launch_action(std::wstring_view argument) noexcept
{
    if (ascii_iequals(argument, L"/background")) return PairingLaunchAction::background;
    if (ascii_iequals(argument, L"/show-qr")) return PairingLaunchAction::show_qr;
    return PairingLaunchAction::none;
}

} // namespace lcr
