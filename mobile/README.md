# Android sender

## Toolchain

- Android Gradle Plugin 9.3.1
- Gradle 9.5.0
- AGP built-in Kotlin with resolved Kotlin stdlib 2.2.10
- JDK 17 (Temurin 17.0.20 used for the verified local build)
- compile/target SDK 37.0 (Android 17)
- minimum SDK 29 (Android 10)
- Android NDK r29 (`29.0.14206865`)
- CMake 3.31.6

API 29 is the minimum because it provides the typed camera/microphone foreground-service foundation, thermal status APIs, modern Camera2/MediaCodec behavior, AES-GCM Android Keystore support, and the NDK baseline needed here. A lower minimum would add substantial lifecycle and capability branches without making sustained hardware HEVC streaming broadly reliable.

## Build

The checked-in wrapper is the supported build entry point:

```powershell
$env:JAVA_HOME='U:\mobile-pc-streaming\.toolchains\jdk-17.0.20+8'
$env:ANDROID_HOME='U:\mobile-pc-streaming\.android-sdk'
$env:ANDROID_AVD_HOME='U:\mobile-pc-streaming\.android-avd'
$env:GRADLE_USER_HOME='U:\mobile-pc-streaming\.gradle-user'
Set-Location U:\mobile-pc-streaming\mobile
./gradlew.bat --no-configuration-cache clean testDebugUnitTest lintDebug connectedDebugAndroidTest assembleDebug assembleRelease
```

This exact clean gate was verified on the bundled API 37 emulator. It runs 43
JVM tests, strict Android lint, 7 device tests, native builds for both ABIs, the
debug APK, and the minified unsigned release APK.

Primary installable handoff: `../LocalCameraSender.apk`. Successful `main` CI
builds align the minified release APK, sign it with the stable development-only
CI identity in [`signing/`](./signing/), verify the signer certificate and APK,
and then refresh the repository-root handoff only after the full CI gate passes.
The development signing key is intentionally public and is never a production
trust identity. A private production signing key must replace it before store or
production publication.

Gradle unsigned release output: `app/build/outputs/apk/release/app-release-unsigned.apk`

Gradle debug APK: `app/build/outputs/apk/debug/app-debug.apk`

For a release configuration check without production signing:

```powershell
./gradlew.bat test lint assembleRelease
```

The Gradle release output remains unsigned until a signing step is applied; the
canonical root APK gets that signing step in GitHub Actions.

## Run

1. Install the canonical `../LocalCameraSender.apk`, or use the debug APK for local development. If you previously installed a build signed by a different local development certificate, Android may require one uninstall before moving to the stable CI-signed APKs.
2. Open the app and scan a protocol-v1 pairing QR code generated from the contract in `../pc/README.md`.
3. Grant camera, microphone (enabled by default), and notification permission
   when asked. Android 17 also asks for local-network access; Android 10–16 do
   not need that separate permission.
4. After a successful scan, the app requests any missing permissions and starts
   streaming automatically. The same button remains available for later manual
   stop/start use without scanning again.

The QR camera preview chooses a supported shared camera size, preserves its
aspect ratio, follows display/sensor rotation (including 180-degree changes),
and mirrors a front camera. Pair, start, stop, and failure actions use short
on-device sound cues; no sound file, analytics event, or cloud request is used.
The sender also provides OBS a sanitized, bounded phone model label—never a
serial number, account, advertising ID, or other unique device identifier.

## Known limitations

- The 0.2.0 Windows installer, plugin discovery, source restoration, helper
  control pipe, and UDP 9000 listener were originally verified on an OBS 32.2.1
  target PC. The current installer requires OBS 32.2.2 or newer and deliberately
  has no upper-version block, so future OBS releases are permitted.
- Emulator and static tests cannot prove screen-off/lock survival, 4K60 hardware encoding, thermal stability, OEM battery handling, Wi-Fi roaming, or two-hour sustained streaming.
- The preview geometry is unit-tested, but real rear/front camera optics,
  autofocus, exposure, rotation, and microphone A/V sync require a physical
  phone test; an emulator is not evidence for them.
- The first protocol version accepts a private IPv4 receiver address only. IPv6 and hostname pairing are deliberately deferred to avoid ambiguous or public routing.
- HEVC is preferred; H.264 is selected only when the chosen camera/profile has no usable hardware HEVC encoder.
