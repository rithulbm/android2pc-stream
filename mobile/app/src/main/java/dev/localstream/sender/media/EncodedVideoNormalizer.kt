package dev.localstream.sender.media

import java.io.ByteArrayOutputStream

/** Converts bounded MediaCodec Annex-B or 4-byte length-prefixed access units to Annex-B. */
class EncodedVideoNormalizer(private val maximumAccessUnitBytes: Int = MAX_ACCESS_UNIT_BYTES) {
    private var codecConfiguration = ByteArray(0)

    fun hasCodecConfiguration(): Boolean = codecConfiguration.isNotEmpty()

    fun setCodecConfiguration(parts: List<ByteArray>): Boolean {
        val normalized = parts.map { normalizeConfigurationPart(it) ?: return false }
        val total = normalized.sumOf { it.size }
        if (normalized.isEmpty() || total <= 0 || total > MAX_CONFIGURATION_BYTES) return false
        codecConfiguration.fill(0)
        codecConfiguration = normalized.fold(ByteArray(0)) { output, bytes -> output + bytes }
        return true
    }

    fun normalizeFrame(bytes: ByteArray, keyFrame: Boolean): ByteArray? {
        val frame = normalize(bytes) ?: return null
        if (!keyFrame || codecConfiguration.isEmpty()) return frame
        if (codecConfiguration.size + frame.size > maximumAccessUnitBytes) return null
        return codecConfiguration + frame
    }

    /**
     * True when the buffer's first video slice NAL is an H.264 IDR slice or an HEVC
     * IRAP (BLA/IDR/CRA) NAL. Recovery path for vendor encoders that emit IDRs
     * without BUFFER_FLAG_KEY_FRAME; scans only leading NAL headers up to the first
     * slice, so cost is bounded regardless of access-unit size.
     */
    fun containsRandomAccessNal(bytes: ByteArray, hevc: Boolean): Boolean {
        var offset = 0
        while (offset + START_CODE.size <= bytes.size || offset + 3 <= bytes.size) {
            val startCodeLength = when {
                offset + 4 <= bytes.size &&
                    bytes[offset] == 0.toByte() && bytes[offset + 1] == 0.toByte() &&
                    bytes[offset + 2] == 0.toByte() && bytes[offset + 3] == 1.toByte() -> 4
                offset + 3 <= bytes.size &&
                    bytes[offset] == 0.toByte() && bytes[offset + 1] == 0.toByte() &&
                    bytes[offset + 2] == 1.toByte() -> 3
                else -> return false
            }
            val headerOffset = offset + startCodeLength
            if (headerOffset >= bytes.size) return false
            val header = bytes[headerOffset].toInt() and 0xFF
            if (header and 0x80 != 0) return false // forbidden_zero_bit must be 0
            if (hevc) {
                val nalType = (header shr 1) and 0x3F
                if (nalType < 32) return nalType in IRAP_NAL_TYPE_MIN..IRAP_NAL_TYPE_MAX
            } else {
                val nalType = header and 0x1F
                if (nalType in 1..5) return nalType == H264_IDR_SLICE_TYPE
            }
            offset = nextStartCode(bytes, headerOffset + 2) ?: return false
        }
        return false
    }

    fun clear() {
        codecConfiguration.fill(0)
        codecConfiguration = ByteArray(0)
    }

    private fun nextStartCode(bytes: ByteArray, from: Int): Int? {
        var index = from
        var zeros = 0
        while (index < bytes.size) {
            when (bytes[index].toInt()) {
                0 -> zeros++
                1 -> if (zeros >= 2) return index - 2 else zeros = 0
                else -> zeros = 0
            }
            index++
        }
        return null
    }

    private fun normalizeConfigurationPart(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty() || bytes.size > MAX_CONFIGURATION_BYTES) return null
        return when {
            startsWithFourByteStartCode(bytes) || isGenuineAnnexB(bytes) -> bytes.copyOf()
            isValidAvcc(bytes) -> convertAvcc(bytes)
            else -> null
        }
    }

    private fun normalize(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty() || bytes.size > maximumAccessUnitBytes) return null
        // Per-buffer strict detection with no cross-frame lock (one mismatched
        // CSD/frame pairing used to reject every later access unit). Order matters:
        // a genuine 4-byte start code is always Annex-B because a real AVCC stream
        // cannot begin with a 1-byte first NAL; otherwise the complete AVCC length
        // walk decides before a mere 3-byte start-code prefix is trusted, so AVCC
        // lengths of 256-511 bytes (prefix 00 00 01 XX) are never mistaken for
        // start codes.
        return when {
            startsWithFourByteStartCode(bytes) -> bytes.copyOf()
            isValidAvcc(bytes) -> convertAvcc(bytes)
            isGenuineAnnexB(bytes) -> bytes.copyOf()
            else -> null
        }
    }

    private fun convertAvcc(bytes: ByteArray): ByteArray? {
        val output = ByteArrayOutputStream(bytes.size + 32)
        var offset = 0
        while (offset < bytes.size) {
            if (bytes.size - offset < LENGTH_PREFIX_BYTES) return null
            val length = readLengthPrefix(bytes, offset)
            offset += LENGTH_PREFIX_BYTES
            if (length <= 0 || length > bytes.size - offset || !hasValidNalHeader(bytes, offset)) return null
            if (output.size() + length + START_CODE.size > maximumAccessUnitBytes) return null
            output.write(START_CODE)
            output.write(bytes, offset, length)
            offset += length
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun isValidAvcc(bytes: ByteArray): Boolean {
        if (bytes.size < LENGTH_PREFIX_BYTES + 1) return false
        var offset = 0
        var nalCount = 0
        while (offset < bytes.size) {
            if (bytes.size - offset < LENGTH_PREFIX_BYTES) return false
            val length = readLengthPrefix(bytes, offset)
            offset += LENGTH_PREFIX_BYTES
            if (length <= 0 || length > bytes.size - offset) return false
            if (!hasValidNalHeader(bytes, offset)) return false
            offset += length
            nalCount++
        }
        return offset == bytes.size && nalCount > 0
    }

    private fun isGenuineAnnexB(bytes: ByteArray): Boolean {
        val headerOffset = when {
            startsWithFourByteStartCode(bytes) -> 4
            startsWithThreeByteStartCode(bytes) -> 3
            else -> return false
        }
        return hasValidNalHeader(bytes, headerOffset)
    }

    private fun startsWithFourByteStartCode(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0.toByte() &&
            bytes[1] == 0.toByte() &&
            bytes[2] == 0.toByte() &&
            bytes[3] == 1.toByte()

    private fun startsWithThreeByteStartCode(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0.toByte() &&
            bytes[1] == 0.toByte() &&
            bytes[2] == 1.toByte()

    private fun hasValidNalHeader(bytes: ByteArray, headerOffset: Int): Boolean {
        if (headerOffset >= bytes.size) return false
        val header = bytes[headerOffset].toInt() and 0xFF
        if (header and 0x80 != 0) return false // forbidden_zero_bit must be 0

        val avcType = header and 0x1F
        if (avcType in 1..23) return true

        val hevcType = (header shr 1) and 0x3F
        if (hevcType !in 0..40 || headerOffset + 1 >= bytes.size) return false
        val temporalIdPlusOne = bytes[headerOffset + 1].toInt() and 0x07
        return temporalIdPlusOne != 0
    }

    private fun readLengthPrefix(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    companion object {
        const val MAX_ACCESS_UNIT_BYTES: Int = 8 * 1024 * 1024
        private const val MAX_CONFIGURATION_BYTES = 64 * 1024
        private const val LENGTH_PREFIX_BYTES = 4
        private const val H264_IDR_SLICE_TYPE = 5
        private const val IRAP_NAL_TYPE_MIN = 16
        private const val IRAP_NAL_TYPE_MAX = 23
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
