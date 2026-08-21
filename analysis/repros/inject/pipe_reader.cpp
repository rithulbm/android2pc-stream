// pipe_reader.exe -- throwaway INBOUND named-pipe server used only to
// smoke-test inject.exe without OBS. It is the inverse of the product's
// NamedPipeSink: same creation parameters (byte mode, 1 instance, 1 MB
// buffers, same-user DACL "D:P(A;;GA;;;SID)") but PIPE_ACCESS_INBOUND, so it
// ACCEPTS inject.exe as a client, counts the bytes, and validates MPEG-TS
// 188-byte packet sync (0x47 at every packet boundary).
//
// Usage:
//   pipe_reader.exe <\\.\pipe\name> [--max-mb N] [--clients N]
//
// Exits 0 after --max-mb received (default 8) or --clients served; prints a
// per-client and total summary. Non-zero sync-error count means inject (or
// the pacing/chunking) corrupted the stream.

#include <windows.h>
#include <sddl.h>

#include <cstdint>
#include <cstdarg>
#include <cstdio>
#include <string>
#include <vector>

namespace {

LARGE_INTEGER g_freq{};
LARGE_INTEGER g_start{};

double elapsed_s() noexcept
{
    LARGE_INTEGER now{};
    QueryPerformanceCounter(&now);
    return static_cast<double>(now.QuadPart - g_start.QuadPart) / static_cast<double>(g_freq.QuadPart);
}

void log_line(const char *fmt, ...) noexcept
{
    fprintf(stdout, "[%9.3fs] ", elapsed_s());
    va_list args{};
    va_start(args, fmt);
    vfprintf(stdout, fmt, args);
    va_end(args);
    fputc('\n', stdout);
    fflush(stdout);
}

std::wstring to_wide(const std::string &text)
{
    if (text.empty()) return {};
    const int size = MultiByteToWideChar(CP_UTF8, 0, text.c_str(), -1, nullptr, 0);
    std::wstring wide(static_cast<std::size_t>(size > 0 ? size : 0), L'\0');
    if (size > 0) MultiByteToWideChar(CP_UTF8, 0, text.c_str(), -1, wide.data(), size);
    if (!wide.empty()) wide.pop_back();
    return wide;
}

struct SecurityDescriptor final {
    PSECURITY_DESCRIPTOR descriptor = nullptr;
    SECURITY_ATTRIBUTES attributes{sizeof(SECURITY_ATTRIBUTES), nullptr, FALSE};

    ~SecurityDescriptor()
    {
        if (descriptor != nullptr) LocalFree(descriptor);
    }
};

// Same DACL model as pc/src/named_pipe_sink.cpp.
bool current_user_only(SecurityDescriptor &security) noexcept
{
    HANDLE token = nullptr;
    if (OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token) == FALSE) return false;
    DWORD required = 0;
    GetTokenInformation(token, TokenUser, nullptr, 0, &required);
    if (required == 0 || required > 64U * 1024U) {
        CloseHandle(token);
        return false;
    }
    std::vector<std::uint8_t> buffer(required);
    const bool queried = GetTokenInformation(token, TokenUser, buffer.data(), required, &required) != FALSE;
    CloseHandle(token);
    if (!queried) return false;
    const auto *user = reinterpret_cast<const TOKEN_USER *>(buffer.data());
    LPWSTR sid = nullptr;
    if (ConvertSidToStringSidW(user->User.Sid, &sid) == FALSE || sid == nullptr) return false;
    const std::wstring sddl = L"D:P(A;;GA;;;" + std::wstring(sid) + L")";
    LocalFree(sid);
    if (ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl.c_str(), SDDL_REVISION_1, &security.descriptor, nullptr) == FALSE) {
        return false;
    }
    security.attributes.lpSecurityDescriptor = security.descriptor;
    return true;
}

struct ClientReport final {
    unsigned long long bytes = 0;
    unsigned long long packets_checked = 0;
    unsigned long long sync_errors = 0;
    unsigned long long first_bad_offset = ULLONG_MAX;
    double duration = 0.0;
};

int serve(const std::wstring &pipe_name, unsigned long long max_bytes, unsigned long long client_limit)
{
    SecurityDescriptor security{};
    if (!current_user_only(security)) {
        log_line("ERROR: cannot build security descriptor");
        return 2;
    }

    unsigned long long grand_bytes = 0;
    unsigned long long grand_packets = 0;
    unsigned long long grand_sync_errors = 0;
    unsigned long long clients_served = 0;

    while (grand_bytes < max_bytes && clients_served < client_limit) {
        const HANDLE handle = CreateNamedPipeW(
            pipe_name.c_str(),
            PIPE_ACCESS_INBOUND,
            PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
            1,
            1024U * 1024U,
            1024U * 1024U,
            0,
            &security.attributes);
        if (handle == INVALID_HANDLE_VALUE) {
            log_line("CreateNamedPipe failed gle=%lu; retry in 75 ms", GetLastError());
            Sleep(75);
            continue;
        }
        log_line("LISTENING (inbound instance up, waiting for injector)");
        const BOOL connected =
            ConnectNamedPipe(handle, nullptr) != FALSE || GetLastError() == ERROR_PIPE_CONNECTED;
        if (!connected) {
            log_line("ConnectNamedPipe failed gle=%lu; recreating", GetLastError());
            DisconnectNamedPipe(handle);
            CloseHandle(handle);
            continue;
        }
        ++clients_served;
        log_line("CLIENT #%llu connected", clients_served);

        ClientReport report{};
        std::vector<std::uint8_t> buffer(64U * 1024U);
        unsigned long long absolute_offset = 0;
        const double client_t0 = elapsed_s();

        for (;;) {
            DWORD read_done = 0;
            if (ReadFile(handle, buffer.data(), static_cast<DWORD>(buffer.size()), &read_done, nullptr) == FALSE ||
                read_done == 0) {
                log_line("ReadFile ended gle=%lu (ERROR_BROKEN_PIPE=109 = clean client close)",
                         GetLastError());
                break;
            }
            report.bytes += read_done;
            for (DWORD i = 0; i < read_done; ++i) {
                if ((absolute_offset % 188) == 0) {
                    ++report.packets_checked;
                    if (buffer[i] != 0x47) {
                        ++report.sync_errors;
                        if (report.first_bad_offset == ULLONG_MAX) {
                            report.first_bad_offset = absolute_offset;
                            log_line("FIRST SYNC ERROR at offset %llu (got 0x%02X, expected 0x47)",
                                     absolute_offset, buffer[i]);
                        }
                    }
                }
                ++absolute_offset;
            }
        }

        report.duration = elapsed_s() - client_t0;
        log_line(
            "CLIENT #%llu summary: bytes=%llu packets=%llu sync_errors=%llu dur=%.3fs kbps=%.1f",
            clients_served, report.bytes, report.packets_checked, report.sync_errors,
            report.duration,
            report.duration > 0.0 ? static_cast<double>(report.bytes) * 8.0 / 1000.0 / report.duration : 0.0);

        grand_bytes += report.bytes;
        grand_packets += report.packets_checked;
        grand_sync_errors += report.sync_errors;

        DisconnectNamedPipe(handle);
        CloseHandle(handle);
        log_line("instance destroyed; recreating");
    }

    log_line(
        "TOTAL: clients=%llu bytes=%llu packets=%llu sync_errors=%llu",
        clients_served, grand_bytes, grand_packets, grand_sync_errors);
    return grand_sync_errors == 0 ? 0 : 1;
}

} // namespace

int main(int argc, char **argv)
{
    QueryPerformanceFrequency(&g_freq);
    QueryPerformanceCounter(&g_start);

    if (argc < 2) {
        fprintf(stderr, "usage: pipe_reader.exe <\\\\.\\pipe\\name> [--max-mb N] [--clients N]\n");
        return 2;
    }
    unsigned long long max_mb = 8;
    unsigned long long clients = ULLONG_MAX;
    for (int i = 2; i < argc; ++i) {
        const std::string arg = argv[i];
        auto parse = [&](const char *prefix, unsigned long long &out) -> bool {
            const size_t len = strlen(prefix);
            if (arg.rfind(prefix, 0) == 0 && arg.size() > len) {
                out = _strtoui64(arg.c_str() + len, nullptr, 10);
                return true;
            }
            return false;
        };
        if (parse("--max-mb=", max_mb) || parse("--clients=", clients)) continue;
        fprintf(stderr, "unknown arg: %s\n", arg.c_str());
        return 2;
    }
    return serve(to_wide(argv[1]), max_mb * 1024ULL * 1024ULL, clients);
}
