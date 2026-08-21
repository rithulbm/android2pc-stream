// inject.exe -- MPEG-TS named-pipe CLIENT injector for OBS-side pipeline repro.
//
// Inverse of analysis/repros/pipe/pipe_server.cpp: instead of a server that
// writes, this is the client that connects to the pipe created by the OBS
// plugin (pc/src/named_pipe_sink.cpp: CreateNamedPipeW PIPE_ACCESS_OUTBOUND,
// byte mode, 1MB buffers, same-user DACL) and feeds it known-good MPEG-TS.
//
// Usage:
//   inject.exe --pipe <\\.\pipe\name> --file <file.ts>
//              [--loop N|forever] [--fps-scale X] [--chunk N]
//              [--bitrate N] [--open-timeout-secs N]
//
// Pacing: one chunk every ~3 ms by default => 1316*8/0.003 ~= 3.5 Mbps,
// approximating the real sender bitrate. --fps-scale X multiplies the rate
// (X=2 plays twice as fast). --bitrate overrides the base rate outright.
//
// Console keys while running:
//   p  pause/resume writing (handle stays open; emulates sender stall)
//   r  force disconnect + reconnect (emulates SRT reconnect / fresh keyframe)
//   q or Esc  quit
//
// Pipe-open retry budget is 60 s (configurable). ERROR_PIPE_BUSY goes through
// WaitNamedPipe; ERROR_FILE_NOT_FOUND retries too, so inject can be started
// BEFORE the OBS source exists (tests NamedPipeSink's ERROR_PIPE_CONNECTED
// race handling on ConnectNamedPipe).

#include <windows.h>
#include <conio.h>
#include <timeapi.h>

#include <climits>
#include <cstdint>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

LARGE_INTEGER g_perf_freq{};
LARGE_INTEGER g_perf_start{};

double now_s() noexcept
{
    LARGE_INTEGER now{};
    QueryPerformanceCounter(&now);
    return static_cast<double>(now.QuadPart - g_perf_start.QuadPart) /
           static_cast<double>(g_perf_freq.QuadPart);
}

void log_line(const char *fmt, ...) noexcept
{
    fprintf(stdout, "[%9.3fs] ", now_s());
    va_list args{};
    va_start(args, fmt);
    vfprintf(stdout, fmt, args);
    va_end(args);
    fputc('\n', stdout);
    fflush(stdout);
}

// Prints "<what> failed, GetLastError()=<code> (<verbatim FormatMessage text>)".
void print_gle(const char *what, DWORD gle) noexcept
{
    LPWSTR buffer = nullptr;
    const DWORD chars = FormatMessageW(
        FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
        nullptr, gle, MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT),
        reinterpret_cast<LPWSTR>(&buffer), 0, nullptr);
    DWORD length = chars;
    while (length > 0 && (buffer[length - 1] == L'\r' || buffer[length - 1] == L'\n' ||
                          buffer[length - 1] == L' ')) {
        --length;
    }
    fprintf(stderr, "[%9.3fs] %s failed, GetLastError()=%lu", now_s(), what,
            static_cast<unsigned long>(gle));
    if (buffer != nullptr && length > 0) {
        fprintf(stderr, " (");
        for (DWORD i = 0; i < length; ++i) fputc(buffer[i] < 128 ? static_cast<char>(buffer[i]) : '?', stderr);
        fputc(')', stderr);
    }
    fputc('\n', stderr);
    if (buffer != nullptr) LocalFree(buffer);
    fflush(stderr);
}

std::wstring to_wide(const std::string &text)
{
    if (text.empty()) return {};
    const int size = MultiByteToWideChar(CP_UTF8, 0, text.c_str(), static_cast<int>(text.size()), nullptr, 0);
    std::wstring wide(static_cast<std::size_t>(size > 0 ? size : 0), L'\0');
    if (size > 0) {
        MultiByteToWideChar(CP_UTF8, 0, text.c_str(), static_cast<int>(text.size()), wide.data(), size);
    }
    return wide;
}

struct Options final {
    std::string pipe_name;
    std::string file_path;
    unsigned long long loop = ULLONG_MAX; // forever unless --loop N
    double fps_scale = 1.0;
    unsigned chunk = 1316;
    unsigned long long bitrate = 0;       // 0 = derive from chunk + 3 ms period
    unsigned open_timeout_secs = 60;
};

bool parse_args(int argc, char **argv, Options &options)
{
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        auto value = [&]() -> const char * {
            if (i + 1 >= argc) return nullptr;
            return argv[i + 1];
        };
        auto consume = [&](unsigned long long &out) -> bool {
            const char *v = value();
            if (v == nullptr) return false;
            out = _strtoui64(v, nullptr, 0);
            ++i;
            return true;
        };
        if (arg == "--pipe" && value() != nullptr) {
            options.pipe_name = value();
            ++i;
        } else if (arg == "--file" && value() != nullptr) {
            options.file_path = value();
            ++i;
        } else if (arg == "--loop" && value() != nullptr) {
            if (_stricmp(value(), "forever") == 0) {
                options.loop = ULLONG_MAX;
                ++i;
            } else if (!consume(options.loop)) {
                return false;
            }
        } else if (arg == "--fps-scale" && value() != nullptr) {
            options.fps_scale = atof(value());
            ++i;
        } else if (arg == "--chunk") {
            unsigned long long parsed = 0;
            if (!consume(parsed)) return false;
            options.chunk = static_cast<unsigned>(parsed);
        } else if (arg == "--bitrate") {
            if (!consume(options.bitrate)) return false;
        } else if (arg == "--open-timeout-secs") {
            unsigned long long parsed = 0;
            if (!consume(parsed)) return false;
            options.open_timeout_secs = static_cast<unsigned>(parsed);
        } else {
            fprintf(stderr, "unknown or incomplete arg: %s\n", arg.c_str());
            return false;
        }
    }
    return !options.pipe_name.empty() && !options.file_path.empty();
}

bool read_file(const std::wstring &path, std::vector<std::uint8_t> &data)
{
    FILE *file = _wfopen(path.c_str(), L"rb");
    if (file == nullptr) return false;
    _fseeki64(file, 0, SEEK_END);
    const __int64 size = _ftelli64(file);
    _fseeki64(file, 0, SEEK_SET);
    if (size <= 0) {
        fclose(file);
        return size == 0;
    }
    data.resize(static_cast<std::size_t>(size));
    const size_t got = fread(data.data(), 1, data.size(), file);
    fclose(file);
    return got == data.size();
}

// Opens the pipe as a write-only client. Retries within the budget:
// ERROR_PIPE_BUSY -> WaitNamedPipe(1 s); anything else (typically
// ERROR_FILE_NOT_FOUND before the OBS source creates the instance) -> 250 ms
// backoff. Errors are printed verbatim but throttled to avoid log spam.
HANDLE open_pipe(const std::wstring &name, unsigned timeout_secs)
{
    const ULONGLONG deadline_ms = GetTickCount64() + static_cast<ULONGLONG>(timeout_secs) * 1000ULL;
    DWORD last_gle = 0;
    double last_print = -10.0;
    for (;;) {
        const HANDLE handle = CreateFileW(
            name.c_str(), GENERIC_WRITE, 0, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (handle != INVALID_HANDLE_VALUE) return handle;
        const DWORD gle = GetLastError();
        const double t = now_s();
        if (gle != last_gle || t - last_print >= 2.0) {
            print_gle("CreateFile(GENERIC_WRITE)", gle);
            last_gle = gle;
            last_print = t;
        }
        if (GetTickCount64() >= deadline_ms) return INVALID_HANDLE_VALUE;
        if (gle == ERROR_PIPE_BUSY) {
            WaitNamedPipeW(name.c_str(), 1000);
        } else {
            Sleep(250);
        }
    }
}

volatile bool g_quit = false;

BOOL WINAPI console_ctrl_handler(DWORD type) noexcept
{
    (void)type;
    g_quit = true;
    return TRUE;
}

int run(const Options &options)
{
    std::vector<std::uint8_t> data;
    if (!read_file(to_wide(options.file_path), data)) {
        fprintf(stderr, "ERROR: cannot read input file: %s\n", options.file_path.c_str());
        return 2;
    }
    if (data.empty()) {
        fprintf(stderr, "ERROR: input file is empty: %s\n", options.file_path.c_str());
        return 2;
    }
    if (data.size() % 188 != 0) {
        log_line("WARNING: file size %llu is not a multiple of 188",
                 static_cast<unsigned long long>(data.size()));
    }

    const double base_rate_bps =
        options.bitrate != 0
            ? static_cast<double>(options.bitrate)
            : static_cast<double>(options.chunk) * 8.0 / 0.003; // chunk per ~3 ms
    const double rate_bps = base_rate_bps * options.fps_scale;

    SetConsoleCtrlHandler(console_ctrl_handler, TRUE);
    timeBeginPeriod(1);

    log_line("inject start: pipe=%s file=%s bytes=%llu chunk=%u rate=%.0f bps (%.2f Mbps) loop=%s fps_scale=%.3f",
             options.pipe_name.c_str(), options.file_path.c_str(),
             static_cast<unsigned long long>(data.size()), options.chunk, rate_bps,
             rate_bps / 1.0e6,
             options.loop == ULLONG_MAX ? "forever"
                                        : std::to_string(options.loop).c_str(),
             options.fps_scale);

    const std::wstring pipe_name = to_wide(options.pipe_name);
    HANDLE handle = open_pipe(pipe_name, options.open_timeout_secs);
    if (handle == INVALID_HANDLE_VALUE) {
        print_gle("CreateFile(GENERIC_WRITE)", GetLastError());
        log_line("FATAL: could not open pipe within %u s", options.open_timeout_secs);
        timeEndPeriod(1);
        return 3;
    }
    log_line("CONNECTED to pipe");

    // Pacing schedule: target time for the next byte is
    // schedule_origin + scheduled_bytes * 8 / rate + paused_accum.
    double schedule_origin = now_s();
    unsigned long long scheduled_bytes = 0;
    double paused_accum = 0.0;
    double pause_started = 0.0;
    bool paused = false;

    unsigned long long total_bytes = 0;
    unsigned long long passes_done = 0;
    double last_progress = now_s();

    while (!g_quit && passes_done < options.loop) {
        bool reconnect_requested = false;
        bool pipe_broken = false;
        size_t offset = 0;

        while (offset < data.size() && !g_quit && !reconnect_requested && !pipe_broken) {
            while (_kbhit()) {
                const int key = _getch();
                if (key == 'q' || key == 'Q' || key == 27) {
                    g_quit = true;
                } else if (key == 'p' || key == 'P') {
                    if (paused) {
                        const double paused_for = now_s() - pause_started;
                        paused_accum += paused_for;
                        paused = false;
                        log_line("RESUMED after %.3f s pause (schedule shifted, no burst)", paused_for);
                    } else {
                        pause_started = now_s();
                        paused = true;
                        log_line("PAUSED (handle held open, no bytes flowing)");
                    }
                } else if (key == 'r' || key == 'R') {
                    reconnect_requested = true;
                }
            }
            if (g_quit || reconnect_requested) break;
            if (paused) {
                Sleep(30);
                continue;
            }

            const double target =
                schedule_origin +
                static_cast<double>(scheduled_bytes) * 8.0 / rate_bps +
                paused_accum;
            for (;;) {
                const double remain = target - now_s();
                if (remain <= 0.0) break;
                if (remain > 0.002) {
                    Sleep(static_cast<DWORD>((remain - 0.0015) * 1000.0));
                } else {
                    Sleep(0);
                }
            }

            const size_t take = options.chunk < data.size() - offset
                                    ? options.chunk
                                    : data.size() - offset;
            DWORD written = 0;
            if (WriteFile(handle, data.data() + offset, static_cast<DWORD>(take), &written, nullptr) == FALSE ||
                written == 0) {
                print_gle("WriteFile", GetLastError());
                pipe_broken = true;
                break;
            }
            offset += written;
            scheduled_bytes += written;
            total_bytes += written;

            const double t = now_s();
            if (t - last_progress >= 1.0) {
                last_progress = t;
                log_line("progress: passes=%llu total_bytes=%llu pass_offset=%zu/%zu state=%s",
                         passes_done, total_bytes, offset, data.size(),
                         paused ? "paused" : "running");
            }
        }

        if (g_quit) break;

        if (pipe_broken || reconnect_requested) {
            log_line("%s; closing client handle and re-opening pipe (budget %u s), restarting file from byte 0",
                     pipe_broken ? "PIPE BROKEN (server closed/recreated instance)"
                                 : "RECONNECT requested by keypress",
                     options.open_timeout_secs);
            CloseHandle(handle);
            handle = open_pipe(pipe_name, options.open_timeout_secs);
            if (handle == INVALID_HANDLE_VALUE) {
                print_gle("CreateFile(GENERIC_WRITE)", GetLastError());
                log_line("FATAL: reconnect failed within %u s", options.open_timeout_secs);
                break;
            }
            log_line("RECONNECTED");
            schedule_origin = now_s();
            scheduled_bytes = 0;
            paused_accum = 0.0;
            paused = false;
            continue;
        }

        ++passes_done;
        log_line("pass %llu complete (%llu bytes)", passes_done,
                 static_cast<unsigned long long>(data.size()));
    }

    CloseHandle(handle);
    const double duration = now_s();
    log_line("done: passes=%llu total_bytes=%llu wall=%.1fs avg=%.0f kbps",
             passes_done, total_bytes, duration,
             duration > 0.0 ? static_cast<double>(total_bytes) * 8.0 / 1000.0 / duration : 0.0);
    timeEndPeriod(1);
    return 0;
}

} // namespace

int main(int argc, char **argv)
{
    QueryPerformanceFrequency(&g_perf_freq);
    QueryPerformanceCounter(&g_perf_start);

    Options options{};
    if (!parse_args(argc, argv, options)) {
        fprintf(stderr,
                "usage: inject.exe --pipe <\\\\.\\pipe\\name> --file <file.ts>\n"
                "                  [--loop N|forever] [--fps-scale X] [--chunk N]\n"
                "                  [--bitrate N] [--open-timeout-secs N]\n"
                "keys: p=pause/resume  r=reconnect  q/Esc=quit\n");
        return 2;
    }
    if (options.chunk == 0 || options.chunk > 4U * 1024U * 1024U) {
        fprintf(stderr, "invalid --chunk\n");
        return 2;
    }
    if (options.fps_scale <= 0.0) {
        fprintf(stderr, "invalid --fps-scale (must be > 0)\n");
        return 2;
    }
    return run(options);
}
