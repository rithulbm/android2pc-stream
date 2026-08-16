package dev.localstream.sender.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodedVideoNormalizerTest {
    @Test
    fun preservesAnnexBFrames() {
        val input = byteArrayOf(0, 0, 0, 1, 0x26, 1, 2, 3)

        val output = EncodedVideoNormalizer().normalizeFrame(input, keyFrame = false)

        assertArrayEquals(input, output)
        assertFalse(input === output)
    }

    @Test
    fun convertsMultipleLengthPrefixedNals() {
        val input = byteArrayOf(
            0, 0, 0, 2, 0x67, 1,
            0, 0, 0, 3, 0x68, 2, 3,
        )

        val output = EncodedVideoNormalizer().normalizeFrame(input, keyFrame = false)

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2, 3),
            output,
        )
    }

    @Test
    fun prependsConfigurationOnlyToKeyFrames() {
        val normalizer = EncodedVideoNormalizer()
        val configuration = byteArrayOf(0, 0, 0, 1, 0x67, 9)
        assertTrue(normalizer.setCodecConfiguration(listOf(configuration)))

        val regular = normalizer.normalizeFrame(byteArrayOf(0, 0, 1, 0x41, 2), keyFrame = false)
        val key = normalizer.normalizeFrame(byteArrayOf(0, 0, 1, 0x65, 3), keyFrame = true)

        assertArrayEquals(byteArrayOf(0, 0, 1, 0x41, 2), regular)
        assertArrayEquals(configuration + byteArrayOf(0, 0, 1, 0x65, 3), key)
    }

    @Test
    fun rejectsTruncatedZeroAndOversizedLengthPrefixes() {
        val normalizer = EncodedVideoNormalizer(maximumAccessUnitBytes = 32)

        assertNull(normalizer.normalizeFrame(byteArrayOf(0, 0, 0), keyFrame = false))
        assertNull(normalizer.normalizeFrame(byteArrayOf(0, 0, 0, 0), keyFrame = false))
        assertNull(normalizer.normalizeFrame(byteArrayOf(0, 0, 0, 5, 1, 2), keyFrame = false))
        assertNull(normalizer.normalizeFrame(ByteArray(33) { 1 }, keyFrame = false))
    }

    @Test
    fun configurationReplacementAndClearAreDeterministic() {
        val normalizer = EncodedVideoNormalizer()
        assertTrue(normalizer.setCodecConfiguration(listOf(byteArrayOf(0, 0, 1, 0x67))))
        assertTrue(normalizer.setCodecConfiguration(listOf(byteArrayOf(0, 0, 1, 0x68))))
        normalizer.clear()

        val output = normalizer.normalizeFrame(byteArrayOf(0, 0, 1, 0x65), keyFrame = true)

        assertNotNull(output)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0x65), output)
    }

    @Test
    fun rejectsInvalidConfigurationWithoutDiscardingPreviousValidConfiguration() {
        val normalizer = EncodedVideoNormalizer()
        val valid = byteArrayOf(0, 0, 1, 0x67)
        assertTrue(normalizer.setCodecConfiguration(listOf(valid)))

        assertFalse(normalizer.setCodecConfiguration(listOf(byteArrayOf(0, 0, 0, 5, 1))))
        val key = normalizer.normalizeFrame(byteArrayOf(0, 0, 1, 0x65), keyFrame = true)

        assertArrayEquals(valid + byteArrayOf(0, 0, 1, 0x65), key)
    }

    @Test
    fun convertsAvccNalWhoseLengthLooksLikeAThreeByteStartCode() {
        val payload = ByteArray(300) { index -> if (index == 0) 0x41 else (index % 251).toByte() }
        val input = byteArrayOf(0, 0, 1, 0x2C) + payload

        val output = EncodedVideoNormalizer().normalizeFrame(input, keyFrame = false)

        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + payload, output)
    }

    @Test
    fun preservesThreeByteAnnexBStartCodes() {
        val input = byteArrayOf(0, 0, 1, 0x41, 2, 3, 4)

        val output = EncodedVideoNormalizer().normalizeFrame(input, keyFrame = false)

        assertArrayEquals(input, output)
        assertFalse(input === output)
    }

    @Test
    fun preservesThreeByteAnnexBWithMultipleNals() {
        val input = byteArrayOf(0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2, 3)

        val output = EncodedVideoNormalizer().normalizeFrame(input, keyFrame = false)

        assertArrayEquals(input, output)
    }

    @Test
    fun concatenatesHevcConfigurationOntoKeyFrames() {
        val vps = byteArrayOf(0, 0, 0, 1, 0x40, 1)
        val sps = byteArrayOf(0, 0, 0, 1, 0x42, 2)
        val pps = byteArrayOf(0, 0, 0, 1, 0x44, 3)
        val normalizer = EncodedVideoNormalizer()
        assertTrue(normalizer.setCodecConfiguration(listOf(vps + sps + pps)))
        assertTrue(normalizer.hasCodecConfiguration())

        val key = byteArrayOf(0, 0, 0, 1, 0x26, 4)
        val output = normalizer.normalizeFrame(key, keyFrame = true)

        assertArrayEquals(vps + sps + pps + key, output)
    }

    @Test
    fun locksAvccFormatAfterFirstValidLengthPrefixedUnit() {
        val first = byteArrayOf(0, 0, 0, 2, 0x67, 1)
        val secondPayload = ByteArray(256) { index -> if (index == 0) 0x41 else 7 }
        val second = byteArrayOf(0, 0, 1, 0x00) + secondPayload
        val normalizer = EncodedVideoNormalizer()

        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 1), normalizer.normalizeFrame(first, keyFrame = false))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + secondPayload, normalizer.normalizeFrame(second, keyFrame = false))
    }
}
