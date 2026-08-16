package dev.localstream.sender.transport

import dev.localstream.sender.pairing.PairingRecord
import dev.localstream.sender.quality.VideoCodec
import java.io.Closeable

enum class TransportStatus {
    STOPPED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    AUTHENTICATION_FAILED,
    FAILED,
    NEEDS_KEY_FRAME,
}

enum class TransportError {
    NONE,
    INVALID_CONFIGURATION,
    SOCKET_CONFIGURATION,
    CONNECTION,
    ENCRYPTION_REJECTED,
    SEND,
    RECONNECT_EXHAUSTED,
    MUX,
    BACKPRESSURE,
}

/**
 * Narrow JNI boundary for the native MPEG-TS/SRT sender.
 *
 * No passphrase, payload, address, or native error message is ever logged or returned. The native
 * layer exposes only bounded media writes and allowlisted status codes.
 */
class NativeTransport private constructor(private var handle: Long) : Closeable {
    fun start(): Boolean = handle != 0L && nativeStart(handle)

    fun writeVideo(accessUnit: ByteArray, presentationTimeUs: Long, isKeyFrame: Boolean): Boolean =
        handle != 0L &&
            accessUnit.isNotEmpty() &&
            nativeWriteVideo(handle, accessUnit, presentationTimeUs, isKeyFrame)

    fun writeAudio(accessUnit: ByteArray, presentationTimeUs: Long): Boolean =
        handle != 0L &&
            accessUnit.isNotEmpty() &&
            nativeWriteAudio(handle, accessUnit, presentationTimeUs)

    fun status(): TransportStatus =
        TransportStatus.entries.getOrElse(nativeStatus(handle)) { TransportStatus.FAILED }

    fun error(): TransportError =
        TransportError.entries.getOrElse(nativeError(handle)) { TransportError.INVALID_CONFIGURATION }

    fun queuePercent(): Int = nativeQueuePercent(handle).coerceIn(0, 100)

    fun stop() {
        val current = handle
        if (current != 0L) {
            nativeStop(current)
        }
    }

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) {
            nativeDestroy(current)
        }
    }

    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)
    private external fun nativeWriteVideo(
        handle: Long,
        accessUnit: ByteArray,
        presentationTimeUs: Long,
        keyFrame: Boolean,
    ): Boolean

    private external fun nativeWriteAudio(
        handle: Long,
        accessUnit: ByteArray,
        presentationTimeUs: Long,
    ): Boolean

    private external fun nativeStatus(handle: Long): Int
    private external fun nativeError(handle: Long): Int
    private external fun nativeQueuePercent(handle: Long): Int

    companion object {
        init {
            System.loadLibrary("local_sender")
        }

        fun create(
            pairing: PairingRecord,
            videoCodec: VideoCodec,
            audioEnabled: Boolean,
        ): NativeTransport? {
            val secret = pairing.copySecret()
            return try {
                val handle = nativeCreate(
                    pairing.host,
                    SenderDeviceLabel.current(),
                    pairing.port,
                    secret,
                    pairing.latencyMs,
                    if (videoCodec == VideoCodec.HEVC) 1 else 2,
                    audioEnabled,
                    AUDIO_SAMPLE_RATE,
                    AUDIO_CHANNELS,
                )
                if (handle == 0L) null else NativeTransport(handle)
            } finally {
                secret.fill(0)
            }
        }

        @JvmStatic
        private external fun nativeCreate(
            host: String,
            deviceName: String,
            port: Int,
            secret: ByteArray,
            latencyMilliseconds: Int,
            videoCodec: Int,
            audioEnabled: Boolean,
            audioSampleRate: Int,
            audioChannels: Int,
        ): Long

        const val AUDIO_SAMPLE_RATE: Int = 48_000
        const val AUDIO_CHANNELS: Int = 1
    }
}
