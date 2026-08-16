package dev.localstream.sender.media

import java.io.ByteArrayOutputStream

/** Converts bounded MediaCodec Annex-B or 4-byte length-prefixed access units to Annex-B. */
class EncodedVideoNormalizer(private val maximumAccessUnitBytes: Int = MAX_ACCESS_UNIT_BYTES) {
    private var codecConfiguration = ByteArray(0)

    fun setCodecConfiguration(parts: List<ByteArray>): Boolean {
        val normalized = parts.mapNotNull { normalize(it) }
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

    fun clear() {
        codecConfiguration.fill(0)
        codecConfiguration = ByteArray(0)
    }

    private fun normalize(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty() || bytes.size > maximumAccessUnitBytes) return null
        if (startsWithStartCode(bytes)) return bytes.copyOf()
        val output = ByteArrayOutputStream(bytes.size + 32)
        var offset = 0
        while (offset < bytes.size) {
            if (bytes.size - offset < LENGTH_PREFIX_BYTES) return null
            val length = ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            offset += LENGTH_PREFIX_BYTES
            if (length <= 0 || length > bytes.size - offset || output.size() + length + 4 > maximumAccessUnitBytes) {
                return null
            }
            output.write(START_CODE)
            output.write(bytes, offset, length)
            offset += length
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun startsWithStartCode(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0.toByte() &&
            bytes[1] == 0.toByte() &&
            (bytes[2] == 1.toByte() || (bytes.size >= 4 && bytes[2] == 0.toByte() && bytes[3] == 1.toByte()))

    companion object {
        const val MAX_ACCESS_UNIT_BYTES: Int = 6 * 1024 * 1024
        private const val MAX_CONFIGURATION_BYTES = 256 * 1024
        private const val LENGTH_PREFIX_BYTES = 4
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
