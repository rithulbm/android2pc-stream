#include "local_camera_receiver/bounded_packet_queue.hpp"
#include "local_camera_receiver/device_stream_id.hpp"
#include "local_camera_receiver/network_addresses.hpp"
#include "local_camera_receiver/pairing_control_protocol.hpp"
#include "local_camera_receiver/pairing_launch.hpp"
#include "local_camera_receiver/receiver_config.hpp"
#include "local_camera_receiver/receiver_source_contract.hpp"
#include "local_camera_receiver/receiver_status_text.hpp"

#include <Windows.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <span>
#include <string>
#include <thread>
#include <vector>

namespace {

int failures = 0;

void expect(const bool condition, const char *message)
{
    if (condition) return;
    ++failures;
    std::cerr << "FAIL: " << message << '\n';
}

lcr::ReceiverConfig known_config(const std::uint64_t now)
{
    lcr::ReceiverConfig config{};
    config.receiver_id = "125be249-d3cc-47be-9258-9429ef0ae841";
    config.label = "Living Room PC";
    config.host = "192.168.1.20";
    config.port = 9000;
    config.latency_ms = 120;
    config.credential_expires_epoch_seconds = now + 3600;
    for (std::size_t index = 0; index < config.secret.size(); ++index) {
        config.secret[index] = static_cast<std::uint8_t>(index);
    }
    return config;
}

void test_private_ipv4_validation()
{
    expect(lcr::is_canonical_private_ipv4("10.0.0.1"), "10/8 should be private");
    expect(lcr::is_canonical_private_ipv4("172.16.0.1"), "172.16/12 lower boundary should be private");
    expect(lcr::is_canonical_private_ipv4("172.31.255.254"), "172.16/12 upper boundary should be private");
    expect(lcr::is_canonical_private_ipv4("192.168.1.1"), "192.168/16 should be private");
    expect(lcr::is_canonical_private_ipv4("169.254.2.3"), "link-local IPv4 should be accepted");
    expect(!lcr::is_canonical_private_ipv4("127.0.0.1"), "loopback must not be accepted");
    expect(!lcr::is_canonical_private_ipv4("8.8.8.8"), "public IPv4 must not be accepted");
    expect(!lcr::is_canonical_private_ipv4("192.168.001.001"), "non-canonical IPv4 must not be accepted");
    expect(!lcr::is_canonical_private_ipv4("::1"), "IPv6 must not pass the IPv4 validator");
    expect(!lcr::is_canonical_private_ipv4(""), "empty host must not be accepted");
}

void test_config_validation_and_generation()
{
    constexpr std::uint64_t now = 1'800'000'000ULL;
    auto config = known_config(now);
    expect(lcr::validate_config(config, now) == lcr::ConfigError::none, "known config should validate");

    config.receiver_id[14] = '5';
    expect(lcr::validate_config(config, now) == lcr::ConfigError::invalid_receiver_id, "UUID must be version 4");
    config = known_config(now);
    config.label = " leading";
    expect(lcr::validate_config(config, now) == lcr::ConfigError::invalid_label, "leading spaces must be rejected");
    config = known_config(now);
    config.label = std::string("bad\xE2\x80\xAEname", 10);
    expect(lcr::validate_config(config, now) == lcr::ConfigError::invalid_label, "bidi controls must be rejected");
    config = known_config(now);
    config.host = "203.0.113.4";
    expect(lcr::validate_config(config, now) == lcr::ConfigError::invalid_host, "public address must be rejected");
    config = known_config(now);
    config.port = 1023;
    expect(lcr::validate_config(config, now) == lcr::ConfigError::invalid_port, "privileged port must be rejected");
    config = known_config(now);
    config.latency_ms = 59;
    expect(lcr::validate_config(config, now) == lcr::ConfigError::invalid_latency, "low latency boundary must be rejected");
    config.latency_ms = 2001;
    expect(lcr::validate_config(config, now) == lcr::ConfigError::invalid_latency, "high latency boundary must be rejected");
    config = known_config(now);
    config.credential_expires_epoch_seconds = now;
    expect(lcr::validate_config(config, now) == lcr::ConfigError::expired, "expired credentials must be rejected");
    config.credential_expires_epoch_seconds = now + 367ULL * 24ULL * 60ULL * 60ULL;
    expect(lcr::validate_config(config, now) == lcr::ConfigError::unsafe_lifetime, "unbounded credentials must be rejected");

    const auto generated = lcr::generate_config("My PC", "10.20.30.40", 65535, 2000, now);
    expect(generated.has_value(), "valid boundary settings should generate");
    if (generated) {
        expect(lcr::is_canonical_uuid(generated->receiver_id), "generated receiver ID should be UUID v4");
        expect(std::any_of(generated->secret.begin(), generated->secret.end(), [](std::uint8_t byte) { return byte != 0; }),
               "generated secret should not be all zeroes");
    }
    expect(!lcr::generate_config("My PC", "8.8.8.8", 9000, 120, now), "generation must fail closed on public host");
}

void test_pairing_payload_and_serialization()
{
    constexpr std::uint64_t now = 1'800'000'000ULL;
    auto config = known_config(now);
    const std::string payload = lcr::build_pairing_payload(config, now + 600, now);
    expect(payload.starts_with("pcstream://pair/v1?receiver_id="), "pairing URI should use the v1 scheme");
    expect(payload.find("label=Living%20Room%20PC") != std::string::npos, "label must use canonical percent encoding");
    expect(payload.find("&host=192.168.1.20&port=9000&secret=") != std::string::npos, "endpoint fields should be present");
    expect(payload.find('+') == std::string::npos, "base64url and query must not contain plus");
    expect(payload.find("=") != std::string::npos && payload.ends_with("&pbkeylen=32"), "key length must be explicit");
    expect(lcr::build_pairing_payload(config, now, now).empty(), "expired QR payloads must be rejected");
    expect(lcr::build_pairing_payload(config, now + 601, now).empty(), "overlong QR payloads must be rejected");
    config.credential_expires_epoch_seconds = now + 300;
    expect(lcr::build_pairing_payload(config, now + 301, now).empty(), "QR expiry must not outlive the credential");
    config = known_config(now);

    auto serialized = lcr::serialize_config(config);
    lcr::ConfigError error = lcr::ConfigError::none;
    auto decoded = lcr::deserialize_config(serialized, now, error);
    expect(decoded.has_value() && error == lcr::ConfigError::none, "serialized config should round-trip");
    if (decoded) {
        expect(decoded->receiver_id == config.receiver_id && decoded->secret == config.secret,
               "round-trip must preserve ID and secret");
        decoded->clear_secret();
    }

    auto unsupported = serialized;
    unsupported[4] = 2;
    decoded = lcr::deserialize_config(unsupported, now, error);
    expect(!decoded && error == lcr::ConfigError::unsupported_version, "unknown versions must fail closed");
    decoded = lcr::deserialize_config(std::span<const std::uint8_t>(serialized.data(), serialized.size() - 1), now, error);
    expect(!decoded && error == lcr::ConfigError::malformed, "truncated records must fail closed");
    serialized.push_back(0);
    decoded = lcr::deserialize_config(serialized, now, error);
    expect(!decoded && error == lcr::ConfigError::malformed, "trailing bytes must fail closed");
    config.clear_secret();
}

void test_bounded_queue()
{
    lcr::BoundedPacketQueue queue;
    const std::array<std::uint8_t, 3> packet{1, 2, 3};
    expect(!queue.push({}), "empty queue writes must be rejected");
    expect(queue.push(packet), "normal packet should enqueue");
    expect(queue.size() == 1 && queue.bytes() == packet.size(), "queue accounting should be exact");
    const auto popped = queue.wait_pop();
    expect(popped && *popped == std::vector<std::uint8_t>(packet.begin(), packet.end()), "queue should preserve bytes");
    expect(queue.size() == 0 && queue.bytes() == 0, "pop should decrement accounting");

    std::vector<std::uint8_t> oversized(lcr::BoundedPacketQueue::kMaximumPacketBytes + 1, 7);
    expect(!queue.push(oversized), "oversized packets must be rejected");
    for (std::size_t index = 0; index < lcr::BoundedPacketQueue::kMaximumPackets; ++index) {
        expect(queue.push(std::span<const std::uint8_t>(packet.data(), 1)), "queue should accept packets through its count boundary");
    }
    expect(!queue.push(std::span<const std::uint8_t>(packet.data(), 1)), "queue should reject count overflow");
    queue.clear();
    expect(queue.size() == 0 && queue.bytes() == 0, "clear should reset accounting");

    std::atomic<bool> waiter_returned{false};
    std::thread waiter([&] {
        const auto value = queue.wait_pop();
        waiter_returned.store(!value.has_value());
    });
    queue.cancel();
    waiter.join();
    expect(waiter_returned.load(), "cancel should release a blocked consumer");
    expect(!queue.push(packet), "cancelled queue should reject new packets");
    queue.reset();
    expect(queue.push(packet), "reset should allow a new lifecycle");
    queue.clear();
}

void test_dpapi_store()
{
    const std::uint64_t now = lcr::now_epoch_seconds();
    auto config = known_config(now);
    wchar_t temp_buffer[MAX_PATH]{};
    const DWORD length = GetTempPathW(MAX_PATH, temp_buffer);
    expect(length > 0 && length < MAX_PATH, "Windows temp path should be available");
    if (length == 0 || length >= MAX_PATH) return;
    const std::filesystem::path directory = std::filesystem::path(temp_buffer) /
        (L"lcr-tests-" + std::to_wstring(GetCurrentProcessId()) + L"-" + std::to_wstring(GetTickCount64()));
    const auto path = directory / L"receiver.dat";
    expect(lcr::save_config_dpapi(path, config), "DPAPI config should save for current user");
    lcr::ConfigError error = lcr::ConfigError::none;
    auto loaded = lcr::load_config_dpapi(path, now, error);
    expect(loaded && error == lcr::ConfigError::none && loaded->secret == config.secret,
           "DPAPI config should decrypt for the current user");
    if (loaded) loaded->clear_secret();

    std::fstream file(path, std::ios::in | std::ios::out | std::ios::binary);
    file.seekg(-1, std::ios::end);
    char last = 0;
    file.read(&last, 1);
    last = static_cast<char>(last ^ 0x55);
    file.seekp(-1, std::ios::end);
    file.write(&last, 1);
    file.close();
    loaded = lcr::load_config_dpapi(path, now, error);
    expect(!loaded && error == lcr::ConfigError::crypto_failure, "tampered DPAPI data must fail authentication");

    const auto invalid_path = directory / L"invalid.dat";
    config.host = "203.0.113.7";
    expect(!lcr::save_config_dpapi(invalid_path, config), "invalid configs must not be encrypted or saved");
    expect(!std::filesystem::exists(invalid_path), "failed config validation must not create a file");
    config.clear_secret();
    std::error_code cleanup_error;
    std::filesystem::remove_all(directory, cleanup_error);
    expect(!cleanup_error, "test credential directory should clean up");
}

void test_network_enumeration()
{
    const auto addresses = lcr::enumerate_private_ipv4_addresses();
    std::cout << "Private network addresses found: " << addresses.size() << '\n';
    for (const auto &address : addresses) {
        expect(lcr::is_canonical_private_ipv4(address.address), "enumeration must return only canonical private IPv4");
        expect(!address.display_name.empty(), "enumerated address should have a display name");
    }
}

void test_network_ranking()
{
    expect(lcr::is_likely_virtual_adapter_name("vEthernet (Default Switch)"),
           "Hyper-V vEthernet must be identified as virtual");
    expect(lcr::is_likely_virtual_adapter_name("VMware Network Adapter VMnet8"),
           "VMware adapters must be identified as virtual");
    expect(!lcr::is_likely_virtual_adapter_name("Intel(R) Wi-Fi 7 BE200"),
           "physical Wi-Fi must not be treated as virtual");

    std::vector<lcr::NetworkAddress> addresses{
        {"172.23.128.1", "vEthernet (Default Switch)", true, false, false, true, 15},
        {"192.168.1.20", "Intel Ethernet", true, false, true, false, 25},
        {"192.168.1.21", "Intel Wi-Fi", false, true, true, false, 35},
    };
    lcr::sort_network_addresses(addresses);
    expect(addresses[0].display_name == "Intel Ethernet", "routable physical Ethernet should rank first");
    expect(addresses[1].display_name == "Intel Wi-Fi", "routable physical Wi-Fi should rank before virtual adapters");
    expect(addresses[2].display_name.starts_with("vEthernet"), "virtual adapters should remain selectable but rank last");
    expect(lcr::saved_network_index(addresses, "192.168.1.20") == 0,
           "a saved physical network should be restored");
    expect(!lcr::saved_network_index(addresses, "172.23.128.1"),
           "a saved virtual network should not override a reachable physical route");
    expect(!lcr::saved_network_index(addresses, "10.0.0.99"),
           "a missing saved network should require a new selection");

    std::vector<lcr::NetworkAddress> virtual_only{addresses[2]};
    expect(lcr::saved_network_index(virtual_only, "172.23.128.1") == 0,
           "a virtual adapter remains usable when it is the only active option");
}

void test_obs_source_contract()
{
    expect((lcr::kReceiverSourceOutputFlags & OBS_SOURCE_COMPOSITE) != 0U,
           "receiver must remain a composite source");
    expect((lcr::kReceiverSourceOutputFlags & OBS_SOURCE_AUDIO) == 0U,
           "composite source must not claim direct audio");
    expect(lcr::receiver_source_registration_contract(lcr::kReceiverSourceOutputFlags, true),
           "composite receiver with an audio renderer must satisfy OBS registration");
    expect(!lcr::receiver_source_registration_contract(lcr::kReceiverSourceOutputFlags, false),
           "composite receiver without an audio renderer must fail the contract");
    expect(!lcr::receiver_source_registration_contract(
               lcr::kReceiverSourceOutputFlags | OBS_SOURCE_AUDIO, true),
           "composite receiver must never advertise direct audio");
}

void test_pairing_launch_arguments()
{
    expect(lcr::pairing_launch_action(L"/background") == lcr::PairingLaunchAction::background,
           "background launch argument must start hidden");
    expect(lcr::pairing_launch_action(L"/SHOW-QR") == lcr::PairingLaunchAction::show_qr,
           "OBS show-QR launch argument must be case-insensitive");
    expect(lcr::pairing_launch_action(L"/unknown") == lcr::PairingLaunchAction::none,
           "unknown launch arguments must not trigger a privileged action");
    expect(lcr::pairing_launch_action(L"") == lcr::PairingLaunchAction::none,
           "empty launch arguments must use the normal foreground path");
}

void test_pairing_control_protocol()
{
    expect(lcr::is_show_qr_command(lcr::kShowQrCommand),
           "the exact local control command must open the pairing QR");

    auto changed = lcr::kShowQrCommand;
    changed.back() = std::byte{1};
    expect(!lcr::is_show_qr_command(changed),
           "a modified local control command must fail closed");

    expect(!lcr::is_show_qr_command(
               std::span<const std::byte>(lcr::kShowQrCommand.data(), lcr::kShowQrCommand.size() - 1U)),
           "a truncated local control command must fail closed");
    expect(!lcr::is_show_qr_command(std::span<const std::byte>{}),
           "an empty local control command must fail closed");
}

void test_authenticated_device_stream_id()
{
    const auto parsed = lcr::parse_device_stream_id("lcr/1/Google Pixel 9");
    expect(parsed && *parsed == "Google Pixel 9", "valid device stream ID should expose its safe label");
    expect(!lcr::parse_device_stream_id("other/1/Google Pixel 9"), "foreign stream ID namespace must be rejected");
    expect(!lcr::parse_device_stream_id("lcr/1/../phone"), "protocol characters in a device label must be rejected");
    expect(!lcr::parse_device_stream_id("lcr/1/ phone"), "leading whitespace in a device label must be rejected");
    expect(!lcr::parse_device_stream_id("lcr/1/" + std::string(49, 'x')), "oversized device labels must be rejected");
}

void test_receiver_status_copy()
{
    lcr::ReceiverStatus status{};
    status.state = lcr::ReceiverState::listening;
    expect(lcr::receiver_status_text(status).find("starts streaming automatically") != std::string::npos,
           "listening status should explain the automatic scan handoff");

    status.state = lcr::ReceiverState::streaming;
    status.connected_device = "Google Pixel 9";
    status.peer_address = "192.168.1.44";
    expect(lcr::receiver_status_text(status) == "Connected phone: Google Pixel 9 (192.168.1.44)",
           "streaming status should list the authenticated phone and private address");

    status.state = lcr::ReceiverState::failed;
    status.error = lcr::ReceiverError::bind;
    expect(lcr::receiver_status_text(status).find("port 9000") != std::string::npos,
           "bind failures should be distinguishable from an absent phone");
}

} // namespace

int main()
{
    test_private_ipv4_validation();
    test_config_validation_and_generation();
    test_pairing_payload_and_serialization();
    test_bounded_queue();
    test_dpapi_store();
    test_network_enumeration();
    test_network_ranking();
    test_obs_source_contract();
    test_pairing_launch_arguments();
    test_pairing_control_protocol();
    test_authenticated_device_stream_id();
    test_receiver_status_copy();
    if (failures != 0) {
        std::cerr << failures << " receiver test(s) failed\n";
        return 1;
    }
    std::cout << "All receiver tests passed\n";
    return 0;
}
