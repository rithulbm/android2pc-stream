# Local Camera Receiver for OBS Studio

This directory contains the native Windows half of Local Camera Sender. It is
an OBS input source backed by a private static SRT 1.5.6 + Botan 3.12.0 stack,
plus a small native pairing app and an Inno Setup installer.

The supported production runtime target is **OBS Studio 32.2.2 x64 or newer on
Windows 10/11**. The installer enforces only that minimum version; it has no
maximum-version gate, so future OBS releases are permitted instead of being
blocked or mistaken for an older unsupported build. The source uses OBS's
private built-in `ffmpeg_source` for MPEG-TS demux/decode while keeping module
registration, source ownership, rendering, and audio activation on public
libobs APIs.

## What gets installed

`LocalCameraReceiverSetup.exe` installs this bundle at:

```text
%ProgramData%\obs-studio\plugins\local-camera-receiver\
  bin\64bit\local-camera-receiver.dll
  bin\64bit\LocalCameraReceiver.exe
  data\manifest.json
  data\locale\en-US.ini
  licenses\...
```

It also installs/repairs the Microsoft Visual C++ x64 runtime and creates a
Start-menu shortcut named **Pair this PC**. By default it adds a machine-wide
startup shortcut that launches the helper hidden in the notification area; the
installer task can be cleared if that is not wanted. The selected firewall task adds one
inbound rule constrained to:

- the detected `obs64.exe` only;
- UDP local port 9000 only;
- Windows Private network profiles only;
- remote addresses in `LocalSubnet` only.

Uninstall removes the plugin, startup/Start-menu shortcuts, and that exact firewall rule. The
current user's encrypted pairing remains under the OBS plugin-config directory
so an upgrade does not unexpectedly unpair the phone.

## Pairing and streaming

1. In OBS choose **Sources + → Local Camera Receiver** and open its properties.
2. Choose **Show pairing QR**. OBS contacts the already-running helper through a
   current-user-only local control pipe, so no second helper or repeated setup is needed.
3. If this is the first setup, choose the private Ethernet or Wi-Fi adapter. The
   app never offers public, loopback, multicast, broadcast, unspecified, DNS,
   or non-canonical hosts.
4. Scan the QR in Local Camera Sender within 10 minutes. The phone requests any
   missing permissions and starts streaming automatically after a valid scan.
5. Choose **Finish & run in background**. OBS receives the source immediately
   and lists the authenticated phone model in the source properties.

Closing the window hides the helper to the notification area. Its menu can open
the receiver window, open the detected OBS installation, or exit the helper.
Settings survive restarts through current-user DPAPI; QR data remains in memory
only and is cleared on expiry or hide. Local system sounds confirm QR readiness
and a successful finish without loading media assets or using a network service.

The pairing payload is never written to a QR image, browser, clipboard, log, or
cloud service. The native window paints its QR modules directly from bounded
in-memory data. Credentials last at most one year; QR import lasts ten minutes.

## Security boundary

- Android is always the SRT caller; this plugin is the listener.
- The listener binds one selected canonical private IPv4 address, not `0.0.0.0`.
- Accepted peers must also have a canonical private IPv4 address.
- SRT preview AEAD mode 2 (AES-256-GCM), 32-byte keys, enforced encryption,
  SRT >=1.5.6, and secured sender/receiver key states are mandatory.
- Plaintext and AES-CTR mode 1 are rejected; there is no silent downgrade.
- Only non-empty, bounded groups of complete 188-byte MPEG-TS packets with
  valid sync bytes enter the named pipe.
- The media pipe rejects remote clients, uses a current-user-only security descriptor,
  and has bounded packet/byte queues with cancellation and backpressure.
- The helper control/status pipe uses the current Windows SID in its name,
  rejects remote clients, applies a current-user-only DACL, and accepts only
  bounded protocol messages for showing the QR or propagating receiver state.
  No pairing secret crosses this control/status pipe.
- A sanitized phone model label is accepted for display only after AES-GCM key
  states are secured. It is bounded to 48 safe ASCII characters and contains no
  serial number, account, or advertising identifier.
- OBS's generic `srt.dll` is neither imported nor replaced.

The media path is:

```text
phone → authenticated SRT/AES-GCM listener → private local named pipe
      → OBS private FFmpeg MPEG-TS child → composite OBS video/audio source
```

## Build

The checked-in sources pin SRT/Botan with the Android sender and vendor the
MIT QR generator. For reproducible compilation, the bootstrap script currently
fetches and hash-verifies the OBS 32.2.1 source headers; this is a build-time
header baseline only and is not an installed-OBS version restriction. Runtime
installation accepts OBS 32.2.2 and all newer versions. OBS binaries are never
bundled.

From an x64 PowerShell prompt:

```powershell
Set-Location U:\mobile-pc-streaming
.\pc\scripts\build-windows.ps1
```

Required developer tools are Visual Studio 2022 Build Tools with MSVC v143 and
Windows SDK 10.0.26100, CMake >=3.30, Python 3, and Inno Setup 6. The script
configures a Release build, treats project warnings as errors, runs the native
tests, compiles the installer, and leaves `LocalCameraReceiverSetup.exe` at the
repository root.

The desktop host used during development provided both `Path` and `PATH`
environment entries. `build-windows.ps1` intentionally launches CMake with a
single case-insensitive path entry so MSBuild does not fail before invoking
MSVC.

## Validation boundary

Native unit tests cover configuration validation, QR/credential expiry,
URI encoding, strict binary parsing, random generation, DPAPI round-trips and
tamper rejection, queue bounds/backpressure/cancellation, adapter ranking and
saved-virtual-adapter migration, device stream-ID validation, bounded helper
control/status messages, live status copy, and the OBS source output contract.
The pairing GUI is also exercised as a real Windows app to confirm physical
Wi-Fi selection, DPAPI save, in-memory QR rendering, saved settings, and its
tray/finish path.

The original 0.1.0 installation exposed a precise OBS load error because a
composite source incorrectly advertised direct audio output. Version 0.2.0
removes that invalid flag and keeps synchronized audio through the active private
FFmpeg child. The checked-in installed-plugin diagnostic parses the latest OBS
log and fails if the module, registration, or source identity is missing.

The real 0.2.0 installer upgrade, installed helper IPC, OBS 32.2.1 module/source
discovery, and private-interface UDP 9000 bind were verified on the original
target Windows PC. The installer now treats 32.2.2 as the minimum runtime and
permits newer OBS versions without an upper-bound block. Physical-phone
acceptance still requires authenticated AES-GCM transport, live video/audio
sync, reconnect, wrong-secret/downgrade rejection, and long-run testing on each
intended phone and network. Do not treat an emulator build or QR screenshot as
proof of those hardware-dependent gates.

Protocol details remain the same as the mobile v1 contract: caller/listener,
pairing URI fields, pinned PIDs, MPEG-TS timing, and downgrade rules are covered
by the authoritative notes in `docs/` and the Android tests.
