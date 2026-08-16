package dev.localstream.sender.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Optional 48 kHz mono AAC-LC path with explicit audio-focus and permission handling. */
class AudioEncoder(
    private val context: Context,
    private val onAccessUnit: (ByteArray, Long) -> Boolean,
    private val onFailure: (MediaFailure) -> Unit,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focusThread = HandlerThread("stream-audio-focus")
    private var focusRequest: AudioFocusRequest? = null
    private var recorder: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var inputThread: Thread? = null
    private var outputThread: Thread? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            running.set(false)
            onFailure(MediaFailure.AUDIO_PERMISSION)
            return false
        }
        return try {
            focusThread.start()
            if (!requestAudioFocus()) throw IllegalStateException("audio focus unavailable")
            configureCodecAndRecorder()
            inputThread = Thread(::feedInput, "stream-audio-input").apply { start() }
            outputThread = Thread(::drainOutput, "stream-audio-output").apply { start() }
            true
        } catch (_: Exception) {
            onFailure(MediaFailure.AUDIO_CAPTURE)
            close()
            false
        }
    }

    private fun requestAudioFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if ((change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) &&
                    running.get()
                ) {
                    onFailure(MediaFailure.AUDIO_FOCUS_LOST)
                }
            }, Handler(focusThread.looper))
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun configureCodecAndRecorder() {
        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBuffer <= 0) throw IllegalStateException("audio input unsupported")
        val bufferBytes = (minimumBuffer * 2).coerceAtLeast(MINIMUM_AUDIO_BUFFER_BYTES)
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val createdRecorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (createdRecorder.state != AudioRecord.STATE_INITIALIZED) {
            createdRecorder.release()
            throw IllegalStateException("audio input unavailable")
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferBytes)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val createdCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        createdCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        createdCodec.start()
        createdRecorder.startRecording()
        if (createdRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            createdRecorder.release()
            createdCodec.stop()
            createdCodec.release()
            throw IllegalStateException("audio recording did not start")
        }
        recorder = createdRecorder
        codec = createdCodec
    }

    private fun feedInput() {
        val activeCodec = codec ?: return
        val activeRecorder = recorder ?: return
        var nextPresentationTimeUs = System.nanoTime() / 1_000L
        try {
            while (running.get()) {
                val index = activeCodec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (index < 0) continue
                val buffer = activeCodec.getInputBuffer(index)
                if (buffer == null) {
                    if (running.get()) onFailure(MediaFailure.AUDIO_ENCODER)
                    return
                }
                buffer.clear()
                val bytesRead = activeRecorder.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
                if (bytesRead <= 0) {
                    // Every dequeued MediaCodec input index must be returned, including when
                    // AudioRecord transiently produces no bytes or reports a terminal error.
                    activeCodec.queueInputBuffer(index, 0, 0, nextPresentationTimeUs, 0)
                    if (bytesRead < 0 && running.get()) {
                        onFailure(MediaFailure.AUDIO_CAPTURE)
                    }
                    continue
                }
                activeCodec.queueInputBuffer(index, 0, bytesRead, nextPresentationTimeUs, 0)
                val sampleFrames = bytesRead / BYTES_PER_SAMPLE_FRAME
                nextPresentationTimeUs += sampleFrames * 1_000_000L / SAMPLE_RATE
            }
        } catch (_: IllegalStateException) {
            if (running.get()) onFailure(MediaFailure.AUDIO_ENCODER)
        }
    }

    private fun drainOutput() {
        val activeCodec = codec ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val index = activeCodec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                if (index < 0) continue
                try {
                    val isConfiguration = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfiguration && info.size in 1..MAX_AUDIO_ACCESS_UNIT_BYTES) {
                        val buffer = activeCodec.getOutputBuffer(index)
                        if (buffer != null && info.offset >= 0 && info.offset + info.size <= buffer.capacity()) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            onAccessUnit(bytes, info.presentationTimeUs)
                            bytes.fill(0)
                        }
                    }
                } finally {
                    activeCodec.releaseOutputBuffer(index, false)
                }
            }
        } catch (_: IllegalStateException) {
            if (running.get()) onFailure(MediaFailure.AUDIO_ENCODER)
        }
    }

    override fun close() {
        if (!running.getAndSet(false) && codec == null && recorder == null) return
        try {
            recorder?.stop()
        } catch (_: IllegalStateException) {
            // Recording may already have stopped after route or permission loss.
        }
        joinThread(inputThread)
        joinThread(outputThread)
        inputThread = null
        outputThread = null
        recorder?.release()
        recorder = null
        try {
            codec?.stop()
        } catch (_: IllegalStateException) {
            // Codec failure and normal cleanup share the same idempotent path.
        }
        codec?.release()
        codec = null
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        focusRequest = null
        if (focusThread.isAlive) {
            focusThread.quitSafely()
            try {
                focusThread.join(THREAD_JOIN_TIMEOUT_MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun joinThread(thread: Thread?) {
        if (thread == null || thread === Thread.currentThread()) return
        try {
            thread.join(THREAD_JOIN_TIMEOUT_MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        private const val BIT_RATE = 128_000
        private const val BYTES_PER_SAMPLE_FRAME = 2
        private const val MINIMUM_AUDIO_BUFFER_BYTES = 8_192
        private const val MAX_AUDIO_ACCESS_UNIT_BYTES = 64 * 1024
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val THREAD_JOIN_TIMEOUT_MILLISECONDS = 2_000L
    }
}
