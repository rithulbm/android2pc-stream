package dev.localstream.sender.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EncodedAccessUnitAssemblerTest {
    @Test
    fun emitsSingleCompleteBufferImmediately() {
        val assembler = EncodedAccessUnitAssembler(32)

        val output = assembler.offer(byteArrayOf(1, 2, 3), 100L, 1, partialFrame = false)

        requireNotNull(output)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.bytes)
        assertEquals(100L, output.presentationTimeUs)
        assertEquals(1, output.flags)
    }

    @Test
    fun joinsFragmentsAndPreservesFirstTimestampAndAllFlags() {
        val assembler = EncodedAccessUnitAssembler(32)

        assertNull(assembler.offer(byteArrayOf(1, 2), 100L, 1, partialFrame = true))
        assertNull(assembler.offer(byteArrayOf(3), 101L, 2, partialFrame = true))
        val output = assembler.offer(byteArrayOf(4, 5), 102L, 4, partialFrame = false)

        requireNotNull(output)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), output.bytes)
        assertEquals(100L, output.presentationTimeUs)
        assertEquals(7, output.flags)
    }

    @Test
    fun clearDropsPendingFragments() {
        val assembler = EncodedAccessUnitAssembler(32)
        assertNull(assembler.offer(byteArrayOf(1, 2), 100L, 1, partialFrame = true))

        assembler.clear()
        val output = assembler.offer(byteArrayOf(9), 200L, 4, partialFrame = false)

        requireNotNull(output)
        assertArrayEquals(byteArrayOf(9), output.bytes)
        assertEquals(200L, output.presentationTimeUs)
        assertEquals(4, output.flags)
    }

    @Test
    fun rejectsOversizedCombinedFrame() {
        val assembler = EncodedAccessUnitAssembler(4)
        assertNull(assembler.offer(byteArrayOf(1, 2, 3), 100L, 0, partialFrame = true))

        assertThrows(IllegalArgumentException::class.java) {
            assembler.offer(byteArrayOf(4, 5), 101L, 0, partialFrame = false)
        }
    }
}
