package dev.localstream.sender.media

import dev.localstream.sender.quality.VideoCodec
import java.io.ByteArrayOutputStream

data class NormalizedVideoAccessUnit(
    val bytes: ByteArray,
    val keyFrame: Boolean,
)

/**
 * Converts bounded MediaCodec Annex-B or length-prefixed access units to Annex-B and
 * persists AVC/HEVC decoder parameter sets so every random-access frame is independently decodable.
 */
class EncodedVideoNormalizer(
    private val codec: VideoCodec,
    private val maximumAccessUnitBytes: Int = MAX_ACCESS_UNIT_BYTES,
) {
    private val parameterSets = linkedMapOf<Int, List<ByteArray>>()
    private var accessUnitFormat = AccessUnitFormat.UNKNOWN

    fun hasCodecConfiguration(): Boolean = requiredParameterSetTypes().all { parameterSets.containsKey(it) }

    /**
     * Merges any valid parameter sets found in MediaFormat CSD or BUFFER_FLAG_CODEC_CONFIG buffers.
     * Invalid or partial vendor buffers never discard parameter sets that were already recovered.
     *
     * @return true only when a complete decoder configuration is available after this update.
     */
    fun setCodecConfiguration(parts: List<ByteArray>): Boolean {
        var observed = false
        for (part in parts) {
            val normalized = normalizeConfigurationPart(part) ?: continue
            try {
                observed = cacheParameterSets(normalized) || observed
            } finally {
                normalized.fill(0)
            }
        }
        return observed && hasCodecConfiguration()
    }

    /**
     * Normalizes one encoded access unit and independently detects random-access NAL units.
     *
     * Parameter sets observed in-band are cached before the keyframe is emitted. Once the cache
     * is complete, a canonical VPS/SPS/PPS or SPS/PPS prefix is prepended to any random-access
     * frame that does not already carry a complete decoder configuration. A decoder attaching
     * after any reconnect can therefore start from the next keyframe without depending on a
     * one-time encoder CSD callback.
     */
    fun normalizeAccessUnit(bytes: ByteArray, keyFrameHint: Boolean): NormalizedVideoAccessUnit? {
        val frame = normalize(bytes) ?: return null
        val frameHasCompleteConfiguration = containsCompleteParameterSets(frame)
        cacheParameterSets(frame)
        val keyFrame = keyFrameHint || containsRandomAccessNal(frame)
        if (!keyFrame || !hasCodecConfiguration() || frameHasCompleteConfiguration) {
            return NormalizedVideoAccessUnit(frame, keyFrame)
        }

        val configuration = buildCodecConfiguration() ?: return NormalizedVideoAccessUnit(frame, keyFrame)
        if (configuration.size > maximumAccessUnitBytes - frame.size) {
            configuration.fill(0)
            frame.fill(0)
            return null
        }
        val output = ByteArray(configuration.size + frame.size)
        configuration.copyInto(output)
        frame.copyInto(output, configuration.size)
        configuration.fill(0)
        frame.fill(0)
        return NormalizedVideoAccessUnit(output, keyFrame)
    }

    fun normalizeFrame(bytes: ByteArray, keyFrame: Boolean): ByteArray? =
        normalizeAccessUnit(bytes, keyFrame)?.bytes

    fun clear() {
        parameterSets.values.flatten().forEach { it.fill(0) }
        parameterSets.clear()
        accessUnitFormat = AccessUnitFormat.UNKNOWN
    }

    private fun normalizeConfigurationPart(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty() || bytes.size > MAX_CONFIGURATION_BYTES) return null

        canonicalizeAnnexB(bytes)?.let { return it }
        parseDecoderConfigurationRecord(bytes)?.let { return it }

        for (lengthBytes in intArrayOf(4, 3, 2, 1)) {
            convertLengthPrefixed(bytes, lengthBytes, MAX_CONFIGURATION_BYTES)?.let { return it }
        }

        if (hasValidNalHeader(bytes, 0) && nalType(bytes, 0) in requiredParameterSetTypes()) {
            if (START_CODE.size + bytes.size > MAX_CONFIGURATION_BYTES) return null
            return START_CODE + bytes
        }
        return null
    }

    private fun normalize(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty() || bytes.size > maximumAccessUnitBytes) return null
        return when (val format = lockedFormat(bytes) ?: return null) {
            AccessUnitFormat.ANNEX_B -> bytes.copyOf().takeIf { parseAnnexB(it) != null }
            AccessUnitFormat.LENGTH_4,
            AccessUnitFormat.LENGTH_3,
            AccessUnitFormat.LENGTH_2,
            AccessUnitFormat.LENGTH_1 -> convertLengthPrefixed(bytes, format.lengthBytes, maximumAccessUnitBytes)
            AccessUnitFormat.UNKNOWN -> null
        }
    }

    private fun lockedFormat(bytes: ByteArray): AccessUnitFormat? {
        if (accessUnitFormat != AccessUnitFormat.UNKNOWN) {
            return when (accessUnitFormat) {
                AccessUnitFormat.ANNEX_B -> accessUnitFormat.takeIf { parseAnnexB(bytes) != null }
                AccessUnitFormat.LENGTH_4,
                AccessUnitFormat.LENGTH_3,
                AccessUnitFormat.LENGTH_2,
                AccessUnitFormat.LENGTH_1 -> accessUnitFormat.takeIf {
                    isValidLengthPrefixed(bytes, accessUnitFormat.lengthBytes)
                }
                AccessUnitFormat.UNKNOWN -> null
            }
        }

        // A complete 4-byte length-prefixed stream wins over a leading 00 00 01 byte
        // pattern, preventing 256-511-byte NAL lengths from being mistaken for Annex-B.
        val detected = when {
            startsWithFourByteStartCode(bytes) && parseAnnexB(bytes) != null -> AccessUnitFormat.ANNEX_B
            isValidLengthPrefixed(bytes, 4) -> AccessUnitFormat.LENGTH_4
            parseAnnexB(bytes) != null -> AccessUnitFormat.ANNEX_B
            isValidLengthPrefixed(bytes, 3) -> AccessUnitFormat.LENGTH_3
            isValidLengthPrefixed(bytes, 2) -> AccessUnitFormat.LENGTH_2
            isValidLengthPrefixed(bytes, 1) -> AccessUnitFormat.LENGTH_1
            else -> return null
        }
        accessUnitFormat = detected
        return detected
    }

    private fun cacheParameterSets(annexB: ByteArray): Boolean {
        val nals = parseAnnexB(annexB) ?: return false
        val observedByType = linkedMapOf<Int, MutableList<ByteArray>>()
        for (nal in nals) {
            val type = nalType(annexB, nal.payloadStart)
            if (type !in requiredParameterSetTypes()) continue
            val payloadSize = nal.payloadEnd - nal.payloadStart
            if (payloadSize <= 0 || START_CODE.size + payloadSize > MAX_CONFIGURATION_BYTES) continue
            val canonical = ByteArray(START_CODE.size + payloadSize)
            START_CODE.copyInto(canonical)
            annexB.copyInto(canonical, START_CODE.size, nal.payloadStart, nal.payloadEnd)
            observedByType.getOrPut(type) { mutableListOf() }.add(canonical)
        }
        if (observedByType.isEmpty()) return false

        for ((type, replacements) in observedByType) {
            parameterSets.remove(type)?.forEach { it.fill(0) }
            parameterSets[type] = replacements
        }
        return true
    }

    private fun containsCompleteParameterSets(annexB: ByteArray): Boolean {
        val nals = parseAnnexB(annexB) ?: return false
        val present = mutableSetOf<Int>()
        for (nal in nals) {
            val type = nalType(annexB, nal.payloadStart)
            if (type in requiredParameterSetTypes()) present += type
        }
        return requiredParameterSetTypes().all { it in present }
    }

    private fun buildCodecConfiguration(): ByteArray? {
        if (!hasCodecConfiguration()) return null
        var total = 0
        for (type in requiredParameterSetTypes()) {
            val parts = parameterSets[type] ?: return null
            for (part in parts) {
                if (part.size > MAX_CONFIGURATION_BYTES - total) return null
                total += part.size
            }
        }
        if (total <= 0) return null
        val output = ByteArray(total)
        var offset = 0
        for (type in requiredParameterSetTypes()) {
            val parts = parameterSets[type] ?: return null
            for (part in parts) {
                part.copyInto(output, offset)
                offset += part.size
            }
        }
        return output
    }

    private fun containsRandomAccessNal(annexB: ByteArray): Boolean {
        val nals = parseAnnexB(annexB) ?: return false
        return nals.any { nal ->
            when (codec) {
                VideoCodec.AVC -> nalType(annexB, nal.payloadStart) == AVC_IDR
                VideoCodec.HEVC -> nalType(annexB, nal.payloadStart) in HEVC_RANDOM_ACCESS_TYPES
            }
        }
    }

    private fun canonicalizeAnnexB(bytes: ByteArray): ByteArray? {
        val nals = parseAnnexB(bytes) ?: return null
        val output = ByteArrayOutputStream(bytes.size + nals.size * START_CODE.size)
        for (nal in nals) {
            val payloadSize = nal.payloadEnd - nal.payloadStart
            if (output.size() > MAX_CONFIGURATION_BYTES - START_CODE.size - payloadSize) return null
            output.write(START_CODE)
            output.write(bytes, nal.payloadStart, payloadSize)
        }
        return output.toByteArray().takeIf { it.isNotEmpty() && it.size <= MAX_CONFIGURATION_BYTES }
    }

    private fun parseDecoderConfigurationRecord(bytes: ByteArray): ByteArray? = when (codec) {
        VideoCodec.AVC -> parseAvcDecoderConfigurationRecord(bytes)
        VideoCodec.HEVC -> parseHevcDecoderConfigurationRecord(bytes)
    }

    private fun parseAvcDecoderConfigurationRecord(bytes: ByteArray): ByteArray? {
        if (bytes.size < 7 || bytes[0] != 1.toByte()) return null
        var offset = 5
        val output = ByteArrayOutputStream(bytes.size + 32)
        val spsCount = bytes[offset++].toInt() and 0x1F
        if (spsCount == 0) return null
        repeat(spsCount) {
            if (offset + 2 > bytes.size) return null
            val length = readUnsignedLength(bytes, offset, 2)
            offset += 2
            if (length <= 0 || offset + length > bytes.size || !hasValidNalHeader(bytes, offset) ||
                nalType(bytes, offset) != AVC_SPS
            ) {
                return null
            }
            output.write(START_CODE)
            output.write(bytes, offset, length)
            offset += length
        }
        if (offset >= bytes.size) return null
        val ppsCount = bytes[offset++].toInt() and 0xFF
        if (ppsCount == 0) return null
        repeat(ppsCount) {
            if (offset + 2 > bytes.size) return null
            val length = readUnsignedLength(bytes, offset, 2)
            offset += 2
            if (length <= 0 || offset + length > bytes.size || !hasValidNalHeader(bytes, offset) ||
                nalType(bytes, offset) != AVC_PPS
            ) {
                return null
            }
            output.write(START_CODE)
            output.write(bytes, offset, length)
            offset += length
        }
        return output.toByteArray().takeIf { it.isNotEmpty() && it.size <= MAX_CONFIGURATION_BYTES }
    }

    private fun parseHevcDecoderConfigurationRecord(bytes: ByteArray): ByteArray? {
        if (bytes.size < HEVC_CONFIGURATION_HEADER_BYTES || bytes[0] != 1.toByte()) return null
        val arrayCount = bytes[22].toInt() and 0xFF
        if (arrayCount == 0) return null
        var offset = HEVC_CONFIGURATION_HEADER_BYTES
        val output = ByteArrayOutputStream(bytes.size + 32)
        repeat(arrayCount) {
            if (offset + 3 > bytes.size) return null
            val declaredType = bytes[offset++].toInt() and 0x3F
            val nalCount = readUnsignedLength(bytes, offset, 2)
            offset += 2
            if (nalCount <= 0) return null
            repeat(nalCount) {
                if (offset + 2 > bytes.size) return null
                val length = readUnsignedLength(bytes, offset, 2)
                offset += 2
                if (length <= 0 || offset + length > bytes.size || !hasValidNalHeader(bytes, offset) ||
                    nalType(bytes, offset) != declaredType
                ) {
                    return null
                }
                output.write(START_CODE)
                output.write(bytes, offset, length)
                offset += length
            }
        }
        if (offset != bytes.size) return null
        return output.toByteArray().takeIf { it.isNotEmpty() && it.size <= MAX_CONFIGURATION_BYTES }
    }

    private fun convertLengthPrefixed(bytes: ByteArray, lengthBytes: Int, maximumBytes: Int): ByteArray? {
        if (!isValidLengthPrefixed(bytes, lengthBytes)) return null
        val output = ByteArrayOutputStream(bytes.size + 32)
        var offset = 0
        while (offset < bytes.size) {
            val length = readUnsignedLength(bytes, offset, lengthBytes)
            offset += lengthBytes
            if (output.size() > maximumBytes - START_CODE.size - length) return null
            output.write(START_CODE)
            output.write(bytes, offset, length)
            offset += length
        }
        return output.toByteArray().takeIf { it.isNotEmpty() && it.size <= maximumBytes }
    }

    private fun isValidLengthPrefixed(bytes: ByteArray, lengthBytes: Int): Boolean {
        if (lengthBytes !in 1..4 || bytes.size < lengthBytes + minimumNalHeaderBytes()) return false
        var offset = 0
        var nalCount = 0
        while (offset < bytes.size) {
            if (bytes.size - offset < lengthBytes) return false
            val length = readUnsignedLength(bytes, offset, lengthBytes)
            offset += lengthBytes
            if (length < minimumNalHeaderBytes() || length > bytes.size - offset) return false
            if (!hasValidNalHeader(bytes, offset)) return false
            offset += length
            nalCount++
        }
        return offset == bytes.size && nalCount > 0
    }

    private fun parseAnnexB(bytes: ByteArray): List<NalUnit>? {
        val first = findStartCode(bytes, 0) ?: return null
        if (first.index > 0 && bytes.copyOfRange(0, first.index).any { it != 0.toByte() }) return null

        val nals = mutableListOf<NalUnit>()
        var start = first
        while (true) {
            val payloadStart = start.index + start.length
            val next = findStartCode(bytes, payloadStart)
            val payloadEnd = next?.index ?: bytes.size
            if (payloadEnd - payloadStart < minimumNalHeaderBytes() || !hasValidNalHeader(bytes, payloadStart)) {
                return null
            }
            nals += NalUnit(payloadStart, payloadEnd)
            if (next == null) break
            start = next
        }
        return nals.takeIf { it.isNotEmpty() }
    }

    private fun findStartCode(bytes: ByteArray, fromIndex: Int): StartCode? {
        var index = fromIndex.coerceAtLeast(0)
        while (index + 2 < bytes.size) {
            if (index + 3 < bytes.size &&
                bytes[index] == 0.toByte() &&
                bytes[index + 1] == 0.toByte() &&
                bytes[index + 2] == 0.toByte() &&
                bytes[index + 3] == 1.toByte()
            ) {
                return StartCode(index, 4)
            }
            if (bytes[index] == 0.toByte() &&
                bytes[index + 1] == 0.toByte() &&
                bytes[index + 2] == 1.toByte()
            ) {
                return StartCode(index, 3)
            }
            index++
        }
        return null
    }

    private fun startsWithFourByteStartCode(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0.toByte() &&
            bytes[1] == 0.toByte() &&
            bytes[2] == 0.toByte() &&
            bytes[3] == 1.toByte()

    private fun hasValidNalHeader(bytes: ByteArray, headerOffset: Int): Boolean {
        if (headerOffset < 0 || headerOffset >= bytes.size) return false
        val first = bytes[headerOffset].toInt() and 0xFF
        if (first and 0x80 != 0) return false
        return when (codec) {
            VideoCodec.AVC -> (first and 0x1F) in 1..23
            VideoCodec.HEVC -> {
                if (headerOffset + 1 >= bytes.size) return false
                val type = (first shr 1) and 0x3F
                val temporalIdPlusOne = bytes[headerOffset + 1].toInt() and 0x07
                type in 0..63 && temporalIdPlusOne != 0
            }
        }
    }

    private fun nalType(bytes: ByteArray, headerOffset: Int): Int = when (codec) {
        VideoCodec.AVC -> bytes[headerOffset].toInt() and 0x1F
        VideoCodec.HEVC -> (bytes[headerOffset].toInt() ushr 1) and 0x3F
    }

    private fun minimumNalHeaderBytes(): Int = when (codec) {
        VideoCodec.AVC -> 1
        VideoCodec.HEVC -> 2
    }

    private fun requiredParameterSetTypes(): IntArray = when (codec) {
        VideoCodec.AVC -> AVC_PARAMETER_TYPES
        VideoCodec.HEVC -> HEVC_PARAMETER_TYPES
    }

    private fun readUnsignedLength(bytes: ByteArray, offset: Int, lengthBytes: Int): Int {
        if (lengthBytes !in 1..4 || offset < 0 || offset + lengthBytes > bytes.size) return -1
        var value = 0L
        repeat(lengthBytes) { index ->
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFFL)
        }
        return if (value in 1..Int.MAX_VALUE.toLong()) value.toInt() else -1
    }

    private data class StartCode(val index: Int, val length: Int)
    private data class NalUnit(val payloadStart: Int, val payloadEnd: Int)

    private enum class AccessUnitFormat(val lengthBytes: Int) {
        UNKNOWN(0),
        ANNEX_B(0),
        LENGTH_4(4),
        LENGTH_3(3),
        LENGTH_2(2),
        LENGTH_1(1),
    }

    companion object {
        const val MAX_ACCESS_UNIT_BYTES: Int = 8 * 1024 * 1024
        private const val MAX_CONFIGURATION_BYTES = 256 * 1024
        private const val HEVC_CONFIGURATION_HEADER_BYTES = 23
        private const val AVC_IDR = 5
        private const val AVC_SPS = 7
        private const val AVC_PPS = 8
        private val AVC_PARAMETER_TYPES = intArrayOf(AVC_SPS, AVC_PPS)
        private val HEVC_PARAMETER_TYPES = intArrayOf(32, 33, 34)
        private val HEVC_RANDOM_ACCESS_TYPES = 16..21
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
