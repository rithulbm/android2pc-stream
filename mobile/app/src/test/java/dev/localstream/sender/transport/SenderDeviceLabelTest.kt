package dev.localstream.sender.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderDeviceLabelTest {
    @Test
    fun combinesManufacturerAndModelWithoutRepeatingManufacturer() {
        assertEquals("Google Pixel 9", SenderDeviceLabel.from("Google", "Pixel 9"))
        assertEquals("Samsung Galaxy S25", SenderDeviceLabel.from("Samsung", "Samsung Galaxy S25"))
    }

    @Test
    fun stripsControlAndProtocolCharactersAndBoundsLength() {
        val label = SenderDeviceLabel.from("Acme/../../\n", "Phone:%${"x".repeat(80)}")

        assertTrue(label.length <= 48)
        assertFalse(label.contains('/'))
        assertFalse(label.contains(':'))
        assertFalse(label.contains('\n'))
    }

    @Test
    fun emptyOrUnsupportedValuesUseFriendlyFallback() {
        assertEquals("Android phone", SenderDeviceLabel.from("/", "\u202e"))
    }
}
