@file:Suppress("DEPRECATION")
@file:android.annotation.SuppressLint("SyntheticAccessor")

package dev.localstream.sender.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import dev.localstream.sender.quality.FpsRangeSelector
import dev.localstream.sender.quality.ProfileCapability
import dev.localstream.sender.quality.QualityProfile
import dev.localstream.sender.quality.VideoCodec
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class MediaFailure {
    CAMERA_PERMISSION,
    CAMERA_UNAVAILABLE,
    CAMERA_SESSION,
    VIDEO_ENCODER,
    AUDIO_PERMISSION,
    AUDIO_CAPTURE,
    AUDIO_ENCODER,
    AUDIO_FOCUS_LOST,
}

data class VideoEncoderConfig(
    val cameraId: String,
    val profile: QualityProfile,
    val capability: ProfileCapability,
    val codec: VideoCodec,
)

/** Camera2 -> MediaCodec surface pipeline. No raw video buffer crosses into application code. */
class VideoEncoder(
    private val context: Context,
    private val config: VideoEncoderConfig,
    private val onAccessUnit: (ByteArray, Long, Boolean) -> Boolean,
    private val onFailure: (MediaFailure) -> Unit,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val normalizer = EncodedVideoNormalizer()
    private val accessUnitAssembler = EncodedAccessUnitAssembler(EncodedVideoNormalizer.MAX_ACCESS_UNIT_BYTES)
    private val cameraThread = HandlerThread("stream-camera")
    private val encoderThread = HandlerThread("stream-video-output")
    private lateinit var cameraHandler: Handler
    private lateinit var encoderHandler: Handler
    private var camera: CameraDevice? = null
    private var cameraManager: CameraManager? = null
    private var cameraOpening = false
    private var session: CameraCaptureSession? = null
    private var codec: MediaCodec? = null
    private var codecCallback: MediaCodec.Callback? = null
    private var inputSurface: Surface? = null
    private var captureFpsRange: Range<Int>? = null
    private var lastKeyFrameRequestNs = 0L
    private val codecConfigParts = mutableListOf<ByteArray>()
    private val formatProvidedCodecConfig = AtomicBoolean(false)
    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            if (cameraId == config.cameraId && running.get() && !cameraOpening && camera == null) {
                onFailure(MediaFailure.CAMERA_UNAVAILABLE)
            }
        }
    }

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            running.set(false)
            onFailure(MediaFailure.CAMERA_PERMISSION)
            return false
        }
        cameraThread.start()
        encoderThread.start()
        cameraHandler = Handler(cameraThread.looper)
        encoderHandler = Handler(encoderThread.looper)
        return try {
            configureEncoder()
            if (!openCameraAndSession()) throw IllegalStateException("camera session unavailable")
            true
        } catch (_: Exception) {
            onFailure(MediaFailure.VIDEO_ENCODER)
            close()
            false
        }
    }

    fun requestKeyFrame() {
        val now = System.nanoTime()
        if (now - lastKeyFrameRequestNs < KEY_FRAME_REQUEST_INTERVAL_NS) return
        lastKeyFrameRequestNs = now
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: IllegalStateException) {
            // A reconnect can race encoder teardown. A failed sync-frame hint is not
            // itself a terminal codec failure; the codec callback owns that decision.
            Log.w(TAG, "sync-frame request ignored because encoder is not executing")
        }
    }

    @SuppressLint("InlinedApi")
    private fun configureEncoder() {
        val encoderName = config.capability.encoderName
            ?: throw IllegalStateException("validated hardware encoder missing")
        val configuredBitrate = config.capability.bitrate
            ?: throw IllegalStateException("validated encoder bitrate missing")
        if (config.capability.codec != config.codec) {
            throw IllegalStateException("validated encoder codec changed")
        }
        val mediaFormat = MediaFormat.createVideoFormat(
            config.codec.mimeType,
            config.profile.width,
            config.profile.height,
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, configuredBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.profile.framesPerSecond)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, config.profile.framesPerSecond.toFloat())
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_SECONDS)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_LATENCY, ENCODER_LATENCY_FRAMES)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val created = MediaCodec.createByCodecName(encoderName)
        if (!created.codecInfo.isEncoder || config.codec.mimeType !in created.codecInfo.supportedTypes) {
            created.release()
            throw IllegalStateException("validated hardware encoder is no longer available")
        }
        val codecCapabilities = created.codecInfo.getCapabilitiesForType(config.codec.mimeType)
        val encoderCapabilities = codecCapabilities.encoderCapabilities
        if (encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
            mediaFormat.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            )
        }
        val callback = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                handleOutput(codec, index, info)
            }

            override fun onError(codec: MediaCodec, exception: MediaCodec.CodecException) {
                Log.w(TAG, "encoder error recoverable=${exception.isRecoverable} transient=${exception.isTransient}")
                if (running.get()) onFailure(MediaFailure.VIDEO_ENCODER)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                val parts = listOfNotNull(
                    copyBuffer(format.getByteBuffer("csd-0")),
                    copyBuffer(format.getByteBuffer("csd-1")),
                    copyBuffer(format.getByteBuffer("csd-2")),
                )
                if (parts.isEmpty()) {
                    Log.i(TAG, "output format has no CSD; accepting in-band codec configuration")
                    return
                }
                replaceCodecConfigParts(parts)
                if (normalizer.setCodecConfiguration(codecConfigParts.toList())) {
                    formatProvidedCodecConfig.set(true)
                } else {
                    Log.w(TAG, "format CSD rejected; accepting in-band codec configuration")
                }
            }
        }
        codecCallback = callback
        created.setCallback(callback, encoderHandler)
        created.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = created.createInputSurface()
        codec = created
        created.start()
        Log.i(
            TAG,
            "encoder started name=$encoderName mime=${config.codec.mimeType} " +
                "${config.profile.width}x${config.profile.height}@${config.profile.framesPerSecond} bitrate=$configuredBitrate",
        )
    }

    private fun handleOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        try {
            if (info.size <= 0 || info.size > EncodedVideoNormalizer.MAX_ACCESS_UNIT_BYTES) return
            val source = codec.getOutputBuffer(index) ?: return
            if (info.offset < 0 || info.size < 0 || info.offset + info.size > source.capacity()) return
            source.position(info.offset)
            source.limit(info.offset + info.size)
            val fragment = ByteArray(info.size)
            source.get(fragment)
            val partialFrame = info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME != 0
            val assembled = accessUnitAssembler.offer(
                bytes = fragment,
                presentationTimeUs = info.presentationTimeUs,
                flags = info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME.inv(),
                partialFrame = partialFrame,
            )
            fragment.fill(0)
            if (assembled != null) {
                processEncodedAccessUnit(assembled)
                assembled.bytes.fill(0)
            }
        } catch (_: RuntimeException) {
            accessUnitAssembler.clear()
            if (running.get()) onFailure(MediaFailure.VIDEO_ENCODER)
        } finally {
            try {
                codec.releaseOutputBuffer(index, false)
            } catch (_: IllegalStateException) {
                // Cleanup may race a final callback; the codec has already released this buffer.
            }
        }
    }

    private fun processEncodedAccessUnit(unit: EncodedAccessUnit) {
        val isConfiguration = unit.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val isKeyFrame = unit.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (isConfiguration) {
            if (!formatProvidedCodecConfig.get()) {
                codecConfigParts += unit.bytes.copyOf()
                if (!normalizer.setCodecConfiguration(codecConfigParts.toList())) {
                    Log.i(TAG, "partial in-band codec configuration; waiting for additional CSD")
                }
            }
            return
        }

        // Some Android encoders provide VPS/SPS/PPS only inside the first keyframe.
        // Do not abort merely because an out-of-band CSD callback has not arrived.
        val accessUnit = normalizer.normalizeFrame(unit.bytes, isKeyFrame)
        if (accessUnit == null) {
            Log.w(TAG, "encoder produced an invalid access unit")
            if (running.get()) onFailure(MediaFailure.VIDEO_ENCODER)
        } else {
            try {
                if (!onAccessUnit(accessUnit, unit.presentationTimeUs, isKeyFrame)) {
                    requestKeyFrame()
                }
            } finally {
                accessUnit.fill(0)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraAndSession(): Boolean {
        val manager = context.getSystemService(CameraManager::class.java)
        cameraManager = manager
        manager.registerAvailabilityCallback(availabilityCallback, cameraHandler)
        captureFpsRange = chooseFpsRange(manager) ?: return false
        val cameraLatch = CountDownLatch(1)
        val cameraResult = AtomicReference<CameraDevice?>()
        cameraOpening = true
        manager.openCamera(config.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                cameraOpening = false
                cameraResult.set(device)
                cameraLatch.countDown()
            }

            override fun onDisconnected(device: CameraDevice) {
                cameraOpening = false
                device.close()
                if (camera === device) camera = null
                cameraLatch.countDown()
                if (running.get()) onFailure(MediaFailure.CAMERA_UNAVAILABLE)
            }

            override fun onError(device: CameraDevice, error: Int) {
                cameraOpening = false
                device.close()
                if (camera === device) camera = null
                cameraLatch.countDown()
                if (running.get()) onFailure(MediaFailure.CAMERA_UNAVAILABLE)
            }
        }, cameraHandler)
        if (!cameraLatch.await(CAMERA_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return false
        val openedCamera = cameraResult.get() ?: return false
        camera = openedCamera
        val encoderSurface = inputSurface ?: return false
        val fpsRange = captureFpsRange ?: return false
        val sessionLatch = CountDownLatch(1)
        val configured = AtomicBoolean(false)
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                if (!running.get()) {
                    captureSession.close()
                    sessionLatch.countDown()
                    return
                }
                session = captureSession
                try {
                    startRepeatingCapture(openedCamera, captureSession, encoderSurface)
                    configured.set(true)
                } catch (_: Exception) {
                    onFailure(MediaFailure.CAMERA_SESSION)
                } finally {
                    sessionLatch.countDown()
                }
            }

            override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                captureSession.close()
                sessionLatch.countDown()
                if (running.get()) onFailure(MediaFailure.CAMERA_SESSION)
            }
        }
        val sessionType = if (config.capability.constrainedHighSpeed) {
            SessionConfiguration.SESSION_HIGH_SPEED
        } else {
            SessionConfiguration.SESSION_REGULAR
        }
        val sessionParameters = openedCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        }.build()
        val sessionConfig = SessionConfiguration(
            sessionType,
            listOf(OutputConfiguration(encoderSurface)),
            Executor { command ->
                cameraHandler.post(command)
                Unit
            },
            callback,
        )
        sessionConfig.setSessionParameters(sessionParameters)
        openedCamera.createCaptureSession(sessionConfig)
        return sessionLatch.await(CAMERA_START_TIMEOUT_SECONDS, TimeUnit.SECONDS) && configured.get()
    }

    private fun startRepeatingCapture(
        device: CameraDevice,
        captureSession: CameraCaptureSession,
        encoderSurface: Surface,
    ) {
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(encoderSurface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                captureFpsRange ?: throw IllegalStateException("capture FPS range missing"),
            )
        }.build()
        if (config.capability.constrainedHighSpeed) {
            val highSpeed = captureSession as? CameraConstrainedHighSpeedCaptureSession
                ?: throw IllegalStateException("high speed session missing")
            highSpeed.setRepeatingBurst(highSpeed.createHighSpeedRequestList(request), null, cameraHandler)
        } else {
            captureSession.setRepeatingRequest(request, null, cameraHandler)
        }
    }

    private fun copyBuffer(buffer: ByteBuffer?): ByteArray? {
        if (buffer == null || !buffer.hasRemaining() || buffer.remaining() > MAX_CODEC_CONFIGURATION_BYTES) return null
        val duplicate = buffer.duplicate()
        return ByteArray(duplicate.remaining()).also { duplicate.get(it) }
    }

    private fun replaceCodecConfigParts(parts: List<ByteArray>) {
        codecConfigParts.forEach { it.fill(0) }
        codecConfigParts.clear()
        codecConfigParts += parts
    }

    private fun chooseFpsRange(manager: CameraManager): Range<Int>? {
        val characteristics = manager.getCameraCharacteristics(config.cameraId)
        val target = config.profile.framesPerSecond
        val candidates = if (config.capability.constrainedHighSpeed) {
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            map.getHighSpeedVideoFpsRangesFor(android.util.Size(config.profile.width, config.profile.height)).toList()
        } else {
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        }
        val chosen = FpsRangeSelector.choose(candidates.map { it.lower to it.upper }, target) ?: return null
        val range = candidates.firstOrNull { it.lower == chosen.first && it.upper == chosen.second } ?: return null
        Log.i(TAG, "selected fps range ${range.lower}-${range.upper} for target $target")
        return range
    }

    override fun close() {
        if (!running.getAndSet(false) && codec == null && camera == null) return
        try {
            session?.stopRepeating()
            session?.abortCaptures()
        } catch (_: Exception) {
            // The camera may already have disconnected.
        }
        session?.close()
        session = null
        camera?.close()
        camera = null
        cameraOpening = false
        try {
            cameraManager?.unregisterAvailabilityCallback(availabilityCallback)
        } catch (_: RuntimeException) {
            // Registration may have failed during partial startup.
        }
        cameraManager = null
        try {
            codec?.signalEndOfInputStream()
        } catch (_: IllegalStateException) {
            // Encoder may have failed before its input surface started.
        }
        try {
            codec?.stop()
        } catch (_: IllegalStateException) {
            // Stop remains idempotent after codec failure.
        }
        codec?.release()
        codec = null
        codecCallback = null
        inputSurface?.release()
        inputSurface = null
        captureFpsRange = null
        replaceCodecConfigParts(emptyList())
        formatProvidedCodecConfig.set(false)
        accessUnitAssembler.clear()
        normalizer.clear()
        stopThread(cameraThread)
        stopThread(encoderThread)
    }

    private fun stopThread(thread: HandlerThread) {
        if (!thread.isAlive) return
        thread.quitSafely()
        try {
            thread.join(THREAD_JOIN_TIMEOUT_MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "VideoEncoder"
        private const val KEY_FRAME_INTERVAL_SECONDS = 2f
        private const val KEY_FRAME_REQUEST_INTERVAL_NS = 500_000_000L
        private const val ENCODER_LATENCY_FRAMES = 1
        private const val CAMERA_START_TIMEOUT_SECONDS = 5L
        private const val THREAD_JOIN_TIMEOUT_MILLISECONDS = 2_000L
        private const val MAX_CODEC_CONFIGURATION_BYTES = 256 * 1024
    }
}
