package dev.localstream.sender.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPreviewGeometryTest {
    @Test
    fun `portrait back camera keeps aspect ratio with center crop`() {
        val transform = CameraPreviewGeometry.transform(
            viewWidth = 1080,
            viewHeight = 1920,
            previewWidth = 640,
            previewHeight = 480,
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 0,
            frontFacing = false,
        )

        requireNotNull(transform)
        assertEquals(4f / 3f, transform.scaleX, 0.001f)
        assertEquals(1f, transform.scaleY, 0.001f)
        assertEquals(0f, transform.rotationDegrees, 0.001f)
    }

    @Test
    fun `relative rotation handles rear front and display rotation`() {
        assertEquals(90, CameraPreviewGeometry.relativeRotation(90, 0, frontFacing = false))
        assertEquals(180, CameraPreviewGeometry.relativeRotation(90, 90, frontFacing = false))
        assertEquals(180, CameraPreviewGeometry.relativeRotation(270, 90, frontFacing = true))
    }

    @Test
    fun `shared size is supported by both outputs bounded and closest to view`() {
        val privateSizes = listOf(
            CameraOutputSize(1920, 1080),
            CameraOutputSize(1280, 720),
            CameraOutputSize(640, 480),
        )
        val yuvSizes = listOf(CameraOutputSize(1280, 720), CameraOutputSize(640, 480))

        val selected = CameraPreviewGeometry.chooseSharedOutputSize(
            privateSizes,
            yuvSizes,
            viewWidth = 1080,
            viewHeight = 1920,
            relativeRotationDegrees = 90,
        )

        assertEquals(CameraOutputSize(1280, 720), selected)
        assertTrue(requireNotNull(selected).pixels <= 1280L * 720L)
    }

    @Test
    fun `unsupported and invalid geometry fails closed`() {
        assertNull(
            CameraPreviewGeometry.chooseSharedOutputSize(
                listOf(CameraOutputSize(640, 480)),
                listOf(CameraOutputSize(1280, 720)),
                1080,
                1920,
                90,
            ),
        )
        assertNull(CameraPreviewGeometry.transform(0, 1920, 640, 480, 90, 0, false))
    }
}
