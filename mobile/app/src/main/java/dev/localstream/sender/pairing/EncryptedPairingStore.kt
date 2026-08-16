package dev.localstream.sender.pairing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

interface SecretBox {
    fun seal(plaintext: ByteArray): ByteArray

    @Throws(SecurityException::class)
    fun open(sealed: ByteArray): ByteArray
}

interface KeyValueStore {
    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)
}

/** Encrypts the complete pairing record and validates it again after every decrypt. */
class EncryptedPairingStore(
    private val secretBox: SecretBox,
    private val values: KeyValueStore,
    private val nowEpochSeconds: () -> Long,
) {
    fun save(record: PairingRecord) {
        require(PairingPayloadParser.isValidStoredRecord(record, nowEpochSeconds()))
        val plaintext = PairingRecordCodec.encode(record)
        try {
            val sealed = secretBox.seal(plaintext)
            try {
                require(sealed.size <= MAX_SEALED_BYTES)
                values.put(STORAGE_KEY, Base64.getEncoder().encodeToString(sealed))
            } finally {
                sealed.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    fun load(): PairingRecord? {
        val encoded = values.get(STORAGE_KEY) ?: return null
        if (encoded.length > MAX_ENCODED_CHARS) return removeInvalid()
        val sealed = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return removeInvalid()
        }
        if (sealed.size > MAX_SEALED_BYTES) {
            sealed.fill(0)
            return removeInvalid()
        }
        val plaintext = try {
            secretBox.open(sealed)
        } catch (_: SecurityException) {
            sealed.fill(0)
            return removeInvalid()
        } finally {
            sealed.fill(0)
        }
        return try {
            val record = PairingRecordCodec.decode(plaintext) ?: return removeInvalid()
            if (!PairingPayloadParser.isValidStoredRecord(record, nowEpochSeconds())) {
                record.destroy()
                removeInvalid()
            } else {
                record
            }
        } finally {
            plaintext.fill(0)
        }
    }

    fun remove() {
        values.remove(STORAGE_KEY)
    }

    private fun removeInvalid(): PairingRecord? {
        remove()
        return null
    }

    companion object {
        private const val STORAGE_KEY = "paired_receiver_v1"
        private const val MAX_SEALED_BYTES = 4096
        private const val MAX_ENCODED_CHARS = 5500
    }
}

private object PairingRecordCodec {
    private const val MAGIC = 0x50435331
    private const val VERSION = 1
    private const val MAX_RECORD_BYTES = 2048

    fun encode(record: PairingRecord): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            writeText(data, record.receiverId, 36)
            writeText(data, record.label, 192)
            writeText(data, record.host, 15)
            data.writeInt(record.port)
            val secret = record.copySecret()
            try {
                data.writeInt(secret.size)
                data.write(secret)
            } finally {
                secret.fill(0)
            }
            data.writeLong(record.credentialExpiresAtEpochSeconds)
            data.writeInt(record.latencyMs)
            data.writeInt(record.pbKeyLength)
        }
        return output.toByteArray().also { require(it.size <= MAX_RECORD_BYTES) }
    }

    fun decode(bytes: ByteArray): PairingRecord? {
        if (bytes.isEmpty() || bytes.size > MAX_RECORD_BYTES) return null
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                if (data.readInt() != MAGIC || data.readInt() != VERSION) return null
                val receiverId = readText(data, 36) ?: return null
                val label = readText(data, 192) ?: return null
                val host = readText(data, 15) ?: return null
                val port = data.readInt()
                val secretLength = data.readInt()
                if (secretLength != 32) return null
                val secret = ByteArray(secretLength)
                data.readFully(secret)
                val record = PairingRecord(
                    receiverId = receiverId,
                    label = label,
                    host = host,
                    port = port,
                    secret = secret,
                    credentialExpiresAtEpochSeconds = data.readLong(),
                    latencyMs = data.readInt(),
                    pbKeyLength = data.readInt(),
                )
                secret.fill(0)
                if (data.available() != 0) {
                    record.destroy()
                    null
                } else {
                    record
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeText(data: DataOutputStream, value: String, maximumBytes: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= maximumBytes)
        data.writeInt(bytes.size)
        data.write(bytes)
    }

    private fun readText(data: DataInputStream, maximumBytes: Int): String? {
        val size = data.readInt()
        if (size < 0 || size > maximumBytes || size > data.available()) return null
        val bytes = ByteArray(size)
        data.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}

