#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace lcr {

struct NetworkAddress final {
    std::string address;
    std::string display_name;
    bool ethernet = false;
    bool wifi = false;
    bool has_default_gateway = false;
    bool likely_virtual = false;
    std::uint32_t metric = UINT32_MAX;
};

[[nodiscard]] bool is_likely_virtual_adapter_name(std::string_view name) noexcept;
void sort_network_addresses(std::vector<NetworkAddress> &addresses);
[[nodiscard]] std::optional<std::size_t> saved_network_index(
    const std::vector<NetworkAddress> &addresses,
    std::string_view saved_host) noexcept;
[[nodiscard]] std::vector<NetworkAddress> enumerate_private_ipv4_addresses();

} // namespace lcr
