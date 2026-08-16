# local-camera-receiver

> Zero-friction installer and launcher CLI for **Local Camera Receiver** (OBS Studio plugin on Windows).

Turn your Android phone into a secure, encrypted local camera for OBS Studio over Wi-Fi.

[![npm version](https://img.shields.io/npm/v/local-camera-receiver.svg)](https://www.npmjs.com/package/local-camera-receiver)
[![Platform](https://img.shields.io/badge/Platform-Windows%2010%20%2F%2011%20(64--bit)-0078D4?logo=windows11)](https://github.com/rithulbm/android2pc-stream)
[![License](https://img.shields.io/badge/license-GPL--2.0--or--later-blue)](https://github.com/rithulbm/android2pc-stream/blob/main/LICENSE)

---

## Quick Start

You can run Local Camera Receiver directly with **`npx`** without installing anything globally:

```bash
npx local-camera-receiver
```

Or install it globally using **`npm`**:

```bash
npm install -g local-camera-receiver
local-camera-receiver
```

---

## How It Works

1. Automatically downloads the latest verified Windows receiver binary (`LocalCameraReceiverSetup.exe`).
2. Validates cryptographic integrity via **SHA-256 checksum** before execution.
3. Caches the verified executable in `%LOCALAPPDATA%\local-camera-receiver` for instant subsequent launches.
4. Launches the setup wizard or pairing helper to connect with your Android phone.

---

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

#### Launch Setup Wizard:
```bash
npx local-camera-receiver
```

#### Run Silent / Unattended Installation:
```bash
npx local-camera-receiver --silent
```

#### Show Pairing QR Code:
```bash
npx local-camera-receiver --show-qr
```

---

## Requirements

* **Windows:** Windows 10 or 11 (64-bit)
* **OBS Studio:** OBS Studio 32.2.1+
* **Node.js:** Node.js 18.0.0 or newer
* **Android Device:** Android 10+ with [Local Camera Sender APK](https://github.com/rithulbm/android2pc-stream/releases)

---

## Repository & Source Code

Full source code, Android APK, and C++ OBS plugin source:  
👉 [https://github.com/rithulbm/android2pc-stream](https://github.com/rithulbm/android2pc-stream)

---

## License

GPL-2.0-or-later © rithulbm
