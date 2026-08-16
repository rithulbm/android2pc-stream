#pragma once

#include <array>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <optional>
#include <span>
#include <string>
#include <vector>

namespace lcr {

inline constexpr std::uint16_t kMinimumPort = 1024;
inline constexpr std::uint16_t kMinimumLatencyMs = 60;
inline constexpr std::uint16_t kMaximumLatencyMs = 2000;
inline constexpr std::size_t kSecretBytes = 32;
inline constexpr std::size_t kMaximumLabelUtf8Bytes = 192;

struct ReceiverConfig final {
    std::string receiver_id;
    std::string label;
    std::string host;
    std::uint16_t port = 0;
    std::uint16_t latency_ms = 0;
    std::uint64_t credential_expires_epoch_seconds = 0;
    std::array<std::uint8_t, kSecretBytes> secret{};

    void clear_secret() noexcept;
};

enum class ConfigError {
    none,
    malformed,
    unsupported_version,
    invalid_receiver_id,
    invalid_label,
    invalid_host,
    invalid_port,
    invalid_latency,
    expired,
    unsafe_lifetime,
    crypto_failure,
    io_failure,
};

[[nodiscard]] bool is_canonical_private_ipv4(const std::string &host) noexcept;
[[nodiscard]] bool is_canonical_uuid(const std::string &value) noexcept;
[[nodiscard]] ConfigError validate_config(const ReceiverConfig &config, std::uint64_t now_epoch_seconds) noexcept;
[[nodiscard]] std::optional<ReceiverConfig> generate_config(
    const std::string &label,
    const std::string &host,
    std::uint16_t port,
    std::uint16_t latency_ms,
    std::uint64_t now_epoch_seconds) noexcept;
[[nodiscard]] std::string build_pairing_payload(
    const ReceiverConfig &config,
    std::uint64_t qr_expires_epoch_seconds,
    std::uint64_t now_epoch_seconds);

[[nodiscard]] std::vector<std::uint8_t> serialize_config(const ReceiverConfig &config);
[[nodiscard]] std::optional<ReceiverConfig> deserialize_config(
    std::span<const std::uint8_t> bytes,
    std::uint64_t now_epoch_seconds,
    ConfigError &error) noexcept;

[[nodiscard]] std::filesystem::path default_config_path();
[[nodiscard]] bool save_config_dpapi(const std::filesystem::path &path, const ReceiverConfig &config) noexcept;
[[nodiscard]] std::optional<ReceiverConfig> load_config_dpapi(
    const std::filesystem::path &path,
    std::uint64_t now_epoch_seconds,
    ConfigError &error) noexcept;

[[nodiscard]] std::uint64_t now_epoch_seconds() noexcept;

} // namespace lcr
