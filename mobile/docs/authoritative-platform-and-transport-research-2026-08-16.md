# Authoritative platform and transport research — 2026-08-16

This note records implementation facts for the Android camera sender as checked on
2026-08-16. It is not an implementation plan and it is not legal advice. Sources
are first-party Android, Kotlin, Haivision SRT, Botan, MPEG/ITU, OBS, or project
license sources. The FSF license list is used for GNU GPL compatibility guidance.

## Executive conclusions

1. A current, reproducible Android baseline is API 37, JDK 17, NDK r29
   (`29.0.14206865`), and an explicitly pinned SDK CMake. The repository currently
   pins AGP 9.2.1 and CMake 3.31.6; both are reasonable conservative choices, but
   AGP 9.3.1 is the current stable release. Do not update merely to make the version
   number newer without rebuilding the complete native dependency graph.
2. `minSdk = 26` is the lowest clean baseline for Camera2, Keystore AES-GCM,
   Android O foreground-service behavior, and AAudio. The repository's
   `minSdk = 29` is also defensible and materially simplifies modern service and
   Wi-Fi APIs; it is a product-coverage choice, not an NDK constraint.
3. A camera/microphone foreground service may continue after backgrounding,
   screen-off, and ordinary lock if the user starts it while the Activity is
   visible. This is permission to operate, not an uptime guarantee. Force-stop and
   reboot must not silently resume capture, and OEM/thermal/Doze behavior requires
   physical-device proof.
4. On a target-37 app, direct SRT/UDP access to the LAN requires Android 17's
   runtime `ACCESS_LOCAL_NETWORK` permission. `INTERNET` alone is insufficient.
5. SRT 1.5.6 is the minimum acceptable current pin because its release fixes
   encryption-state downgrade and memory-safety defects. The strongest available
   SRT mode is AES-256-GCM, but it remains a preview build option in 1.5.6 and is
   not proven in stock OBS. Mature AES-256-CTR interoperability does **not** provide
   message integrity. A secure implementation must fail closed rather than silently
   downgrade.
6. Strict `GPL-2.0-only` distribution is presently unresolved. Kotlin stdlib,
   AndroidX, ZXing, and Mbed TLS/OpenSSL 3 are Apache-2.0; the FSF says Apache-2.0
   is incompatible with GPLv2. ML Kit has proprietary terms and may contact Google
   or report utilization/performance. libsrt's MPL-2.0 is not the blocker: MPL 2.0
   explicitly supports combining eligible covered files into a GPLv2 Larger Work
   while retaining MPL obligations.

## 1. Current Android build baseline

### Authoritative current versions

- Android 17/API 37 is final. A new app should compile and target the newest API:
  [Android 17 release](https://developer.android.com/blog/posts/android-17-is-here),
  [NDK SDK version guidance](https://developer.android.com/ndk/guides/sdk-versions).
- The current AGP API reference lists **AGP 9.3.1** as current. The 9.3 release
  supports API 37 and requires/defaults Gradle 9.5.0, JDK 17, Build Tools 36.0.0,
  and default NDK 28.2.13676358:
  [AGP API releases](https://developer.android.com/reference/tools/gradle-api),
  [AGP 9.3 release notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes).
- Kotlin's current stable release is **2.4.10**. Kotlin 2.4 bytecode needs AGP/R8
  9.1 or newer, but JetBrains' KGP table only documents full AGP compatibility
  through AGP 9.1.0. Therefore "latest Kotlin plus latest AGP" needs a real sync,
  test, and release build rather than an assumption:
  [Kotlin releases](https://kotlinlang.org/docs/releases.html),
  [Android Kotlin support](https://developer.android.com/build/kotlin-support),
  [KGP compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html).
- AGP 9+ enables built-in Kotlin and automatically adds the Kotlin stdlib unless
  Kotlin is disabled for a module. The `org.jetbrains.kotlin.android` plugin is not
  normally applied:
  [built-in Kotlin migration](https://developer.android.com/build/migrate-to-built-in-kotlin).
- The current stable NDK is **r29**, exact revision `29.0.14206865`; r30 is preview:
  [NDK downloads](https://developer.android.com/ndk/downloads),
  [NDK compatibility](https://github.com/android/ndk/wiki/Compatibility).
- Google's SDK repository currently publishes CMake 4.1.2, while 3.31.6 is a
  mature packaged alternative. Android supports explicit SDK CMake pins and
  recommends version pinning for reproducibility:
  [SDK repository catalog](https://dl.google.com/android/repository/repository2-3.xml),
  [install and configure NDK/CMake](https://developer.android.com/studio/projects/install-ndk).

### Repository reconciliation

At research time, the repository pins `compileSdk = 37`, `targetSdk = 37`, AGP
9.2.1, JDK/JVM 17, NDK r29, and CMake 3.31.6. The corresponding local SDK contains
API 37, Build Tools 37.0.0, NDK r29, and CMake 3.31.6. This is a reasonable
conservative baseline, with one local-install caveat: AGP 9.2 documents Build Tools
36.0.0 as its tested default, but only 37.0.0 was present at inspection time. Do not
set `buildToolsVersion` merely to hide that mismatch. Let the pinned AGP select its
default and install 36.0.0 if the first real build requests it, or retain 37.0.0 only
after the build proves that choice. Record AGP 9.2.1 as an intentional pin rather
than calling it the latest stable:
[AGP 9.2 release notes](https://developer.android.com/build/releases/agp-9-2-0-release-notes).

The build does not explicitly declare a Kotlin compiler version because it uses
AGP built-in Kotlin. The effective compiler and shipped stdlib versions must be
captured from the resolved build/dependency report; do not copy an assumed Kotlin
version into redistribution records.

## 2. Minimum SDK, Keystore, and private persistence

- Camera2 starts at API 21; `KeyGenParameterSpec` and Keystore AES/GCM start at API
  23; AAudio starts at API 26; NDK r29 supports API 21 and later:
  [Camera2](https://developer.android.com/reference/android/hardware/camera2/package-summary),
  [KeyGenParameterSpec](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec),
  [KeyProperties](https://developer.android.com/reference/android/security/keystore/KeyProperties),
  [AAudio](https://developer.android.com/ndk/guides/audio/aaudio/aaudio),
  [NDK stable APIs](https://developer.android.com/ndk/guides/stable_apis).
- Therefore `minSdk = 26` is defensible. The local `minSdk = 29` is a sensible
  simplification if Android 8.x coverage is not required; it aligns with camera FGS
  typing and `WIFI_MODE_FULL_LOW_LATENCY`, but it is not required by Camera2,
  Keystore, or the NDK.
- AndroidX Security Crypto is deprecated. Generate one non-exportable Android
  Keystore AES-256-GCM key, use a fresh random IV for every write, authenticate the
  complete bounded pairing record, and fail closed on authentication failure:
  [Android cryptography guidance](https://developer.android.com/privacy-and-security/cryptography),
  [Android Keystore](https://developer.android.com/privacy-and-security/keystore).
- Store ciphertext in `getNoBackupFilesDir()`. Internal files and preferences are
  otherwise backed up by default. Exclude pairing material from cloud backup and
  device transfer with both legacy and Android 12+ backup rules:
  [Auto Backup](https://developer.android.com/identity/data/autobackup).

## 3. Camera/microphone foreground-service rules

For a target-34+ application, declare `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_CAMERA`, and `FOREGROUND_SERVICE_MICROPHONE`, plus runtime
`CAMERA` and `RECORD_AUDIO`. The service declaration needs
`android:foregroundServiceType="camera|microphone"`; promotion must supply the
matching type bitmask:
[FGS service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[launch an FGS](https://developer.android.com/develop/background-work/services/fgs/launch).

Android 14+ validates while-in-use permissions when the service is created. Start
or bind the camera/microphone service only while the Activity is visible and only
after an explicit user action. A background attempt can throw `SecurityException`;
a generic permission check can still misleadingly report granted while the app is
backgrounded:
[background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

Once correctly started, the official camera and microphone service-type use cases
explicitly allow continuing access from the background. Ordinary screen-off or
lock does not itself revoke access. The documentation does not promise uninterrupted
capture across process death, OEM task killers, thermal shutdown, permission
revocation, Wi-Fi loss, or every Doze implementation. Those are physical-device
acceptance tests, not facts a unit test can establish.

Additional lifecycle boundaries:

- Removing the Activity from Recents is not force-stop. A service stops with the
  task only when `android:stopWithTask="true"`; its default is false:
  [service manifest element](https://developer.android.com/guide/topics/manifest/service-element),
  [Service API](https://developer.android.com/reference/android/app/Service).
- Force-stop leaves the package stopped until explicit user interaction; Android
  15 also cancels pending intents. It must never auto-resume capture:
  [package unstopped action](https://developer.android.com/reference/android/content/Intent#ACTION_PACKAGE_UNSTOPPED),
  [Android 15 behavior](https://developer.android.com/about/versions/15/behavior-changes-all).
- Camera FGS boot launch is prohibited for target-35+; microphone boot launch was
  already prohibited for target-34+. Do not register a boot receiver to resume a
  stream:
  [Android 15 FGS type changes](https://developer.android.com/about/versions/15/changes/foreground-service-types).
- `START_STICKY` cannot override force-stop and may recreate the service with a null
  Intent. Unless durable session authority can distinguish process death from user
  Stop, `START_NOT_STICKY` is safer.
- On target 37, background audio focus/interaction is further constrained to an
  appropriate FGS with while-in-use capability. Handle audio-focus failure:
  [Android 17 behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17).

`POST_NOTIFICATIONS` is not required to start an FGS, but the notification remains
mandatory. When notification permission is denied, the user sees the FGS only in
Task Manager, not the notification drawer; request it in context so the Stop action
is normally visible:
[notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission).

## 4. Screen-off power and LAN permission

A held `PARTIAL_WAKE_LOCK` keeps the CPU running while the display is off. Hold it
only for the active session, use balanced/non-reference-counted ownership, and
release it on every terminal and partial-startup error path:
[wake locks](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock),
[PowerManager](https://developer.android.com/reference/android/os/PowerManager#PARTIAL_WAKE_LOCK).

Doze suspends network and ignores wake locks. An FGS prevents App Standby idle
classification, but does not exempt the app from device-wide Doze. Battery-
optimization exemption may permit network and partial wake locks, but requesting
direct exemption is policy-sensitive and should happen only if forced-idle/device
tests prove it necessary for the core user-visible feature:
[Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby).

`WIFI_MODE_FULL_HIGH_PERF` is deprecated at API 34 and automatically maps to
`WIFI_MODE_FULL_LOW_LATENCY`. Low-latency mode is active only while associated,
screen-on, and foreground, so no Wi-Fi lock guarantees screen-off high-performance
streaming on modern Android. A lock is best-effort, cannot override Wi-Fi-off or
airplane mode, and must be released:
[Wi-Fi lock modes](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_HIGH_PERF),
[WifiLock](https://developer.android.com/reference/android/net/wifi/WifiManager.WifiLock).

Android 17 target-37 apps must request runtime `ACCESS_LOCAL_NETWORK` before direct
LAN TCP/UDP, including SRT caller unicast. Declare `INTERNET` too, handle denial and
revocation, and make the request user-triggered after pairing or Start. A QR raw-IP
endpoint does not use a system-picker exemption:
[local network permission](https://developer.android.com/privacy-and-security/local-network-permission),
[Android 17 behavior](https://developer.android.com/about/versions/17/behavior-changes-17).

## 5. SRT build, caller mode, and security

### Version and Android support

Pin Haivision SRT **v1.5.6 or newer within an audited compatible line**. The 2026-07-20
release fixes CVE-2026-55869 (KMREQ heap overflow), CVE-2026-55868 (encryption
state-machine downgrade), and related bounds issues:
[SRT v1.5.6 release](https://github.com/Haivision/srt/releases/tag/v1.5.6).

Upstream documents Android NDK+CMake builds and supplies a multi-ABI shell script:
[Android build guide](https://github.com/Haivision/srt/blob/master/docs/build/build-android.md),
[build script](https://github.com/Haivision/srt/blob/master/scripts/build-android/build-android).
The script proves Android support, not a turnkey Windows/Gradle build: it is POSIX
shell, recognizes Linux/macOS hosts, and contains stale defaults/examples (API 28,
old NDK/OpenSSL). Vendor exact source and drive it from the pinned CMake/NDK build;
do not download or execute upstream master during an APK build.

The phone's role is caller only: create a socket, set all pre-connect options, and
`srt_connect()` to the receiver. Never bind/listen/accept or open an inbound service:
[srt-live-transmit modes](https://github.com/Haivision/srt/blob/master/docs/apps/srt-live-transmit.md),
[SRT API functions](https://github.com/Haivision/srt/blob/master/docs/API/API-functions.md).

### Encryption decision that cannot be hidden

SRT 1.5.6 implements:

- `SRTO_CRYPTOMODE=1`: AES-CTR, explicitly without message-integrity
  authentication.
- `SRTO_CRYPTOMODE=2`: AES-GCM authenticated encryption, requires TSBPD and a
  build with `ENABLE_AEAD_API_PREVIEW=ON`. AEAD remains preview/off by default and
  is documented as planned to become official in SRT 1.6.

Sources: [socket options](https://github.com/Haivision/srt/blob/master/docs/API/API-socket-options.md),
[encryption](https://github.com/Haivision/srt/blob/master/docs/features/encryption.md),
[build options](https://github.com/Haivision/srt/blob/master/docs/build/build-options.md).

Thus the strongest implemented contract is mode 2, TSBPD/live mode,
`SRTO_PBKEYLEN=32`, `SRTO_ENFORCEDENCRYPTION=true`, and post-connect verification
that the negotiated mode is 2 and key state is secured. Any mismatch must close the
socket. This requires an identically built future PC receiver. There is no official
evidence that stock OBS enables SRT's preview AEAD build, so secure stock-OBS
interoperability is **unproven**. Mode 1 AES-256-CTR is the likely stock-compatible
fallback, but it does not meet an authenticated-media requirement. Never silently
downgrade from GCM to CTR.

The passphrase documentation conflicts: the option page says 10–80 characters,
while the public header/app documentation says 10–79. Enforce the conservative
intersection, 10–79 printable ASCII bytes. Use 32 random bytes encoded as 43
base64url characters without padding. `PBKEYLEN` accepts 0/16/24/32 and 0 can
resolve to AES-128, so explicitly set 32. Treat Stream ID as public routing metadata,
not a secret. SRT PSK has no certificate identity; possession of the QR secret is
the receiver-authentication property. Do not claim complete anti-replay semantics
without a receiver-side session/credential audit:
[public SRT header](https://github.com/Haivision/srt/blob/master/srtcore/srt.h).

For GPLv2-sensitive crypto, Botan is the cleanest supported backend found. SRT AEAD
supports Botan; Botan 3 is BSD-2-Clause and documents Android/NDK support. Pin and
minimize its module set and audit the produced ELF dependencies:
[Botan source/license](https://github.com/randombit/botan),
[Botan platform support](https://botan.randombit.net/handbook/support.html),
[Botan build guide](https://botan.randombit.net/handbook/building.html).

## 6. SRT latency, queues, errors, and reconnect

SRT live mode uses TSBPD, live congestion control, too-late dropping, message mode,
and a default live payload of 1316 bytes (seven TS packets). Its default latency is
120 ms. Negotiated receiver buffering is the maximum of receiver latency and the
sender's peer-latency request; it is not total glass-to-glass latency:
[SRT API](https://github.com/Haivision/srt/blob/master/docs/API/API.md),
[latency](https://github.com/Haivision/srt/blob/master/docs/features/latency.md).

Use 120 ms as an initial LAN value, bounded/configurable after measuring RTT and
loss. OBS recommends at least 2.5× RTT. Be explicit about units: libsrt socket
options use milliseconds, while OBS/FFmpeg SRT URL parameters are commonly
microseconds:
[OBS SRT guide](https://obsproject.com/kb/srt-protocol-streaming-guide).

Broken connections are not re-established by libsrt. A failed or lost connection
must close the old socket, create a new socket, reapply all options, and re-verify
encryption. Use cancellable bounded exponential backoff. Authentication/config
failures are terminal until configuration changes; no-server/timeouts/network loss
are retryable. On reconnect, discard stale encoded data and start a new TS session
with PAT/PMT and a fresh random-access video unit. Blocking connect defaults to a
3-second timeout; peer-idle defaults to 5 seconds. For bounded sends, use
nonblocking send plus epoll and cap both access-unit and byte counts; never block
the encoder callback indefinitely:
[SRT API functions](https://github.com/Haivision/srt/blob/master/docs/API/API-functions.md),
[bonding/reconnect behavior](https://github.com/Haivision/srt/blob/master/docs/features/bonding-intro.md).

FEC is not automatically enabled. It is an optional packet filter; SRT's core live
recovery is ARQ/retransmission. Do not claim built-in active FEC or enable it before
receiver negotiation and loss testing:
[SRT packet-filter options](https://github.com/Haivision/srt/blob/master/docs/API/API-socket-options.md).

## 7. MPEG-TS contract and timestamps

The normative container is ITU-T H.222.0 / ISO/IEC 13818-1:
[H.222.0 landing page](https://www.itu.int/rec/T-REC-H.222.0/en),
[specification PDF](https://www.itu.int/rec/dologin_pub.asp?id=T-REC-H.222.0-201703-S%21%21PDF-E&lang=e&type=items).

The sender needs a real TS muxer; Android `MediaMuxer` has no MPEG-TS output:
[MediaMuxer output formats](https://developer.android.com/reference/android/media/MediaMuxer.OutputFormat).
`MediaRecorder.OutputFormat.MPEG_2_TS` is coupled to MediaRecorder and documents
H.264/AAC, so it is not a substitute for the required MediaCodec HEVC/AVC path:
[MediaRecorder MPEG-2 TS](https://developer.android.com/reference/android/media/MediaRecorder.OutputFormat#MPEG_2_TS).

Required wire facts:

- 188-byte packets with sync byte `0x47`; seven packets fit the 1316-byte SRT live
  payload.
- PAT maps program to PMT; PMT declares `PCR_PID` and elementary streams. Stream
  types are AAC ADTS `0x0F`, H.264/AVC `0x1B`, and HEVC `0x24`.
- Maintain continuity counters per PID and valid PSI CRC. Repeat PAT/PMT regularly
  and at every new connection/reconfiguration.
- Start each connection with PAT/PMT followed by an independently decodable random-
  access AU and parameter sets: SPS/PPS+IDR for AVC; VPS/SPS/PPS+IDR/CRA as
  appropriate for HEVC.
- PTS/DTS are 33-bit 90 kHz values; PCR uses a 27 MHz clock from the same monotonic
  session timebase. Put PTS on every video/audio PES. Use DTS only when decode and
  presentation order differ; with a genuinely no-B-frame encoder, omit DTS or set
  DTS=PTS. Handle 33-bit wrap deliberately.
- Normalize MediaCodec `presentationTimeUs` and audio capture timestamps to one
  monotonic session origin. AAC-LC advances exactly 1024 samples per frame. Do not
  independently timestamp audio and video from wall clock.

Android timestamp sources:
[MediaCodec BufferInfo](https://developer.android.com/reference/android/media/MediaCodec.BufferInfo),
[AudioTimestamp](https://developer.android.com/reference/android/media/AudioTimestamp),
[AudioRecord](https://developer.android.com/reference/android/media/AudioRecord).
Camera timestamp-domain compatibility varies by the device's
`SENSOR_INFO_TIMESTAMP_SOURCE`; cross-domain alignment is therefore a device test:
[CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE).

H.264 Annex-B plus AAC-LC ADTS is the broadest initial OBS/FFmpeg contract. Offer
HEVC Annex-B only after capability negotiation and decoder/hardware testing. The
FFmpeg TS muxer is useful as a first-party implementation cross-check, not a license
grant for copying code:
[FFmpeg mpegtsenc](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/mpegtsenc.c),
[FFmpeg mpegts](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/mpegts.c).

## 8. Redistribution and QR dependency audit

### SRT MPL-2.0

libsrt uses MPL-2.0 Exhibit A and not Exhibit B. MPL 2.0 section 3.3 and Mozilla's
FAQ allow eligible MPL-covered files in a GPLv2 Larger Work. Preserve MPL notices,
make the exact libsrt source and modifications available under MPL, and document
both sets of obligations. Static versus dynamic linking does not remove MPL's
file-level duties:
[libsrt LICENSE](https://github.com/Haivision/srt/blob/master/LICENSE),
[example SRT header](https://github.com/Haivision/srt/blob/master/srtcore/srt.h),
[Mozilla MPL FAQ](https://www.mozilla.org/en-US/MPL/2.0/FAQ/),
[Mozilla combining MPL and GPL](https://www.mozilla.org/en-US/MPL/2.0/combining-mpl-and-gpl/).

### Strict GPL-2.0-only blocker

The FSF states that Apache License 2.0 is incompatible with GPLv2. Kotlin and its
stdlib, AndroidX, ZXing, Mbed TLS, and OpenSSL 3 use Apache-2.0. Build-only tools are
a separate analysis, but artifacts copied into the APK are distribution inputs:
[FSF Apache-2.0 compatibility](https://www.gnu.org/licenses/license-list.html#apache2),
[Kotlin license](https://github.com/JetBrains/kotlin),
[AndroidX license/source](https://github.com/androidx/androidx),
[ZXing license](https://github.com/zxing/zxing/blob/master/LICENSE).

The current project ships ZXing and built-in Kotlin stdlib, so it must not be
described as clean `GPL-2.0-only`. Switching the application to
`GPL-2.0-or-later` is a material licensing decision, not a clerical fix, and needs
the owner's approval. Other possible paths require legal and technical proof:

1. a carefully drafted, reviewed GPLv2 linking exception for identified Apache-2.0
   runtime libraries;
2. an architecture that ships no Apache-licensed runtime code, including proving
   no Kotlin stdlib references/classes in the final APK; or
3. an approved GPL-2.0-or-later/GPLv3-compatible distribution expression.

For offline QR decoding, ML Kit is not a clean substitute: its terms are proprietary,
allow update contact and utilization/performance reporting, and conflict with the
no-external-service/no-telemetry posture:
[ML Kit barcode scanning](https://developers.google.com/ml-kit/vision/barcode-scanning/android),
[ML Kit terms](https://developers.google.com/ml-kit/terms).

Potential non-Apache candidates require real evaluation. `quirc` is small C under
ISC; ZBar is maintained under LGPL-2.1 and includes Android sources. QR robustness,
camera integration, maintenance, native dependencies, and compliance still need to
be proven before selection:
[quirc](https://github.com/dlbeer/quirc),
[ZBar](https://github.com/mchehab/zbar).

Every release must archive the resolved JVM/native dependency graph, inspect the
APK/AAB contents and ELF `NEEDED` entries, ship notices, and provide corresponding
source where licenses require it. Excluding `META-INF/NOTICE*` from the APK does not
remove notice obligations; notices must be delivered elsewhere in the distribution.

## 9. Future OBS receiver constraints

Stock OBS can test the basic topology with a Media Source, Local File disabled,
`srt://0.0.0.0:PORT?mode=listener...`, and input format `mpegts`. OBS says VLC
Source itself must be the caller, so it is wrong for a listener topology:
[OBS SRT guide](https://obsproject.com/kb/srt-protocol-streaming-guide),
[OBS Media Source](https://obsproject.com/kb/media-sources).

H.264/AAC is the safest stock baseline. HEVC availability depends on the exact
OBS-packaged FFmpeg decoder, build, and hardware. Stock OBS support for SRT 1.5.6
AEAD preview is undocumented; it must not be inferred from generic SRT support.
The secure future path is a native receiver/plugin with the same pinned libsrt,
Botan, AEAD build flags, QR/session contract, and explicit fail-closed negotiation.

For a future plugin, OBS's async source API accepts timestamped raw video/audio in
RAM, while graphics APIs expose Windows shared textures and keyed-mutex operations.
Zero-copy compatibility across decoder, D3D device, format, and adapter is not
guaranteed and needs a prototype:
[OBS source API](https://docs.obsproject.com/reference-sources),
[OBS graphics API](https://github.com/obsproject/obs-studio/blob/master/libobs/graphics/graphics.h).

## 10. Explicit unresolved decisions and proof gates

- **License:** obtain owner/legal approval for the application license expression
  or exception before calling the artifact GPL-compliant. Re-audit ZXing, Kotlin
  stdlib, AndroidX test APKs, crypto backend, and notices.
- **Authenticated transport versus stock OBS:** choose and document either pinned
  AES-256-GCM with a matching custom receiver, or consciously accept that stock-
  compatible AES-CTR lacks integrity. No automatic downgrade.
- **CMake/AGP:** keep local AGP 9.2.1/CMake 3.31.6 until a complete build proves a
  reason to move; record that current stable is AGP 9.3.1/CMake 4.1.2.
- **Timestamping:** verify camera/audio clocks and long-duration A/V drift on each
  supported device family, including PTS wrap logic with synthetic tests.
- **Screen-off:** test forced Doze, lock, Recents removal, thermal pressure, Wi-Fi
  roaming/loss, permissions revoked mid-session, process kill, force-stop, and
  reboot. Only the first three may legally continue; none is guaranteed by a lock.
- **Media:** validate 4K60 encoder capability per exact size/rate/profile; require
  bounded fallback to 4K30/1080p and prove reconnect starts at PAT/PMT+keyframe.
- **Receiver:** verify exact TS, SRT option units, AES mode, codec support, and
  reconnect against the eventual Windows/OBS receiver; stock OBS documentation is
  not evidence for preview AEAD or HEVC on every installation.
