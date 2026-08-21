# inject.exe — feeding known-good MPEG-TS straight into the OBS plugin's pipe

Purpose: prove or disprove the **OBS side** of the pipeline **without a phone**.
The muxer is already exonerated (`../muxer-repro/RESULTS.md`). This tool plays a
known-good `.ts` fixture into the exact named pipe our OBS plugin creates,
bypassing SRT, Android, and pairing entirely.

```
Android ──SRT──> SrtListener ──> NamedPipeSink ──> [\\.\pipe\local-camera-receiver-<uuid>] ──> OBS ffmpeg_source
                                                  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                                                  inject.exe writes HERE as a pipe client
```

If video appears with inject, everything from the pipe inward works and any
remaining fault is upstream (sender/SRT/pairing). If it stays black, we have a
reproducible PC-side failure plus the OBS log lines that explain it.

## Files

| file            | role |
|-----------------|------|
| `inject.cpp`    | pipe **client**: opens the plugin's pipe `GENERIC_WRITE`, streams the fixture in 1316-byte chunks paced at ~3.5 Mbps |
| `pipe_reader.cpp` | throwaway **inbound server** used only to smoke-test inject without OBS (mirrors product DACL/attributes) |
| `build.ps1`     | builds both with WinLibs GCC 15.2 (`$env:WINLIBS_GPP` overrides) |

## Build

```powershell
cd U:\mobile-pc-streaming\analysis\repros\inject
powershell -ExecutionPolicy Bypass -File build.ps1
```

Manual equivalent (WinLibs g++, same one muxer-repro uses — the old
`C:\MinGW` 6.3.0 also compiles these C++17 sources but is not recommended):

```powershell
& "C:\Users\admin\AppData\Local\Microsoft\WinGet\Packages\BrechtSanders.WinLibs.POSIX.UCRT_Microsoft.Winget.Source_8wekyb3d8bbwe\mingw64\bin\g++.exe" `
    -std=c++17 -O2 -Wall -Wextra -static -static-libgcc -static-libstdc++ `
    inject.cpp -o inject.exe -lwinmm
```

## Usage

```
inject.exe --pipe <\\.\pipe\name> --file <file.ts>
           [--loop N|forever] [--fps-scale X] [--chunk N]
           [--bitrate N] [--open-timeout-secs N]
```

| arg | default | meaning |
|-----|---------|---------|
| `--pipe` | required | full pipe name, e.g. `\\.\pipe\local-camera-receiver-<uuid>` |
| `--file` | required | MPEG-TS fixture (`..\..\muxer-repro\muxed_avc.ts` etc.) |
| `--loop` | `forever` | passes over the file; `forever` loops until keypress |
| `--fps-scale` | `1.0` | rate multiplier; 2 = twice as fast, 0.5 = half speed |
| `--chunk` | `1316` | write size (matches the real sender's SRT payload size) |
| `--bitrate` | derived | override base rate; default = chunk per ~3 ms ≈ 3.51 Mbps |
| `--open-timeout-secs` | `60` | client-open retry budget before giving up (exit code 3) |

Pacing defaults to one 1316-byte chunk every ~3 ms ⇒ ~3.51 Mbps, close to the
real sender bitrate, so OBS buffering behaves realistically.

Keys while running: **p** pause/resume (handle held open), **r** force
disconnect+reconnect, **q**/Esc quit. Win32 errors are printed verbatim
(`GetLastError()=<code> (<FormatMessage text>)`). Exit codes: 0 ok, 2 usage/file
error, 3 pipe-open timeout.

---

## Step 1 — add the Local Camera Receiver source in OBS

1. Start OBS with the plugin installed (`pc/build` output copied into the OBS
   plugin directory, per the main README).
2. Sources → **+** → **Local Camera Receiver** → OK → OK.
   The source creates its pipe immediately on creation
   (`ReceiverSource` ctor → `NamedPipeSink::start()`).
3. Leave the source visible in the preview so it activates.

Note: the source properties will keep saying *waiting for pairing* — expected
and irrelevant here; we bypass the SRT listener completely.

## Step 2 — read `pipe_id` from the scenes JSON

The UUID lives in the current scene collection:

```powershell
Get-ChildItem "$env:APPDATA\obs-studio\basic\scenes" -Filter *.json | ForEach-Object {
    $j = Get-Content $_.FullName -Raw | ConvertFrom-Json
    foreach ($s in $j.sources) {
        if ($s.id -eq 'local_camera_receiver_source') {
            "{0}: pipe_id={1}" -f $_.Name, $s.settings.pipe_id
        }
    }
}
```

(If the source sits inside a group, also walk `$s.sources` recursively.)
The pipe name is then:

```
\\.\pipe\local-camera-receiver-<pipe_id>
```

## Step 3 — run inject with the AVC fixture

```powershell
cd U:\mobile-pc-streaming\analysis\repros\inject
.\inject.exe --pipe "\\.\pipe\local-camera-receiver-<pipe_id>" `
             --file ..\..\muxer-repro\muxed_avc.ts --loop forever
```

Watch the OBS preview. The fixture starts with PAT/PMT + IDR and re-emits them
every keyframe (~1 s), so a joining demuxer syncs within ~1 s worst case.

### Expected outcomes matrix

| observation | verdict |
|---|---|
| Color bars appear in ≤2 s and stay stable while inject loops | **PC/OBS integration OK.** Pipe, ffmpeg_source, mpegts demux, decode, render all work. Blame Android/sender (SRT auth, muxer input, reconnect logic). |
| Video appears, later stutters/recovers after `r` reconnects | Integration OK; reconnect recovery works (matches muxer-repro late-join result). |
| Source stays black; inject prints nothing after `CONNECTED` | Data is flowing; suspect decode/render. Capture OBS log (Help → Log Files → View Current Log) and match lines in the troubleshooting table below. |
| inject exits 3 after printing `GetLastError()=2 (The system cannot find the file specified.)` for 60 s | No pipe instance with that name exists: wrong `pipe_id`, source destroyed (hidden + `listen_when_hidden` off), or plugin not loaded. Re-check step 2. |
| inject prints `GetLastError()=5 (Access is denied.)` | Pipe exists but this process runs as another user/elevated context than OBS (same-user DACL mismatch). Run inject unelevated as the same user. |
| inject prints `GetLastError()=231 (All pipe instances are busy.)` repeatedly | Another client holds the single instance (a second inject, or a stale reader). Only one writer is allowed — by design. |
| OBS log shows `MP:` lines but no crash, inject keeps writing | See troubleshooting table; usually probe/stream-info timing. |

Always capture the OBS log afterwards: **Help → Log Files → View Current Log**
and note which `MP:` / `[Media Source ...]` lines appear.

## Step 4 — repeat with HEVC

```
.\inject.exe --pipe "\\.\pipe\local-camera-receiver-<pipe_id>" --file ..\..\muxer-repro\muxed_hevc.ts --loop forever
```

Same expectations as AVC. Notes:
- The source migrates itself to **software decoding** by default
  (`software_decoder_migration_v1`); HEVC software decode via FFmpeg is the
  reliable path. If HEVC fails while AVC succeeds, try toggling
  "Use hardware decoding" in the source properties and compare logs
  (`is_hw_decoding` in the `settings:` dump line).
- Also worth one pass with `muxed_avc_audio.ts` to exercise the audio_render
  path of the source.

## Step 5 — variant: start inject BEFORE adding the source

Tests the open-before-server race (client arrives before/at the instant the
server instance exists):

1. Do **not** create the source yet. Start inject first:
   ```
   .\inject.exe --pipe "\\.\pipe\local-camera-receiver-<pipe_id-you-expect>" --file ..\..\muxer-repro\muxed_avc.ts --loop forever
   ```
   It prints verbatim `GetLastError()=2` retries (throttled) and waits up to
   60 s (`--open-timeout-secs` shortens this for quick tests).
2. Now add the Local Camera Receiver source in OBS.
3. Expected: inject logs `CONNECTED` within ~1 s of source creation and video
   appears. This validates `NamedPipeSink`'s `ConnectNamedPipe`
   `ERROR_PIPE_CONNECTED` race handling (`pc/src/named_pipe_sink.cpp:152`).

To get the UUID before the source exists you cannot read scenes.json yet —
instead create the source once, note the id, delete the source, and reuse the
id (OBS persists deleted-source ids only if you duplicate first; simplest is:
create source, grab id from json, remove source, start inject with that id,
re-add source — OBS regenerates a NEW uuid on re-add, so instead use variant B:
create the source, let inject connect, then kill and restart OBS with inject
already running against the saved id).

## Step 6 — variant: pause/resume and forced reconnects

With inject running and video showing:

1. Press **p** (pause): inject holds the handle open but sends nothing.
   Expected: frame freezes; no errors in OBS log (1 MB pipe buffer + OBS
   buffering absorb it). After >~30 s paused, resuming mid-GOP still recovers
   at the next IDR (~≤1 s) because the fixture repeats PAT/PMT+IDR.
2. Press **p** again (resume): motion continues within ~1 s. No reconnect
   should be logged — the handle never dropped.
3. Press **r** (hard reconnect): inject drops the handle and reopens.
   Expected OBS log sequence:
   ```
   [Media Source 'Local Camera Receiver media'] Disconnected. Reconnecting...
   [Media Source 'Local Camera Receiver media'] Reconnected.
   ```
   Video returns within ~1–2 s. This emulates `NativeSender::reset_mux_after_disconnect`
   semantics: fresh PAT/PMT + IDR right after reconnect.
4. Quit with **q**: OBS logs `Disconnected. Reconnecting...` and keeps retrying
   every `reconnect_delay_sec=1` — expected noise while no sender exists.

## Troubleshooting — OBS log line → cause

Exact strings from libobs media-playback (`shared/media-playback`) and
`plugins/obs-ffmpeg/obs-ffmpeg-source.c` (OBS 32.2.2):

| OBS log line | meaning | likely cause when using inject |
|---|---|---|
| `[Media Source 'Local Camera Receiver media'] settings:` dump with `input: \\.\pipe\local-camera-receiver-...` | source configured | Verify this name equals what you passed to `--pipe`. Mismatch ⇒ black + step-3 timeout symptoms. |
| `MP: Failed to open media: '\\.\pipe\...'` | `avformat_open_input` failed | Pipe vanished between listing and open (source being recreated); or pipe_id typo. Retry; check inject is running. |
| `MP: Unable to find input format for '...'` | demuxer probe found no mpegts | Nothing readable arrived during probe: inject not started, pacing absurdly slow (`--fps-scale 0.01`), or non-TS file passed. |
| `MP: Failed to find stream info for '...'` | opened but `av_find_stream_info` got nothing usable | Data trickles too slowly for the probe window, or fixture lacks PAT/PMT at start (our fixtures have both — suspect your own replacement file). |
| `MP: Could not initialize audio or video: '...'` | streams found, decoder init failed | Codec genuinely unsupported by the FFmpeg build in OBS; or hw-decode forced on a machine without HEVC HW. Keep software decode. |
| `MP: Failed to find video codec` / `MP: Failed to open video decoder: <err>` | decoder lookup/open failure | Same as above; for HEVC check `is_hw_decoding: yes` in the settings dump and turn it off. |
| `MP: av_read_frame failed: <err> (<code>)` | read error mid-stream | Inject exited/was killed mid-write (expected on q/Ctrl+C), or genuine corruption (should not happen — reader smoke test proves byte fidelity). |
| `[Media Source ...] Disconnected. Reconnecting...` | media ended/read failed; source will reopen the pipe | Normal after inject quits or presses `r`. If looping continuously without r/q, something else closes the pipe — check inject stderr for WriteFile errors. |
| `[Media Source ...] Reconnected.` | first decoded frame after a disconnect | Success marker for the step-6 reconnect test. |
| none of the above, still black | — | Confirm inject actually connected (`CONNECTED to pipe` line) and confirm the source is active (visible in preview, `close_when_inactive=false` set by the plugin). |

## Smoke test (no OBS needed)

Validated 2026-08-21 on this machine (GCC 15.2.0 WinLibs):

```
pipe_reader.exe \\.\pipe\inject-smoke-test --clients=1      # window/log A
inject.exe --pipe \\.\pipe\inject-smoke-test --file ..\..\muxer-repro\muxed_avc.ts --loop 2
```

Result: inject sent 891872 bytes (2 × 445936 exactly); reader received 891872
bytes, 4744 TS packets, **0 sync errors**, effective 3.41–3.45 Mbps vs 3.51
target. Reader sees clean close as `gle=109 ERROR_BROKEN_PIPE`.

Also verified:
- no-server path: verbatim `GetLastError()=2` retries, throttled, exit 3 after budget;
- broken-pipe path: killing the reader yields `GetLastError()=232` on inject,
  which reconnects to a fresh reader in ~17 ms and restarts from byte 0;
- `--fps-scale 20` + HEVC fixture: 367540 bytes = 1955 packets, 0 sync errors
  (partial final chunk handled).

`pipe_reader.exe` mirrors the product's pipe attributes (byte mode, 1 instance,
1 MB buffers, same-user DACL `D:P(A;;GA;;;SID)`) but `PIPE_ACCESS_INBOUND`,
so it accepts inject exactly like `NamedPipeSink` does.
