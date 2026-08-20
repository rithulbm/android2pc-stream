package dev.localstream.sender.media

import dev.localstream.sender.quality.VideoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodedVideoNormalizerTest {
    @Test
    fun preservesAnnexBFrames() {
        val input = byteArrayOf(0, 0, 0, 1, 0x41, 1, 2, 3)

        val output = avc().normalizeFrame(input, keyFrame = false)

        assertArrayEquals(input, output)
        assertFalse(input === output)
    }

    @Test
    fun convertsMultipleLengthPrefixedNals() {
        val input = byteArrayOf(
            0, 0, 0, 2, 0x67, 1,
            0, 0, 0, 3, 0x68, 2, 3,
        )

        val output = avc().normalizeFrame(input, keyFrame = false)

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2, 3),
            output,
        )
    }

    @Test
    fun prependsCompleteAvcConfigurationOnlyToKeyFrames() {
        val normalizer = avc()
        val configuration = AVC_SPS + AVC_PPS
        assertTrue(normalizer.setCodecConfiguration(listOf(configuration)))

        val regular = normalizer.normalizeFrame(byteArrayOf(0, 0, 1, 0x41, 2), keyFrame = false)
        val key = normalizer.normalizeFrame(AVC_IDR, keyFrame = true)

        assertArrayEquals(byteArrayOf(0, 0, 1, 0x41, 2), regular)
        assertArrayEquals(configuration + AVC_IDR, key)
    }

    @Test
    fun rejectsTruncatedZeroAndOversizedLengthPrefixes() {
        val normalizer = EncodedVideoNormalizer(VideoCodec.AVC, maximumAccessUnitBytes = 32)

        assertNull(normalizer.normalizeFrame(byteArrayOf(0, 0, 0), keyFrame = false))
        assertNull(normalizer.normalizeFrame(byteArrayOf(0, 0, 0, 0), keyFrame = false))
        assertNull(normalizer.normalizeFrame(byteArrayOf(0, 0, 0, 5, 1, 2), keyFrame = false))
        assertNull(normalizer.normalizeFrame(ByteArray(33) { 1 }, keyFrame = false))
    }

    @Test
    fun clearDropsRecoveredConfigurationDeterministically() {
        val normalizer = avc()
        assertTrue(normalizer.setCodecConfiguration(listOf(AVC_SPS + AVC_PPS)))
        normalizer.clear()

        val output = normalizer.normalizeFrame(AVC_IDR, keyFrame = true)

        assertNotNull(output)
        assertArrayEquals(AVC_IDR, output)
        assertFalse(normalizer.hasCodecConfiguration())
    }

    @Test
    fun rejectsInvalidConfigurationWithoutDiscardingPreviousValidConfiguration() {
        val normalizer = avc()
        val valid = AVC_SPS + AVC_PPS
        assertTrue(normalizer.setCodecConfiguration(listOf(valid)))

        assertFalse(normalizer.setCodecConfiguration(listOf(byteArrayOf(0, 0, 0, 5, 1))))
        val key = normalizer.normalizeFrame(AVC_IDR, keyFrame = true)

        assertArrayEquals(valid + AVC_IDR, key)
    }

    @Test
    fun convertsAvccNalWhoseLengthLooksLikeAThreeByteStartCode() {
        val payload = ByteArray(300) { index -> if (index == 0) 0x41 else (index % 251).toByte() }
        val input = byteArrayOf(0, 0, 1, 0x2C) + payload

        val output = avc().normalizeFrame(input, keyFrame = false)

        assertArrayEquals(START_CODE + payload, output)
    }

    @Test
    fun preservesThreeByteAnnexBStartCodes() {
        val input = byteArrayOf(0, 0, 1, 0x41, 2, 3, 4)

        val output = avc().normalizeFrame(input, keyFrame = false)

        assertArrayEquals(input, output)
        assertFalse(input === output)
    }

    @Test
    fun preservesThreeByteAnnexBWithMultipleNals() {
        val input = byteArrayOf(0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2, 3)

        val output = avc().normalizeFrame(input, keyFrame = false)

        assertArrayEquals(input, output)
    }

    @Test
    fun concatenatesHevcConfigurationOntoKeyFrames() {
        val normalizer = hevc()
        val configuration = HEVC_VPS + HEVC_SPS + HEVC_PPS
        assertTrue(normalizer.setCodecConfiguration(listOf(configuration)))
        assertTrue(normalizer.hasCodecConfiguration())

        val output = normalizer.normalizeFrame(HEVC_IDR, keyFrame = true)

        assertArrayEquals(configuration + HEVC_IDR, output)
    }

    @Test
    fun locksFourByteLengthPrefixAfterFirstValidUnit() {
        val first = byteArrayOf(0, 0, 0, 2, 0x67, 1)
        val secondPayload = ByteArray(256) { index -> if (index == 0) 0x41 else 7 }
        val second = byteArrayOf(0, 0, 1, 0x00) + secondPayload
        val normalizer = avc()

        assertArrayEquals(START_CODE + byteArrayOf(0x67, 1), normalizer.normalizeFrame(first, keyFrame = false))
        assertArrayEquals(START_CODE + secondPayload, normalizer.normalizeFrame(second, keyFrame = false))
    }

    @Test
    fun recoversHevcParametersFromFirstInBandKeyframeAndReinjectsThemForever() {
        val normalizer = hevc()
        val configuration = HEVC_VPS + HEVC_SPS + HEVC_PPS
        val firstEncoderKeyframe = configuration + HEVC_IDR

        val first = normalizer.normalizeAccessUnit(firstEncoderKeyframe, keyFrameHint = false)
        assertNotNull(first)
        assertTrue(first!!.keyFrame)
        assertTrue(normalizer.hasCodecConfiguration())
        assertArrayEquals(configuration + firstEncoderKeyframe, first.bytes)

        val later = normalizer.normalizeAccessUnit(HEVC_IDR, keyFrameHint = true)
        assertNotNull(later)
        assertArrayEquals(configuration + HEVC_IDR, later!!.bytes)
    }

    @Test
    fun recoversAvcParametersFromFirstInBandKeyframeAndReinjectsThemForever() {
        val normalizer = avc()
        val configuration = AVC_SPS + AVC_PPS
        normalizer.normalizeAccessUnit(configuration + AVC_IDR, keyFrameHint = true)

        val later = normalizer.normalizeAccessUnit(AVC_IDR, keyFrameHint = true)

        assertTrue(normalizer.hasCodecConfiguration())
        assertArrayEquals(configuration + AVC_IDR, later!!.bytes)
    }

    @Test
    fun mergesSeparatePartialCodecConfigBuffersWithoutCallerAccumulation() {
        val normalizer = hevc()

        assertFalse(normalizer.setCodecConfiguration(listOf(HEVC_VPS)))
        assertFalse(normalizer.setCodecConfiguration(listOf(HEVC_SPS)))
        assertTrue(normalizer.setCodecConfiguration(listOf(HEVC_PPS)))

        assertArrayEquals(
            HEVC_VPS + HEVC_SPS + HEVC_PPS + HEVC_IDR,
            normalizer.normalizeFrame(HEVC_IDR, keyFrame = true),
        )
    }

    @Test
    fun malformedCsdCannotPoisonLaterInBandRecovery() {
        val normalizer = hevc()
        assertFalse(normalizer.setCodecConfiguration(listOf(byteArrayOf(1, 2, 3, 4, 5))))

        normalizer.normalizeAccessUnit(HEVC_VPS + HEVC_SPS + HEVC_PPS + HEVC_IDR, keyFrameHint = true)
        val later = normalizer.normalizeFrame(HEVC_IDR, keyFrame = true)

        assertTrue(normalizer.hasCodecConfiguration())
        assertArrayEquals(HEVC_VPS + HEVC_SPS + HEVC_PPS + HEVC_IDR, later)
    }

    @Test
    fun detectsRandomAccessNalEvenWhenMediaCodecFlagIsMissing() {
        val normalizer = hevc()
        assertTrue(normalizer.setCodecConfiguration(listOf(HEVC_VPS + HEVC_SPS + HEVC_PPS)))

        val output = normalizer.normalizeAccessUnit(HEVC_IDR, keyFrameHint = false)

        assertNotNull(output)
        assertTrue(output!!.keyFrame)
    }

    @Test
    fun acceptsRawSingleParameterSetBuffersFromVendorCodec() {
        val normalizer = avc()

        assertFalse(normalizer.setCodecConfiguration(listOf(AVC_SPS.copyOfRange(4, AVC_SPS.size))))
        assertTrue(normalizer.setCodecConfiguration(listOf(AVC_PPS.copyOfRange(4, AVC_PPS.size))))

        assertArrayEquals(AVC_SPS + AVC_PPS + AVC_IDR, normalizer.normalizeFrame(AVC_IDR, keyFrame = true))
    }

    @Test
    fun acceptsAvcDecoderConfigurationRecordAsDefensiveVendorFallback() {
        val normalizer = avc()
        val record = avcConfigurationRecord(
            AVC_SPS.copyOfRange(4, AVC_SPS.size),
            AVC_PPS.copyOfRange(4, AVC_PPS.size),
        )

        assertTrue(normalizer.setCodecConfiguration(listOf(record)))

        assertArrayEquals(AVC_SPS + AVC_PPS + AVC_IDR, normalizer.normalizeFrame(AVC_IDR, keyFrame = true))
    }

    @Test
    fun acceptsHevcDecoderConfigurationRecordAsDefensiveVendorFallback() {
        val normalizer = hevc()
        val record = hevcConfigurationRecord(
            HEVC_VPS.copyOfRange(4, HEVC_VPS.size),
            HEVC_SPS.copyOfRange(4, HEVC_SPS.size),
            HEVC_PPS.copyOfRange(4, HEVC_PPS.size),
        )

        assertTrue(normalizer.setCodecConfiguration(listOf(record)))

        assertArrayEquals(
            HEVC_VPS + HEVC_SPS + HEVC_PPS + HEVC_IDR,
            normalizer.normalizeFrame(HEVC_IDR, keyFrame = true),
        )
    }

    @Test
    fun supportsTwoByteLengthPrefixedVendorAccessUnits() {
        val payload = byteArrayOf(0x41, 1, 2, 3)
        val input = byteArrayOf(0, payload.size.toByte()) + payload

        assertArrayEquals(START_CODE + payload, avc().normalizeFrame(input, keyFrame = false))
    }

    @Test
    fun rejectsInvalidHevcTemporalIdHeader() {
        val invalid = START_CODE + byteArrayOf(0x26, 0x00)

        assertNull(hevc().normalizeFrame(invalid, keyFrame = true))
    }

    private fun avc(): EncodedVideoNormalizer = EncodedVideoNormalizer(VideoCodec.AVC)
    private fun hevc(): EncodedVideoNormalizer = EncodedVideoNormalizer(VideoCodec.HEVC)

    private fun avcConfigurationRecord(sps: ByteArray, pps: ByteArray): ByteArray =
        byteArrayOf(
            1,
            100,
            0,
            40,
            0xFF.toByte(),
            0xE1.toByte(),
            ((sps.size ushr 8) and 0xFF).toByte(),
            (sps.size and 0xFF).toByte(),
        ) + sps + byteArrayOf(
            1,
            ((pps.size ushr 8) and 0xFF).toByte(),
            (pps.size and 0xFF).toByte(),
        ) + pps

    private fun hevcConfigurationRecord(vps: ByteArray, sps: ByteArray, pps: ByteArray): ByteArray {
        val header = ByteArray(23)
        header[0] = 1
        header[22] = 3
        return header + hevcArray(32, vps) + hevcArray(33, sps) + hevcArray(34, pps)
    }

    private fun hevcArray(type: Int, nal: ByteArray): ByteArray =
        byteArrayOf(
            type.toByte(),
            0,
            1,
            ((nal.size ushr 8) and 0xFF).toByte(),
            (nal.size and 0xFF).toByte(),
        ) + nal

    companion object {
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
        private val AVC_SPS = START_CODE + byteArrayOf(0x67, 0x64, 0x00, 0x28)
        private val AVC_PPS = START_CODE + byteArrayOf(0x68, 0x01, 0x02)
        private val AVC_IDR = START_CODE + byteArrayOf(0x65, 0x11, 0x22)
        private val HEVC_VPS = START_CODE + byteArrayOf(0x40, 0x01, 0x0C)
        private val HEVC_SPS = START_CODE + byteArrayOf(0x42, 0x01, 0x01)
        private val HEVC_PPS = START_CODE + byteArrayOf(0x44, 0x01, 0x01)
        private val HEVC_IDR = START_CODE + byteArrayOf(0x26, 0x01, 0x55)
    }
}
