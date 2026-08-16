package dev.localstream.sender.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class EncryptedPairingStoreTest {
    private val now = 1_786_861_700L

    @Test
    fun saveAndLoadRoundTripWithoutPlaintextAtRest() {
        val values = MemoryValues()
        val store = EncryptedPairingStore(TestSecretBox(), values) { now }
        val record = record()

        store.save(record)
        val persisted = values.value.orEmpty()
        val loaded = store.load()!!

        assertFalse(persisted.contains(record.host))
        assertFalse(persisted.contains(record.label))
        assertArrayEquals(record.copySecret(), loaded.copySecret())
        assertTrue(loaded.isCredentialValid(now))
    }

    @Test
    fun missingCorruptTamperedAndExpiredDataFailClosedAndAreRemoved() {
        val values = MemoryValues()
        val store = EncryptedPairingStore(TestSecretBox(), values) { now }
        assertNull(store.load())

        values.value = "not base64"
        assertNull(store.load())
        assertNull(values.value)

        store.save(record())
        val decoded = Base64.getDecoder().decode(values.value)
        decoded[decoded.lastIndex] = (decoded.last().toInt() xor 1).toByte()
        values.value = Base64.getEncoder().encodeToString(decoded)
        assertNull(store.load())
        assertNull(values.value)

        val expiredStore = EncryptedPairingStore(TestSecretBox(), values) { now + 100_000 }
        expiredStore.save(record(expiresAt = now + 200_000))
        val laterStore = EncryptedPairingStore(TestSecretBox(), values) { now + 300_000 }
        assertNull(laterStore.load())
        assertNull(values.value)
    }

    @Test
    fun removeIsIdempotent() {
        val values = MemoryValues()
        val store = EncryptedPairingStore(TestSecretBox(), values) { now }
        store.save(record())
        store.remove()
        store.remove()
        assertNull(store.load())
    }

    private fun record(expiresAt: Long = now + 86_400): PairingRecord = PairingRecord(
        receiverId = "550e8400-e29b-41d4-a716-446655440000",
        label = "Studio PC",
        host = "192.168.1.20",
        port = 9000,
        secret = ByteArray(32) { index -> index.toByte() },
        credentialExpiresAtEpochSeconds = expiresAt,
        latencyMs = 120,
        pbKeyLength = 32,
    )

    private class MemoryValues : KeyValueStore {
        var value: String? = null

        override fun get(key: String): String? = value

        override fun put(key: String, value: String) {
            this.value = value
        }

        override fun remove(key: String) {
            value = null
        }
    }

    /** Test-only authenticated box; production uses Android Keystore AES-GCM. */
    private class TestSecretBox : SecretBox {
        override fun seal(plaintext: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256").digest(plaintext)
            return digest + plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }

        override fun open(sealed: ByteArray): ByteArray {
            if (sealed.size < 32) throw SecurityException("invalid")
            val plaintext = sealed.copyOfRange(32, sealed.size).map {
                (it.toInt() xor 0x5A).toByte()
            }.toByteArray()
            val expected = MessageDigest.getInstance("SHA-256").digest(plaintext)
            if (!MessageDigest.isEqual(expected, sealed.copyOfRange(0, 32))) {
                plaintext.fill(0)
                throw SecurityException("invalid")
            }
            return plaintext
        }
    }
}

