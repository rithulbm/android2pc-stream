@file:Suppress("DEPRECATION")
@file:android.annotation.SuppressLint("SyntheticAccessor")

package dev.localstream.sender.pairing

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.atomic.AtomicBoolean

/** Camera2 QR scanner. Frames and decoded payloads are kept only in memory and are never logged. */
class PairingActivity : Activity() {
    private lateinit var preview: TextureView
    private lateinit var message: TextView
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewSize: CameraOutputSize? = null
    private var sensorOrientationDegrees = 0
    private var frontFacing = false
    private var displayListenerRegistered = false
    private val decodeInFlight = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val qrReader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.CHARACTER_SET to "UTF-8",
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (preview.display?.displayId == displayId) configurePreviewTransform()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(dev.localstream.sender.R.string.scan_pairing_code)
        buildUi()
        cameraThread = HandlerThread("pairing-camera").apply { start() }
        cameraHandler = Handler(cameraThread.looper)
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                requestCameraOrStart()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                configurePreviewTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    override fun onResume() {
        super.onResume()
        if (!displayListenerRegistered) {
            getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
            displayListenerRegistered = true
        }
        if (preview.isAvailable && camera == null) requestCameraOrStart()
    }

    override fun onPause() {
        if (displayListenerRegistered) {
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
        }
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        closeCamera()
        qrReader.reset()
        cameraThread.quitSafely()
        try {
            cameraThread.join(2_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            showMessage(getString(dev.localstream.sender.R.string.camera_scan_permission))
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        message = TextView(this).apply {
            text = getString(dev.localstream.sender.R.string.scan_pairing_code)
            textSize = 18f
            setPadding(0, 0, 0, dp(16))
        }
        preview = TextureView(this)
        val previewFrame = FrameLayout(this).apply {
            addView(
                preview,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        root.addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(previewFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun requestCameraOrStart() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        if (!preview.isAvailable || camera != null || isFinishing) return
        val manager = getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()
        if (cameraId == null) {
            showMessage(getString(dev.localstream.sender.R.string.camera_unavailable))
            return
        }
        val characteristics = try {
            manager.getCameraCharacteristics(cameraId)
        } catch (_: RuntimeException) {
            showMessage(getString(dev.localstream.sender.R.string.camera_unavailable))
            return
        }
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    createScannerSession(device, characteristics)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    camera = null
                    showMessage(getString(dev.localstream.sender.R.string.camera_unavailable))
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    camera = null
                    showMessage(getString(dev.localstream.sender.R.string.camera_unavailable))
                }
            }, cameraHandler)
        } catch (_: Exception) {
            showMessage(getString(dev.localstream.sender.R.string.camera_unavailable))
        }
    }

    private fun createScannerSession(device: CameraDevice, characteristics: CameraCharacteristics) {
        val texture = preview.surfaceTexture ?: run {
            device.close()
            if (camera === device) camera = null
            return
        }
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) {
            device.close()
            if (camera === device) camera = null
            showMessage(getString(dev.localstream.sender.R.string.camera_unavailable))
            return
        }
        sensorOrientationDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        frontFacing = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val displayRotationDegrees = displayRotationDegrees()
        val relativeRotation = CameraPreviewGeometry.relativeRotation(
            sensorOrientationDegrees,
            displayRotationDegrees,
            frontFacing,
        )
        val selectedSize = CameraPreviewGeometry.chooseSharedOutputSize(
            privateOutputs = map.getOutputSizes(SurfaceTexture::class.java).orEmpty().map {
                CameraOutputSize(it.width, it.height)
            },
            yuvOutputs = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().map {
                CameraOutputSize(it.width, it.height)
            },
            viewWidth = preview.width,
            viewHeight = preview.height,
            relativeRotationDegrees = relativeRotation,
        )
        if (selectedSize == null) {
            device.close()
            if (camera === device) camera = null
            showMessage(getString(dev.localstream.sender.R.string.camera_unavailable))
            return
        }
        previewSize = selectedSize
        texture.setDefaultBufferSize(selectedSize.width, selectedSize.height)
        configurePreviewTransform()
        val previewSurface = Surface(texture)
        this.previewSurface = previewSurface
        val reader = ImageReader.newInstance(
            selectedSize.width,
            selectedSize.height,
            ImageFormat.YUV_420_888,
            2,
        )
        imageReader = reader
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (!decodeInFlight.compareAndSet(false, true)) {
                image.close()
                return@setOnImageAvailableListener
            }
            try {
                decodeImage(image)
            } finally {
                image.close()
                decodeInFlight.set(false)
            }
        }, cameraHandler)
        try {
            device.createCaptureSession(
                listOf(previewSurface, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        if (camera !== device) {
                            captureSession.close()
                            return
                        }
                        session = captureSession
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface)
                            addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }.build()
                        try {
                            captureSession.setRepeatingRequest(request, null, cameraHandler)
                        } catch (_: Exception) {
                            showMessage(getString(dev.localstream.sender.R.string.camera_unavailable))
                        }
                    }

                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                        captureSession.close()
                        showMessage(getString(dev.localstream.sender.R.string.camera_unavailable))
                    }
                },
                cameraHandler,
            )
        } catch (_: Exception) {
            reader.close()
            imageReader = null
            previewSurface.release()
            this.previewSurface = null
            showMessage(getString(dev.localstream.sender.R.string.camera_unavailable))
        }
    }

    private fun decodeImage(image: Image) {
        if (finished.get()) return
        val plane = image.planes.firstOrNull() ?: return
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0 || width * height > MAX_LUMA_BYTES) return
        val luma = ByteArray(width * height)
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val start = buffer.position()
        val lastByte = start.toLong() +
            (height - 1).toLong() * rowStride.toLong() +
            (width - 1).toLong() * pixelStride.toLong()
        if (rowStride <= 0 || pixelStride <= 0 || lastByte < start || lastByte >= buffer.limit()) return
        for (row in 0 until height) {
            val rowOffset = start + row * rowStride
            for (column in 0 until width) {
                luma[row * width + column] = buffer.get(rowOffset + column * pixelStride)
            }
        }
        val payload = try {
            val source = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
            qrReader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            luma.fill(0)
            qrReader.reset()
        } ?: return
        when (val parsed = PairingPayloadParser().parse(payload, System.currentTimeMillis() / 1_000L)) {
            is PairingParseResult.Success -> savePairing(parsed.record)
            is PairingParseResult.Failure -> showMessage(
                if (parsed.error == PairingError.EXPIRED_QR) {
                    getString(dev.localstream.sender.R.string.expired_pairing_code)
                } else {
                    getString(dev.localstream.sender.R.string.invalid_pairing_code)
                },
            )
        }
    }

    private fun savePairing(record: PairingRecord) {
        if (!finished.compareAndSet(false, true)) {
            record.destroy()
            return
        }
        try {
            AndroidPairingStore.create(this) { System.currentTimeMillis() / 1_000L }.save(record)
            runOnUiThread {
                setResult(RESULT_OK)
                finish()
            }
        } catch (_: RuntimeException) {
            finished.set(false)
            showMessage(getString(dev.localstream.sender.R.string.pairing_save_failed))
        } finally {
            record.destroy()
        }
    }

    private fun closeCamera() {
        session?.close()
        session = null
        camera?.close()
        camera = null
        imageReader?.close()
        imageReader = null
        previewSurface?.release()
        previewSurface = null
        previewSize = null
    }

    private fun configurePreviewTransform() {
        val selectedSize = previewSize ?: return
        val spec = CameraPreviewGeometry.transform(
            viewWidth = preview.width,
            viewHeight = preview.height,
            previewWidth = selectedSize.width,
            previewHeight = selectedSize.height,
            sensorOrientationDegrees = sensorOrientationDegrees,
            displayRotationDegrees = displayRotationDegrees(),
            frontFacing = frontFacing,
        ) ?: return
        val centerX = preview.width / 2f
        val centerY = preview.height / 2f
        val transform = Matrix().apply {
            setScale(spec.scaleX, spec.scaleY, centerX, centerY)
            postRotate(spec.rotationDegrees, centerX, centerY)
            if (frontFacing) postScale(-1f, 1f, centerX, centerY)
        }
        preview.setTransform(transform)
    }

    private fun displayRotationDegrees(): Int = when (preview.display?.rotation ?: Surface.ROTATION_0) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun showMessage(text: String) {
        runOnUiThread { message.text = text }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 70
        private const val MAX_LUMA_BYTES = 1_000_000
    }
}
