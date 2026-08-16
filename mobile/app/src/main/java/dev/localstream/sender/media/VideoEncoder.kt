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
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import dev.localstream.sender.quality.ProfileCapability
import dev.localstream.sender.quality.QualityProfile
import dev.localstream.sender.quality.VideoCodec
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
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
    private val cameraThread = HandlerThread("stream-camera")
    private val encoderThread = HandlerThread("stream-video-output")
    private lateinit var cameraHandler: Handler
    private lateinit var encoderHandler: Handler
    private var camera: CameraDevice? = null
    private var cameraManager: CameraManager? = null
    private var cameraOpening = false
    private var session: CameraCaptureSession? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var captureFpsRange: Range<Int>? = null
    private var lastKeyFrameRequestNs = 0L
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
            onFailure(MediaFailure.VIDEO_ENCODER)
        }
    }

    @SuppressLint("InlinedApi")
    private fun configureEncoder() {
        val mediaFormat = MediaFormat.createVideoFormat(
            config.codec.mimeType,
            config.profile.width,
            config.profile.height,
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.profile.targetBitrate(config.codec))
            setInteger(MediaFormat.KEY_FRAME_RATE, config.profile.framesPerSecond)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_SECONDS)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val created = MediaCodec.createEncoderByType(config.codec.mimeType)
        val codecCapabilities = created.codecInfo.getCapabilitiesForType(config.codec.mimeType)
        val encoderCapabilities = codecCapabilities.encoderCapabilities
        if (encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
            mediaFormat.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            )
        }
        if (Build.VERSION.SDK_INT >= 30 &&
            codecCapabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        ) {
            mediaFormat.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        created.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = created.createInputSurface()
        codec = created
        created.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                handleOutput(codec, index, info)
            }

            override fun onError(codec: MediaCodec, exception: MediaCodec.CodecException) {
                if (running.get()) onFailure(MediaFailure.VIDEO_ENCODER)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                val parts = listOfNotNull(copyBuffer(format.getByteBuffer("csd-0")), copyBuffer(format.getByteBuffer("csd-1")))
                if (parts.isEmpty() || !normalizer.setCodecConfiguration(parts)) {
                    if (running.get()) onFailure(MediaFailure.VIDEO_ENCODER)
                }
            }
        }, encoderHandler)
        created.start()
    }

    private fun handleOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        try {
            if (info.size <= 0 || info.size > EncodedVideoNormalizer.MAX_ACCESS_UNIT_BYTES) return
            val source = codec.getOutputBuffer(index) ?: return
            if (info.offset < 0 || info.size < 0 || info.offset + info.size > source.capacity()) return
            source.position(info.offset)
            source.limit(info.offset + info.size)
            val bytes = ByteArray(info.size)
            source.get(bytes)
            val isConfiguration = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            if (isConfiguration) {
                if (!normalizer.setCodecConfiguration(listOf(bytes)) && running.get()) {
                    onFailure(MediaFailure.VIDEO_ENCODER)
                }
            } else {
                val accessUnit = normalizer.normalizeFrame(bytes, isKeyFrame)
                if (accessUnit == null) {
                    if (running.get()) onFailure(MediaFailure.VIDEO_ENCODER)
                } else if (!onAccessUnit(accessUnit, info.presentationTimeUs, isKeyFrame)) {
                    requestKeyFrame()
                }
            }
            bytes.fill(0)
        } catch (_: RuntimeException) {
            if (running.get()) onFailure(MediaFailure.VIDEO_ENCODER)
        } finally {
            try {
                codec.releaseOutputBuffer(index, false)
            } catch (_: IllegalStateException) {
                // Cleanup may race a final callback; the codec has already released this buffer.
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
        if (config.capability.constrainedHighSpeed) {
            openedCamera.createConstrainedHighSpeedCaptureSession(listOf(encoderSurface), callback, cameraHandler)
        } else {
            openedCamera.createCaptureSession(listOf(encoderSurface), callback, cameraHandler)
        }
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

    private fun chooseFpsRange(manager: CameraManager): Range<Int>? {
        val characteristics = manager.getCameraCharacteristics(config.cameraId)
        val target = config.profile.framesPerSecond
        val candidates = if (config.capability.constrainedHighSpeed) {
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            map.getHighSpeedVideoFpsRangesFor(android.util.Size(config.profile.width, config.profile.height)).toList()
        } else {
            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        }
        return candidates
            .filter { it.lower <= target && it.upper >= target }
            .minWithOrNull(compareBy<Range<Int>> { it.upper - it.lower }.thenByDescending { it.lower })
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
        inputSurface?.release()
        inputSurface = null
        captureFpsRange = null
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
        private const val KEY_FRAME_INTERVAL_SECONDS = 2f
        private const val KEY_FRAME_REQUEST_INTERVAL_NS = 500_000_000L
        private const val CAMERA_START_TIMEOUT_SECONDS = 5L
        private const val THREAD_JOIN_TIMEOUT_MILLISECONDS = 2_000L
        private const val MAX_CODEC_CONFIGURATION_BYTES = 256 * 1024
    }
}
