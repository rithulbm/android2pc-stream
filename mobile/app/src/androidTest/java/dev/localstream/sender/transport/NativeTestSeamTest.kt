package dev.localstream.sender.transport

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeTestSeamTest {
    @Test
    fun nativeQueueMuxAndConfigurationInvariantsHold() {
        assertEquals(0, NativeTestSeam.run())
    }
}
