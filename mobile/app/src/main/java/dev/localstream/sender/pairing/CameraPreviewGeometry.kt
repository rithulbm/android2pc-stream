package dev.localstream.sender.pairing

import kotlin.math.abs
import kotlin.math.max

internal data class CameraOutputSize(val width: Int, val height: Int) {
    val pixels: Long = width.toLong() * height.toLong()
}

internal data class PreviewTransformSpec(
    val scaleX: Float,
    val scaleY: Float,
    val rotationDegrees: Float,
)

/** Pure geometry used by the Camera2 QR preview and its host-side tests. */
internal object CameraPreviewGeometry {
    private const val MAX_SCAN_PIXELS = 1280L * 720L

    fun chooseSharedOutputSize(
        privateOutputs: List<CameraOutputSize>,
        yuvOutputs: List<CameraOutputSize>,
        viewWidth: Int,
        viewHeight: Int,
        relativeRotationDegrees: Int,
    ): CameraOutputSize? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val yuvSet = yuvOutputs.filter(::isValid).toSet()
        val shared = privateOutputs.filter(::isValid).distinct().filter { it in yuvSet }
        if (shared.isEmpty()) return null

        val bounded = shared.filter { it.pixels <= MAX_SCAN_PIXELS }
        if (bounded.isEmpty()) return null
        val targetRatio = viewWidth.toDouble() / viewHeight.toDouble()
        return bounded.minWithOrNull(
            compareBy<CameraOutputSize> { size ->
                val displayedWidth = if (relativeRotationDegrees % 180 == 0) size.width else size.height
                val displayedHeight = if (relativeRotationDegrees % 180 == 0) size.height else size.width
                abs(displayedWidth.toDouble() / displayedHeight.toDouble() - targetRatio)
            }.thenByDescending { it.pixels },
        )
    }

    fun relativeRotation(
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean,
    ): Int {
        val sign = if (frontFacing) 1 else -1
        return (sensorOrientationDegrees - displayRotationDegrees * sign + 360) % 360
    }

    fun transform(
        viewWidth: Int,
        viewHeight: Int,
        previewWidth: Int,
        previewHeight: Int,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean,
    ): PreviewTransformSpec? {
        if (viewWidth <= 0 || viewHeight <= 0 || previewWidth <= 0 || previewHeight <= 0) return null
        val rotationRequired = relativeRotation(
            sensorOrientationDegrees,
            displayRotationDegrees,
            frontFacing,
        ) % 180 != 0

        val scaleX: Float
        val scaleY: Float
        if (sensorOrientationDegrees == 0) {
            scaleX = viewWidth.toFloat() / if (!rotationRequired) previewHeight else previewWidth
            scaleY = viewHeight.toFloat() / if (!rotationRequired) previewWidth else previewHeight
        } else {
            scaleX = viewWidth.toFloat() / if (rotationRequired) previewHeight else previewWidth
            scaleY = viewHeight.toFloat() / if (rotationRequired) previewWidth else previewHeight
        }
        if (scaleX <= 0f || scaleY <= 0f) return null
        val finalScale = max(scaleX, scaleY)
        val matrixScaleX: Float
        val matrixScaleY: Float
        if (rotationRequired) {
            matrixScaleX = finalScale / scaleX
            matrixScaleY = finalScale / scaleY
        } else {
            matrixScaleX = viewHeight.toFloat() / viewWidth.toFloat() / scaleY * finalScale
            matrixScaleY = viewWidth.toFloat() / viewHeight.toFloat() / scaleX * finalScale
        }
        return PreviewTransformSpec(
            scaleX = matrixScaleX,
            scaleY = matrixScaleY,
            rotationDegrees = -displayRotationDegrees.toFloat(),
        )
    }

    private fun isValid(size: CameraOutputSize): Boolean =
        size.width > 0 && size.height > 0 && size.pixels > 0
}
