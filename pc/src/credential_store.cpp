#include "local_camera_receiver/receiver_config.hpp"

#include <Windows.h>
#include <dpapi.h>
#include <shlobj.h>

#include <algorithm>
#include <array>
#include <fstream>
#include <limits>

namespace lcr {
namespace {

constexpr std::array<std::uint8_t, 4> kOuterMagic{'L', 'C', 'R', 'D'};
constexpr std::uint8_t kOuterVersion = 1;
constexpr std::size_t kMaximumProtectedBytes = 64U * 1024U;
constexpr std::array<std::uint8_t, 32> kEntropy{
    0x0c, 0xca, 0xbd, 0x48, 0x43, 0x94, 0x7f, 0x51,
    0x91, 0xa0, 0xeb, 0x67, 0x7d, 0xa7, 0xbe, 0x08,
    0x0d, 0xad, 0xa8, 0xdf, 0xeb, 0x43, 0x09, 0x52,
    0x33, 0x34, 0x9a, 0x04, 0x76, 0x78, 0x35, 0xf0,
};

void append_u32(std::vector<std::uint8_t> &output, const std::uint32_t value)
{
    for (unsigned shift = 0; shift < 32; shift += 8) {
        output.push_back(static_cast<std::uint8_t>((value >> shift) & 0xffU));
    }
}

bool read_u32(const std::span<const std::uint8_t> input, std::size_t &offset, std::uint32_t &value) noexcept
{
    if (input.size() - offset < 4) {
        return false;
    }
    value = 0;
    for (unsigned shift = 0; shift < 32; shift += 8) {
        value |= static_cast<std::uint32_t>(input[offset++]) << shift;
    }
    return true;
}

void wipe(std::vector<std::uint8_t> &bytes) noexcept
{
    if (!bytes.empty()) {
        SecureZeroMemory(bytes.data(), bytes.size());
    }
}

bool write_atomic(const std::filesystem::path &path, const std::span<const std::uint8_t> bytes) noexcept
{
    try {
        std::filesystem::create_directories(path.parent_path());
        const auto temporary = path.wstring() + L".tmp." + std::to_wstring(GetCurrentProcessId()) + L"." +
                               std::to_wstring(GetTickCount64());
        const HANDLE file = CreateFileW(
            temporary.c_str(),
            GENERIC_WRITE,
            0,
            nullptr,
            CREATE_NEW,
            FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_NOT_CONTENT_INDEXED | FILE_FLAG_WRITE_THROUGH,
            nullptr);
        if (file == INVALID_HANDLE_VALUE) {
            return false;
        }
        DWORD written = 0;
        const bool ok = bytes.size() <= std::numeric_limits<DWORD>::max() &&
                        WriteFile(file, bytes.data(), static_cast<DWORD>(bytes.size()), &written, nullptr) != FALSE &&
                        written == bytes.size() && FlushFileBuffers(file) != FALSE;
        CloseHandle(file);
        if (!ok || MoveFileExW(temporary.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH) == FALSE) {
            DeleteFileW(temporary.c_str());
            return false;
        }
        return true;
    } catch (...) {
        return false;
    }
}

std::optional<std::vector<std::uint8_t>> read_bounded(const std::filesystem::path &path) noexcept
{
    try {
        const auto size = std::filesystem::file_size(path);
        if (size < 9 || size > kMaximumProtectedBytes) {
            return std::nullopt;
        }
        std::ifstream stream(path, std::ios::binary);
        if (!stream) {
            return std::nullopt;
        }
        std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
        stream.read(reinterpret_cast<char *>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
        if (!stream || stream.gcount() != static_cast<std::streamsize>(bytes.size())) {
            wipe(bytes);
            return std::nullopt;
        }
        return bytes;
    } catch (...) {
        return std::nullopt;
    }
}

} // namespace

std::filesystem::path default_config_path()
{
    PWSTR raw = nullptr;
    if (SHGetKnownFolderPath(FOLDERID_RoamingAppData, KF_FLAG_CREATE, nullptr, &raw) != S_OK || raw == nullptr) {
        return {};
    }
    std::filesystem::path result(raw);
    CoTaskMemFree(raw);
    return result / L"obs-studio" / L"plugin_config" / L"local-camera-receiver" / L"receiver.dat";
}

bool save_config_dpapi(const std::filesystem::path &path, const ReceiverConfig &config) noexcept
{
    if (path.empty() || validate_config(config, now_epoch_seconds()) != ConfigError::none) {
        return false;
    }
    auto plain = serialize_config(config);
    DATA_BLOB input{static_cast<DWORD>(plain.size()), plain.data()};
    DATA_BLOB entropy{static_cast<DWORD>(kEntropy.size()), const_cast<BYTE *>(kEntropy.data())};
    DATA_BLOB protected_blob{};
    const bool protected_ok = CryptProtectData(
        &input,
        L"Local Camera Receiver v1 credential",
        &entropy,
        nullptr,
        nullptr,
        CRYPTPROTECT_UI_FORBIDDEN,
        &protected_blob) != FALSE;
    wipe(plain);
    if (!protected_ok || protected_blob.cbData == 0 || protected_blob.cbData > kMaximumProtectedBytes) {
        if (protected_blob.pbData != nullptr) {
            SecureZeroMemory(protected_blob.pbData, protected_blob.cbData);
            LocalFree(protected_blob.pbData);
        }
        return false;
    }

    std::vector<std::uint8_t> outer;
    outer.reserve(kOuterMagic.size() + 1 + 4 + protected_blob.cbData);
    outer.insert(outer.end(), kOuterMagic.begin(), kOuterMagic.end());
    outer.push_back(kOuterVersion);
    append_u32(outer, protected_blob.cbData);
    outer.insert(outer.end(), protected_blob.pbData, protected_blob.pbData + protected_blob.cbData);
    SecureZeroMemory(protected_blob.pbData, protected_blob.cbData);
    LocalFree(protected_blob.pbData);
    const bool saved = write_atomic(path, outer);
    wipe(outer);
    return saved;
}

std::optional<ReceiverConfig> load_config_dpapi(
    const std::filesystem::path &path,
    const std::uint64_t now,
    ConfigError &error) noexcept
{
    error = ConfigError::io_failure;
    auto outer_optional = read_bounded(path);
    if (!outer_optional) {
        return std::nullopt;
    }
    auto &outer = *outer_optional;
    std::size_t offset = 0;
    if (outer.size() < kOuterMagic.size() + 5 ||
        !std::equal(kOuterMagic.begin(), kOuterMagic.end(), outer.begin())) {
        error = ConfigError::malformed;
        wipe(outer);
        return std::nullopt;
    }
    offset += kOuterMagic.size();
    if (outer[offset++] != kOuterVersion) {
        error = ConfigError::unsupported_version;
        wipe(outer);
        return std::nullopt;
    }
    std::uint32_t protected_length = 0;
    if (!read_u32(outer, offset, protected_length) || protected_length == 0 ||
        protected_length > kMaximumProtectedBytes || outer.size() - offset != protected_length) {
        error = ConfigError::malformed;
        wipe(outer);
        return std::nullopt;
    }

    DATA_BLOB input{protected_length, outer.data() + offset};
    DATA_BLOB entropy{static_cast<DWORD>(kEntropy.size()), const_cast<BYTE *>(kEntropy.data())};
    DATA_BLOB plain_blob{};
    const bool decrypted = CryptUnprotectData(
        &input,
        nullptr,
        &entropy,
        nullptr,
        nullptr,
        CRYPTPROTECT_UI_FORBIDDEN,
        &plain_blob) != FALSE;
    wipe(outer);
    if (!decrypted || plain_blob.cbData == 0 || plain_blob.cbData > 4096) {
        error = ConfigError::crypto_failure;
        if (plain_blob.pbData != nullptr) {
            SecureZeroMemory(plain_blob.pbData, plain_blob.cbData);
            LocalFree(plain_blob.pbData);
        }
        return std::nullopt;
    }
    const auto plain = std::span<const std::uint8_t>(plain_blob.pbData, plain_blob.cbData);
    auto config = deserialize_config(plain, now, error);
    SecureZeroMemory(plain_blob.pbData, plain_blob.cbData);
    LocalFree(plain_blob.pbData);
    return config;
}

} // namespace lcr
