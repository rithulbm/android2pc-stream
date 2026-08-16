# ADR 0001: Native Android local camera sender

Status: accepted for mobile v1

## Decision

The sender is a native Android application. Kotlin owns UI, permissions, the foreground-service lifecycle, Camera2, MediaCodec, audio capture, capability selection, and session state. C++20 behind a narrow JNI boundary owns MPEG-TS packetization, bounded transport queues, cancellation, and the SRT caller socket.

Camera frames flow directly from Camera2 into a MediaCodec input `Surface`; raw 4K frames never cross Kotlin, JNI, or CPU-owned transport buffers. Only bounded encoded access units cross JNI. HEVC/H.265 is preferred when a hardware encoder supports the exact size/rate; H.264 is the compatibility fallback. AAC-LC is the initial audio codec because MPEG-TS and desktop demuxers support it predictably.

## Connection direction

The PC receiver is an SRT listener. The phone is always the SRT caller. The phone never opens an inbound port, never changes a router, and never uses signaling, STUN/TURN, cloud relay, telemetry, accounts, or a backend. A QR code is the authenticated out-of-band pairing path.

## Transport

SRT is selected for a one-way, high-bitrate local-Wi-Fi contribution feed because it provides caller/listener operation, bounded latency, retransmission, congestion control, and built-in passphrase encryption without a signaling service. Version 1.5.6 is pinned because it is the maintained stable release and includes encryption-downgrade and packet/parser memory-safety fixes. The sender is built with SRT's preview AEAD API and requires cryptographic mode 2: AES-256-GCM, TSBPD/live mode, `SRTO_PBKEYLEN=32`, and enforced encryption. Connection setup verifies the negotiated mode and key state and fails closed on any mismatch. Mature mode 1 uses AES-CTR without message integrity and is never an automatic fallback. The PC receiver uses the same pinned SRT/AEAD configuration; stock OBS SRT interoperability is not assumed.

MPEG-TS is the v1 binary container. It carries one HEVC or AVC video elementary stream plus optional AAC-LC audio, with monotonic 90 kHz PTS/PCR timestamps. SRT transmits fixed, binary-safe groups of seven 188-byte TS packets (1316 bytes). The receiver validates complete packet groups and passes the versioned program to an OBS-owned FFmpeg child for demux/decode.

## Screen-off behavior

A user-visible camera/microphone foreground service is started only from the visible Activity after an explicit user action: scanning a valid pairing QR or tapping **Start streaming** for a saved pairing. A successful scan continues through Android's camera, microphone, notification, and Android 17 local-network permission prompts before starting; it never bypasses a missing grant. The service owns the session, notification, Camera2 devices, codecs, network, wake lock, and Wi-Fi lock. The Activity may be backgrounded or removed while the service continues. A correctly started camera foreground service can continue after ordinary screen-off and lock, but OEM policy, thermal shutdown, permission revocation, or process death can still stop it; only physical-device tests can establish product reliability.

MediaProjection screen sharing is intentionally absent. Screen capture has separate consent and lifecycle restrictions and cannot satisfy the camera-mode screen-off promise.

## Explicit non-decisions

- The PC implementation is maintained separately under `pc/`; this ADR remains scoped to mobile architecture.
- No Rust is introduced; it would duplicate the Kotlin/C++ boundary without a proven mobile benefit.
- No mDNS identity is trusted. Discovery may be added later only after QR authentication.
- No undocumented MediaCodec vendor keys are used.
- No claim is made that every encoder obeys CBR, low-latency, B-frame, or bitrate updates identically.

## Evidence still requiring physical hardware

- Camera and microphone continue for 30 and 120 minutes with the display off and device locked.
- A documented Android 14+ device sustains 3840x2160 at 60 FPS through a hardware HEVC encoder.
- Thermal fallback, battery drain, OEM background policy, Wi-Fi loss/roaming, incoming audio interruption, and SRT reconnect are acceptable.
- The installed PC listener decrypts/demuxes the exact physical-phone stream and OBS preserves A/V sync.
