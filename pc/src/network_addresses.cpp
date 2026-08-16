#include "local_camera_receiver/network_addresses.hpp"
#include "local_camera_receiver/receiver_config.hpp"

#include <WinSock2.h>
#include <ws2tcpip.h>
#include <Windows.h>
#include <iptypes.h>
#include <iphlpapi.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <limits>
#include <memory>

namespace lcr {
namespace {

std::string utf8(const wchar_t *text)
{
    if (text == nullptr || *text == L'\0') {
        return {};
    }
    const int required = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text, -1, nullptr, 0, nullptr, nullptr);
    if (required <= 1) {
        return {};
    }
    std::string output(static_cast<std::size_t>(required), '\0');
    if (WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text, -1, output.data(), required, nullptr, nullptr) != required) {
        return {};
    }
    output.pop_back();
    return output;
}

} // namespace

bool is_likely_virtual_adapter_name(const std::string_view name) noexcept
{
    std::string lowered;
    lowered.reserve(name.size());
    for (const unsigned char character : name) {
        lowered.push_back(static_cast<char>(std::tolower(character)));
    }
    constexpr std::array<std::string_view, 10> virtual_markers{
        "vethernet", "virtual", "hyper-v", "vmware", "virtualbox",
        "default switch", "wsl", "tap", "tailscale", "zerotier",
    };
    return std::ranges::any_of(virtual_markers, [&lowered](const std::string_view marker) {
        return lowered.find(marker) != std::string::npos;
    });
}

void sort_network_addresses(std::vector<NetworkAddress> &addresses)
{
    std::ranges::sort(addresses, [](const NetworkAddress &left, const NetworkAddress &right) {
        if (left.likely_virtual != right.likely_virtual) return left.likely_virtual < right.likely_virtual;
        if (left.has_default_gateway != right.has_default_gateway) {
            return left.has_default_gateway > right.has_default_gateway;
        }
        const bool left_physical = left.ethernet || left.wifi;
        const bool right_physical = right.ethernet || right.wifi;
        if (left_physical != right_physical) return left_physical > right_physical;
        if (left.metric != right.metric) return left.metric < right.metric;
        if (left.ethernet != right.ethernet) return left.ethernet > right.ethernet;
        return left.address < right.address;
    });
}

std::optional<std::size_t> saved_network_index(
    const std::vector<NetworkAddress> &addresses,
    const std::string_view saved_host) noexcept
{
    const auto saved = std::ranges::find_if(addresses, [saved_host](const NetworkAddress &address) {
        return address.address == saved_host;
    });
    if (saved == addresses.end()) return std::nullopt;
    const std::size_t index = static_cast<std::size_t>(std::distance(addresses.begin(), saved));
    const bool preferred_physical_available = std::ranges::any_of(addresses, [](const NetworkAddress &address) {
        return !address.likely_virtual && address.has_default_gateway && (address.ethernet || address.wifi);
    });
    if (saved->likely_virtual && preferred_physical_available) return std::nullopt;
    return index;
}

std::vector<NetworkAddress> enumerate_private_ipv4_addresses()
{
    ULONG size = 16U * 1024U;
    std::vector<std::uint8_t> buffer(size);
    ULONG result = GetAdaptersAddresses(
        AF_INET,
        GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER | GAA_FLAG_INCLUDE_GATEWAYS,
        nullptr,
        reinterpret_cast<IP_ADAPTER_ADDRESSES *>(buffer.data()),
        &size);
    if (result == ERROR_BUFFER_OVERFLOW && size <= 1024U * 1024U) {
        buffer.resize(size);
        result = GetAdaptersAddresses(
            AF_INET,
            GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER | GAA_FLAG_INCLUDE_GATEWAYS,
            nullptr,
            reinterpret_cast<IP_ADAPTER_ADDRESSES *>(buffer.data()),
            &size);
    }
    if (result != NO_ERROR) {
        return {};
    }

    std::vector<NetworkAddress> addresses;
    for (auto *adapter = reinterpret_cast<IP_ADAPTER_ADDRESSES *>(buffer.data()); adapter != nullptr; adapter = adapter->Next) {
        if (adapter->OperStatus != IfOperStatusUp || adapter->IfType == IF_TYPE_SOFTWARE_LOOPBACK ||
            adapter->IfType == IF_TYPE_TUNNEL) {
            continue;
        }
        for (auto *entry = adapter->FirstUnicastAddress; entry != nullptr; entry = entry->Next) {
            if (entry->Address.lpSockaddr == nullptr || entry->Address.lpSockaddr->sa_family != AF_INET) {
                continue;
            }
            const auto *ipv4 = reinterpret_cast<const sockaddr_in *>(entry->Address.lpSockaddr);
            std::array<char, INET_ADDRSTRLEN> text{};
            if (InetNtopA(AF_INET, &ipv4->sin_addr, text.data(), static_cast<DWORD>(text.size())) == nullptr ||
                !is_canonical_private_ipv4(text.data())) {
                continue;
            }
            const bool ethernet = adapter->IfType == IF_TYPE_ETHERNET_CSMACD;
            const bool wifi = adapter->IfType == IF_TYPE_IEEE80211;
            const std::string friendly = utf8(adapter->FriendlyName);
            addresses.push_back(NetworkAddress{
                text.data(),
                friendly.empty() ? std::string(text.data()) : friendly,
                ethernet,
                wifi,
                adapter->FirstGatewayAddress != nullptr,
                is_likely_virtual_adapter_name(friendly),
                adapter->Ipv4Metric,
            });
        }
    }
    sort_network_addresses(addresses);
    addresses.erase(
        std::unique(addresses.begin(), addresses.end(), [](const NetworkAddress &left, const NetworkAddress &right) {
            return left.address == right.address;
        }),
        addresses.end());
    return addresses;
}

} // namespace lcr
