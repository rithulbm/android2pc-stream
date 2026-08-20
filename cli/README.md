# local-camera-receiver

> Zero-friction installer and launcher CLI for **Local Camera Receiver** (OBS Studio plugin on Windows).

Turn your Android phone into a secure, encrypted local camera for OBS Studio over Wi-Fi.

[![npm version](https://img.shields.io/npm/v/local-camera-receiver.svg)](https://www.npmjs.com/package/local-camera-receiver)
[![Platform](https://img.shields.io/badge/Platform-Windows%2010%20%2F%2011%20(64--bit)-0078D4?logo=windows11)](https://github.com/rithulbm/android2pc-stream)
[![License](https://img.shields.io/badge/license-GPL--2.0--or--later-blue)](https://github.com/rithulbm/android2pc-stream/blob/main/LICENSE)

## Quick Start

Run Local Camera Receiver directly with **`npx`** without installing it globally:

```bash
npx local-camera-receiver
```

Or install it globally:

```bash
npm install -g local-camera-receiver
local-camera-receiver
```

## How It Works

1. Downloads the immutable Windows installer pinned to this CLI package version.
2. Validates its cryptographic integrity with the package's pinned **SHA-256** before execution.
3. Caches the verified executable in `%LOCALAPPDATA%\local-camera-receiver\bin` for later launches.
4. Launches the setup wizard or pairing helper for your Android phone.

The npm package intentionally does **not** follow the mutable repository-root installer. That keeps an already-published CLI release reproducible: its URL and SHA-256 continue to identify the same installer even after newer `main` builds are published.

## CLI Options

```text
Usage:
  npx local-camera-receiver [options] [-- <extra-args>]

Options:
  -h, --help            Show help documentation
  -v, --version         Show package and binary version
  -s, --silent          Run installer in silent / unattended mode
      --download-only   Download and verify binary without launching
      --clean           Delete cached binary from disk
      --force-download  Re-download the binary even if already cached
      --show-qr         Launch the pairing QR code helper directly
      --custom-url <url> Download from a custom binary URL
```

### Examples

```bash
# Launch setup wizard
npx local-camera-receiver

# Silent / unattended install
npx local-camera-receiver --silent

# Open the pairing helper
npx local-camera-receiver --show-qr
```

## Requirements

- **Windows:** Windows 10 or 11 x64
- **OBS Studio:** exactly OBS Studio 32.2.1 x64 for this receiver build
- **Node.js:** Node.js 18.0.0 or newer
- **Android Device:** Android 10+ with the [canonical Local Camera Sender APK](../LocalCameraSender.apk)

## Current Development Builds

The latest CI-validated APK and Windows installer built from `main` are kept at the [repository root](../README.md#download). Those root files may be newer than the immutable installer pinned by a previously published npm package.

## Repository & Source Code

Full source code, Android APK, and C++ OBS plugin source are in the [android2pc-stream repository](https://github.com/rithulbm/android2pc-stream).

## License

GPL-2.0-or-later © rithulbm
