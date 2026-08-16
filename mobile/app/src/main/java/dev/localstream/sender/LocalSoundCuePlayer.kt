package dev.localstream.sender

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import java.io.Closeable

internal enum class UiSoundCue(val tone: Int, val durationMilliseconds: Int) {
    PAIRED(ToneGenerator.TONE_PROP_ACK, 110),
    START(ToneGenerator.TONE_PROP_BEEP, 90),
    STOP(ToneGenerator.TONE_PROP_BEEP2, 90),
    ERROR(ToneGenerator.TONE_SUP_ERROR, 180),
}

/** Short, device-local feedback cues. No samples, identifiers, or audio leave the phone. */
internal class LocalSoundCuePlayer : Closeable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var generator: ToneGenerator? = null
    private var closed = false

    fun play(cue: UiSoundCue) {
        if (closed) return
        mainHandler.post {
            if (closed) return@post
            val activeGenerator = generator ?: try {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME_PERCENT).also { generator = it }
            } catch (_: RuntimeException) {
                null
            }
            try {
                activeGenerator?.startTone(cue.tone, cue.durationMilliseconds)
            } catch (_: RuntimeException) {
                // Sound feedback is optional and must never interrupt streaming or pairing.
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        mainHandler.removeCallbacksAndMessages(null)
        try {
            generator?.release()
        } catch (_: RuntimeException) {
            // Some audio routes can disappear while an Activity is being destroyed.
        }
        generator = null
    }

    private companion object {
        const val VOLUME_PERCENT = 45
    }
}
