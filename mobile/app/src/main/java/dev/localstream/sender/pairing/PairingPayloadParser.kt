@file:android.annotation.SuppressLint("SyntheticAccessor")

package dev.localstream.sender.pairing

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.LinkedHashMap
import java.util.UUID

enum class PairingError {
    TOO_LARGE,
    MALFORMED,
    UNKNOWN_PROTOCOL_VERSION,
    DUPLICATE_FIELD,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    INVALID_RECEIVER_ID,
    INVALID_LABEL,
    INVALID_ENDPOINT,
    INVALID_SECRET,
    EXPIRED_QR,
    INVALID_EXPIRY,
    INVALID_TRANSPORT,
}

sealed interface PairingParseResult {
    class Success(val record: PairingRecord) : PairingParseResult

    class Failure(val error: PairingError) : PairingParseResult
}

/** Strict parser for the versioned, canonical QR payload documented in pc/README.md. */
class PairingPayloadParser {
    fun parse(payload: String, nowEpochSeconds: Long): PairingParseResult {
        val encoded = payload.toByteArray(StandardCharsets.UTF_8)
        if (encoded.size > MAX_PAYLOAD_BYTES) {
            return PairingParseResult.Failure(PairingError.TOO_LARGE)
        }
        if (payload.indexOf('#') >= 0) {
            return PairingParseResult.Failure(PairingError.MALFORMED)
        }
        if (!payload.startsWith(PROTOCOL_PREFIX)) {
            if (payload.startsWith("pcstream://pair/")) {
                return PairingParseResult.Failure(PairingError.UNKNOWN_PROTOCOL_VERSION)
            }
            return PairingParseResult.Failure(PairingError.MALFORMED)
        }

        val rawQuery = payload.substring(PROTOCOL_PREFIX.length)
        if (rawQuery.isEmpty()) {
            return PairingParseResult.Failure(PairingError.MISSING_FIELD)
        }

        val values = LinkedHashMap<String, String>()
        val parts = rawQuery.split('&')
        if (parts.size > REQUIRED_FIELDS.size) {
            // A duplicate is reported more precisely below; otherwise extra input is unknown.
            val duplicate = findDuplicateName(parts)
            return PairingParseResult.Failure(
                if (duplicate) PairingError.DUPLICATE_FIELD else PairingError.UNKNOWN_FIELD,
            )
        }

        for (part in parts) {
            val equals = part.indexOf('=')
            if (equals <= 0 || equals == part.lastIndex) {
                return PairingParseResult.Failure(PairingError.MALFORMED)
            }
            val name = strictPercentDecode(part.substring(0, equals))
                ?: return PairingParseResult.Failure(PairingError.MALFORMED)
            val value = strictPercentDecode(part.substring(equals + 1))
                ?: return PairingParseResult.Failure(PairingError.MALFORMED)
            if (!REQUIRED_FIELDS.contains(name)) {
                return PairingParseResult.Failure(PairingError.UNKNOWN_FIELD)
            }
            if (values.put(name, value) != null) {
                return PairingParseResult.Failure(PairingError.DUPLICATE_FIELD)
            }
        }
        if (values.keys != REQUIRED_FIELDS) {
            return PairingParseResult.Failure(PairingError.MISSING_FIELD)
        }

        val receiverId = values.getValue("receiver_id")
        if (!isCanonicalUuid(receiverId)) {
            return PairingParseResult.Failure(PairingError.INVALID_RECEIVER_ID)
        }

        val label = values.getValue("label")
        if (!isSafeLabel(label)) {
            return PairingParseResult.Failure(PairingError.INVALID_LABEL)
        }

        val host = values.getValue("host")
        if (!isPrivateIpv4(host)) {
            return PairingParseResult.Failure(PairingError.INVALID_ENDPOINT)
        }
        val port = parseBoundedInt(values.getValue("port"), 1024, 65535)
            ?: return PairingParseResult.Failure(PairingError.INVALID_ENDPOINT)

        val secretText = values.getValue("secret")
        val secret = decodeSecret(secretText)
            ?: return PairingParseResult.Failure(PairingError.INVALID_SECRET)

        val qrExpiry = parsePositiveLong(values.getValue("qr_expires"))
            ?: return failureAfterClearing(secret, PairingError.INVALID_EXPIRY)
        if (qrExpiry <= nowEpochSeconds) {
            return failureAfterClearing(secret, PairingError.EXPIRED_QR)
        }
        if (qrExpiry > nowEpochSeconds + MAX_QR_LIFETIME_SECONDS) {
            return failureAfterClearing(secret, PairingError.INVALID_EXPIRY)
        }

        val credentialExpiry = parsePositiveLong(values.getValue("credential_expires"))
            ?: return failureAfterClearing(secret, PairingError.INVALID_EXPIRY)
        if (credentialExpiry <= qrExpiry ||
            credentialExpiry > nowEpochSeconds + MAX_CREDENTIAL_LIFETIME_SECONDS
        ) {
            return failureAfterClearing(secret, PairingError.INVALID_EXPIRY)
        }

        val latencyMs = parseBoundedInt(values.getValue("latency_ms"), 60, 2000)
            ?: return failureAfterClearing(secret, PairingError.INVALID_TRANSPORT)
        val pbKeyLength = parseBoundedInt(values.getValue("pbkeylen"), 32, 32)
            ?: return failureAfterClearing(secret, PairingError.INVALID_TRANSPORT)

        return PairingParseResult.Success(
            PairingRecord(
                receiverId = receiverId,
                label = label,
                host = host,
                port = port,
                secret = secret,
                credentialExpiresAtEpochSeconds = credentialExpiry,
                latencyMs = latencyMs,
                pbKeyLength = pbKeyLength,
            ),
        ).also { secret.fill(0) }
    }

    private fun findDuplicateName(parts: List<String>): Boolean {
        val names = HashSet<String>()
        for (part in parts) {
            val equals = part.indexOf('=')
            if (equals <= 0) continue
            val name = strictPercentDecode(part.substring(0, equals)) ?: continue
            if (!names.add(name)) return true
        }
        return false
    }

    private fun strictPercentDecode(raw: String): String? {
        if (raw.indexOf('+') >= 0) return null
        val output = ByteArrayOutputStream(raw.length)
        var index = 0
        while (index < raw.length) {
            val codePoint = raw.codePointAt(index)
            if (codePoint == '%'.code) {
                if (index + 2 >= raw.length) return null
                val high = Character.digit(raw[index + 1], 16)
                val low = Character.digit(raw[index + 2], 16)
                if (high < 0 || low < 0) return null
                output.write((high shl 4) or low)
                index += 3
            } else {
                val chars = Character.toChars(codePoint)
                val bytes = String(chars).toByteArray(StandardCharsets.UTF_8)
                output.write(bytes, 0, bytes.size)
                index += Character.charCount(codePoint)
            }
            if (output.size() > MAX_FIELD_BYTES) return null
        }
        return try {
            val decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray()))
                .toString()
            decoded.takeIf { canonicalPercentEncode(it) == raw }
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun canonicalPercentEncode(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val output = StringBuilder(bytes.size)
        for (byte in bytes) {
            val unsigned = byte.toInt() and 0xFF
            val unreserved = unsigned in 'A'.code..'Z'.code ||
                unsigned in 'a'.code..'z'.code ||
                unsigned in '0'.code..'9'.code ||
                unsigned == '-'.code || unsigned == '.'.code || unsigned == '_'.code || unsigned == '~'.code
            if (unreserved) {
                output.append(unsigned.toChar())
            } else {
                output.append('%')
                output.append(HEX[unsigned ushr 4])
                output.append(HEX[unsigned and 0x0F])
            }
        }
        return output.toString()
    }

    companion object {
        const val PROTOCOL_PREFIX = "pcstream://pair/v1?"
        const val MAX_PAYLOAD_BYTES = 2048
        private const val MAX_FIELD_BYTES = 256
        private const val MAX_QR_LIFETIME_SECONDS = 10L * 60L
        private const val MAX_CREDENTIAL_LIFETIME_SECONDS = 366L * 24L * 60L * 60L
        private const val HEX = "0123456789ABCDEF"

        private val REQUIRED_FIELDS = linkedSetOf(
            "receiver_id",
            "label",
            "host",
            "port",
            "secret",
            "qr_expires",
            "credential_expires",
            "latency_ms",
            "pbkeylen",
        )

        internal fun isCanonicalUuid(value: String): Boolean {
            if (value.length != 36 || value != value.lowercase()) return false
            return try {
                val parsed = UUID.fromString(value)
                parsed.toString() == value && parsed.mostSignificantBits != 0L &&
                    parsed.leastSignificantBits != 0L
            } catch (_: IllegalArgumentException) {
                false
            }
        }

        internal fun isSafeLabel(value: String): Boolean {
            if (value.length !in 1..48 || value != value.trim()) return false
            for (character in value) {
                if (character.isISOControl() || character.code in 0x202A..0x202E ||
                    character.code in 0x2066..0x2069
                ) {
                    return false
                }
            }
            return true
        }

        internal fun isPrivateIpv4(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4) return false
            val octets = IntArray(4)
            for (index in parts.indices) {
                val part = parts[index]
                if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return false
                val octet = part.toIntOrNull() ?: return false
                if (octet !in 0..255 || octet.toString() != part) return false
                octets[index] = octet
            }
            if (value == "255.255.255.255") return false
            return octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 169 && octets[1] == 254)
        }

        internal fun isValidStoredRecord(record: PairingRecord, nowEpochSeconds: Long): Boolean =
            isCanonicalUuid(record.receiverId) &&
                isSafeLabel(record.label) &&
                isPrivateIpv4(record.host) &&
                record.port in 1024..65535 &&
                record.copySecret().let { secret ->
                    val valid = secret.size == 32
                    secret.fill(0)
                    valid
                } &&
                record.isCredentialValid(nowEpochSeconds) &&
                record.latencyMs in 60..2000 &&
                record.pbKeyLength == 32

        private fun parseBoundedInt(value: String, minimum: Int, maximum: Int): Int? {
            if (value.isEmpty() || value.length > 10 || value.any { !it.isDigit() }) return null
            val parsed = value.toIntOrNull() ?: return null
            return if (parsed in minimum..maximum && parsed.toString() == value) parsed else null
        }

        private fun parsePositiveLong(value: String): Long? {
            if (value.isEmpty() || value.length > 19 || value.any { !it.isDigit() }) return null
            val parsed = value.toLongOrNull() ?: return null
            return if (parsed > 0L && parsed.toString() == value) parsed else null
        }

        private fun decodeSecret(value: String): ByteArray? {
            if (value.length != 43 || value.any {
                    !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-')
                }
            ) {
                return null
            }
            return try {
                Base64.getUrlDecoder().decode(value).takeIf { it.size == 32 }
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun failureAfterClearing(
            secret: ByteArray,
            error: PairingError,
        ): PairingParseResult.Failure {
            secret.fill(0)
            return PairingParseResult.Failure(error)
        }
    }
}
