#include "local_camera_receiver/receiver_config.hpp"

#include <Windows.h>
#include <bcrypt.h>
#include <ws2tcpip.h>

#include <algorithm>
#include <array>
#include <charconv>
#include <cstdio>
#include <cstring>
#include <limits>

namespace lcr {
namespace {

constexpr std::array<std::uint8_t, 4> kPlainMagic{'L', 'C', 'R', '1'};
constexpr std::uint8_t kFormatVersion = 1;
constexpr std::uint64_t kMaximumCredentialLifetimeSeconds = 366ULL * 24ULL * 60ULL * 60ULL;
constexpr std::uint64_t kMaximumQrLifetimeSeconds = 10ULL * 60ULL;

void append_u16(std::vector<std::uint8_t> &out, const std::uint16_t value)
{
    out.push_back(static_cast<std::uint8_t>(value & 0xffU));
    out.push_back(static_cast<std::uint8_t>((value >> 8U) & 0xffU));
}

void append_u32(std::vector<std::uint8_t> &out, const std::uint32_t value)
{
    for (unsigned shift = 0; shift < 32; shift += 8) {
        out.push_back(static_cast<std::uint8_t>((value >> shift) & 0xffU));
    }
}

void append_u64(std::vector<std::uint8_t> &out, const std::uint64_t value)
{
    for (unsigned shift = 0; shift < 64; shift += 8) {
        out.push_back(static_cast<std::uint8_t>((value >> shift) & 0xffU));
    }
}

bool take_u16(std::span<const std::uint8_t> bytes, std::size_t &offset, std::uint16_t &value) noexcept
{
    if (bytes.size() - offset < 2) {
        return false;
    }
    value = static_cast<std::uint16_t>(bytes[offset]) |
            static_cast<std::uint16_t>(static_cast<std::uint16_t>(bytes[offset + 1]) << 8U);
    offset += 2;
    return true;
}

bool take_u32(std::span<const std::uint8_t> bytes, std::size_t &offset, std::uint32_t &value) noexcept
{
    if (bytes.size() - offset < 4) {
        return false;
    }
    value = 0;
    for (unsigned shift = 0; shift < 32; shift += 8) {
        value |= static_cast<std::uint32_t>(bytes[offset++]) << shift;
    }
    return true;
}

bool take_u64(std::span<const std::uint8_t> bytes, std::size_t &offset, std::uint64_t &value) noexcept
{
    if (bytes.size() - offset < 8) {
        return false;
    }
    value = 0;
    for (unsigned shift = 0; shift < 64; shift += 8) {
        value |= static_cast<std::uint64_t>(bytes[offset++]) << shift;
    }
    return true;
}

void append_string(std::vector<std::uint8_t> &out, const std::string &value)
{
    append_u32(out, static_cast<std::uint32_t>(value.size()));
    out.insert(out.end(), value.begin(), value.end());
}

bool take_string(
    const std::span<const std::uint8_t> bytes,
    std::size_t &offset,
    const std::size_t maximum,
    std::string &value) noexcept
{
    std::uint32_t length = 0;
    if (!take_u32(bytes, offset, length) || length > maximum || bytes.size() - offset < length) {
        return false;
    }
    value.assign(reinterpret_cast<const char *>(bytes.data() + offset), length);
    offset += length;
    return true;
}

bool valid_utf8_label(const std::string &label) noexcept
{
    if (label.empty() || label.size() > kMaximumLabelUtf8Bytes || label.front() == ' ' || label.back() == ' ') {
        return false;
    }
    const int required = MultiByteToWideChar(
        CP_UTF8,
        MB_ERR_INVALID_CHARS,
        label.data(),
        static_cast<int>(label.size()),
        nullptr,
        0);
    if (required <= 0 || required > 96) {
        return false;
    }
    std::wstring decoded(static_cast<std::size_t>(required), L'\0');
    if (MultiByteToWideChar(
            CP_UTF8,
            MB_ERR_INVALID_CHARS,
            label.data(),
            static_cast<int>(label.size()),
            decoded.data(),
            required) != required) {
        return false;
    }
    std::size_t code_points = 0;
    for (std::size_t index = 0; index < decoded.size(); ++index) {
        const wchar_t value = decoded[index];
        if (value < 0x20 || value == 0x7f || (value >= 0x202a && value <= 0x202e) ||
            (value >= 0x2066 && value <= 0x2069)) {
            return false;
        }
        if (value >= 0xd800 && value <= 0xdbff) {
            if (index + 1 >= decoded.size() || decoded[index + 1] < 0xdc00 || decoded[index + 1] > 0xdfff) {
                return false;
            }
            ++index;
        } else if (value >= 0xdc00 && value <= 0xdfff) {
            return false;
        }
        if (++code_points > 48) {
            return false;
        }
    }
    return true;
}

std::string new_uuid() noexcept
{
    std::array<std::uint8_t, 16> bytes{};
    if (BCryptGenRandom(nullptr, bytes.data(), static_cast<ULONG>(bytes.size()), BCRYPT_USE_SYSTEM_PREFERRED_RNG) != 0) {
        return {};
    }
    bytes[6] = static_cast<std::uint8_t>((bytes[6] & 0x0fU) | 0x40U);
    bytes[8] = static_cast<std::uint8_t>((bytes[8] & 0x3fU) | 0x80U);
    std::array<char, 37> text{};
    const int written = std::snprintf(
        text.data(),
        text.size(),
        "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
        bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
        bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]);
    return written == 36 ? std::string(text.data(), 36) : std::string{};
}

std::string base64url(const std::array<std::uint8_t, kSecretBytes> &bytes)
{
    static constexpr char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string output;
    output.reserve(43);
    std::uint32_t accumulator = 0;
    unsigned bits = 0;
    for (const auto byte : bytes) {
        accumulator = (accumulator << 8U) | byte;
        bits += 8;
        while (bits >= 6) {
            bits -= 6;
            output.push_back(alphabet[(accumulator >> bits) & 0x3fU]);
        }
    }
    if (bits > 0) {
        output.push_back(alphabet[(accumulator << (6U - bits)) & 0x3fU]);
    }
    return output;
}

std::string percent_encode(const std::string &value)
{
    static constexpr char hex[] = "0123456789ABCDEF";
    std::string output;
    output.reserve(value.size() * 3);
    for (const unsigned char byte : value) {
        const bool unreserved = (byte >= 'A' && byte <= 'Z') || (byte >= 'a' && byte <= 'z') ||
                                (byte >= '0' && byte <= '9') || byte == '-' || byte == '.' || byte == '_' || byte == '~';
        if (unreserved) {
            output.push_back(static_cast<char>(byte));
        } else {
            output.push_back('%');
            output.push_back(hex[byte >> 4U]);
            output.push_back(hex[byte & 0x0fU]);
        }
    }
    return output;
}

} // namespace

void ReceiverConfig::clear_secret() noexcept
{
    SecureZeroMemory(secret.data(), secret.size());
}

bool is_canonical_private_ipv4(const std::string &host) noexcept
{
    IN_ADDR address{};
    if (host.empty() || InetPtonA(AF_INET, host.c_str(), &address) != 1) {
        return false;
    }
    std::array<char, INET_ADDRSTRLEN> canonical{};
    if (InetNtopA(AF_INET, &address, canonical.data(), static_cast<DWORD>(canonical.size())) == nullptr ||
        host != canonical.data()) {
        return false;
    }
    const std::uint32_t value = ntohl(address.S_un.S_addr);
    const std::uint8_t first = static_cast<std::uint8_t>(value >> 24U);
    const std::uint8_t second = static_cast<std::uint8_t>((value >> 16U) & 0xffU);
    return first == 10 || (first == 172 && second >= 16 && second <= 31) ||
           (first == 192 && second == 168) || (first == 169 && second == 254);
}

bool is_canonical_uuid(const std::string &value) noexcept
{
    if (value.size() != 36) {
        return false;
    }
    for (std::size_t index = 0; index < value.size(); ++index) {
        if (index == 8 || index == 13 || index == 18 || index == 23) {
            if (value[index] != '-') {
                return false;
            }
        } else if (!((value[index] >= '0' && value[index] <= '9') ||
                     (value[index] >= 'a' && value[index] <= 'f'))) {
            return false;
        }
    }
    return value[14] == '4' && (value[19] == '8' || value[19] == '9' || value[19] == 'a' || value[19] == 'b');
}

ConfigError validate_config(const ReceiverConfig &config, const std::uint64_t now) noexcept
{
    if (!is_canonical_uuid(config.receiver_id)) return ConfigError::invalid_receiver_id;
    if (!valid_utf8_label(config.label)) return ConfigError::invalid_label;
    if (!is_canonical_private_ipv4(config.host)) return ConfigError::invalid_host;
    if (config.port < kMinimumPort) return ConfigError::invalid_port;
    if (config.latency_ms < kMinimumLatencyMs || config.latency_ms > kMaximumLatencyMs) return ConfigError::invalid_latency;
    if (config.credential_expires_epoch_seconds <= now) return ConfigError::expired;
    if (config.credential_expires_epoch_seconds - now > kMaximumCredentialLifetimeSeconds) return ConfigError::unsafe_lifetime;
    return ConfigError::none;
}

std::optional<ReceiverConfig> generate_config(
    const std::string &label,
    const std::string &host,
    const std::uint16_t port,
    const std::uint16_t latency_ms,
    const std::uint64_t now) noexcept
{
    ReceiverConfig config{};
    config.receiver_id = new_uuid();
    config.label = label;
    config.host = host;
    config.port = port;
    config.latency_ms = latency_ms;
    config.credential_expires_epoch_seconds = now + 30ULL * 24ULL * 60ULL * 60ULL;
    if (BCryptGenRandom(nullptr, config.secret.data(), static_cast<ULONG>(config.secret.size()), BCRYPT_USE_SYSTEM_PREFERRED_RNG) != 0 ||
        validate_config(config, now) != ConfigError::none) {
        config.clear_secret();
        return std::nullopt;
    }
    return config;
}

std::string build_pairing_payload(
    const ReceiverConfig &config,
    const std::uint64_t qr_expires,
    const std::uint64_t now)
{
    if (validate_config(config, now) != ConfigError::none || qr_expires <= now ||
        qr_expires - now > kMaximumQrLifetimeSeconds ||
        qr_expires > config.credential_expires_epoch_seconds) {
        return {};
    }
    return "pcstream://pair/v1?receiver_id=" + config.receiver_id +
           "&label=" + percent_encode(config.label) +
           "&host=" + config.host +
           "&port=" + std::to_string(config.port) +
           "&secret=" + base64url(config.secret) +
           "&qr_expires=" + std::to_string(qr_expires) +
           "&credential_expires=" + std::to_string(config.credential_expires_epoch_seconds) +
           "&latency_ms=" + std::to_string(config.latency_ms) +
           "&pbkeylen=32";
}

std::vector<std::uint8_t> serialize_config(const ReceiverConfig &config)
{
    std::vector<std::uint8_t> output;
    output.reserve(320);
    output.insert(output.end(), kPlainMagic.begin(), kPlainMagic.end());
    output.push_back(kFormatVersion);
    append_string(output, config.receiver_id);
    append_string(output, config.label);
    append_string(output, config.host);
    append_u16(output, config.port);
    append_u16(output, config.latency_ms);
    append_u64(output, config.credential_expires_epoch_seconds);
    output.insert(output.end(), config.secret.begin(), config.secret.end());
    return output;
}

std::optional<ReceiverConfig> deserialize_config(
    const std::span<const std::uint8_t> bytes,
    const std::uint64_t now,
    ConfigError &error) noexcept
{
    error = ConfigError::malformed;
    if (bytes.size() < kPlainMagic.size() + 1 ||
        !std::equal(kPlainMagic.begin(), kPlainMagic.end(), bytes.begin())) {
        return std::nullopt;
    }
    std::size_t offset = kPlainMagic.size();
    if (bytes[offset++] != kFormatVersion) {
        error = ConfigError::unsupported_version;
        return std::nullopt;
    }
    ReceiverConfig config{};
    if (!take_string(bytes, offset, 36, config.receiver_id) ||
        !take_string(bytes, offset, kMaximumLabelUtf8Bytes, config.label) ||
        !take_string(bytes, offset, INET_ADDRSTRLEN - 1, config.host) ||
        !take_u16(bytes, offset, config.port) ||
        !take_u16(bytes, offset, config.latency_ms) ||
        !take_u64(bytes, offset, config.credential_expires_epoch_seconds) ||
        bytes.size() - offset != config.secret.size()) {
        return std::nullopt;
    }
    std::copy_n(bytes.begin() + static_cast<std::ptrdiff_t>(offset), config.secret.size(), config.secret.begin());
    error = validate_config(config, now);
    if (error != ConfigError::none) {
        config.clear_secret();
        return std::nullopt;
    }
    return config;
}

std::uint64_t now_epoch_seconds() noexcept
{
    return static_cast<std::uint64_t>(std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count());
}

} // namespace lcr
