package dev.localstream.sender.media

import android.content.Context
import dev.localstream.sender.pairing.PairingRecord
import dev.localstream.sender.transport.NativeTransport
import dev.localstream.sender.transport.TransportError
import dev.localstream.sender.transport.TransportStatus
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

data class MediaPipelineConfig(
    val pairing: PairingRecord,
    val video: VideoEncoderConfig,
    val microphoneEnabled: Boolean,
)

/** Owns one complete camera/audio/native-transport session and its deterministic cleanup order. */
class MediaPipeline(
    context: Context,
    private val config: MediaPipelineConfig,
    private val onFailure: (MediaFailure) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val transport = NativeTransport.create(
        pairing = config.pairing,
        videoCodec = config.video.codec,
        audioEnabled = config.microphoneEnabled,
    )
    private val videoEncoder = transport?.let { activeTransport ->
        VideoEncoder(
            context = context.applicationContext,
            config = config.video,
            onAccessUnit = activeTransport::writeVideo,
            onFailure = onFailure,
        )
    }
    private val audioEncoder = if (config.microphoneEnabled && transport != null) {
        AudioEncoder(
            context = context.applicationContext,
            onAccessUnit = transport::writeAudio,
            onFailure = onFailure,
        )
    } else {
        null
    }

    fun start(): Boolean {
        if (closed.get() || transport == null || videoEncoder == null) return false
        if (!transport.start()) return false
        if (!videoEncoder.start()) {
            close()
            return false
        }
        if (audioEncoder?.start() == false) {
            close()
            return false
        }
        return true
    }

    fun transportStatus(): TransportStatus = transport?.status() ?: TransportStatus.FAILED

    fun transportError(): TransportError = transport?.error() ?: TransportError.INVALID_CONFIGURATION

    fun queuePercent(): Int = transport?.queuePercent() ?: 0

    fun requestKeyFrame(force: Boolean = false) {
        videoEncoder?.requestKeyFrame(force)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Producers stop first, then the native queue/socket, so no callback can target a freed handle.
        audioEncoder?.close()
        videoEncoder?.close()
        transport?.stop()
        transport?.close()
        config.pairing.destroy()
    }
}
