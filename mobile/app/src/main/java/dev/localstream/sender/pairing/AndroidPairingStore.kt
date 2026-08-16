package dev.localstream.sender.pairing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreSecretBox(private val alias: String = KEY_ALIAS) : SecretBox {
    override fun seal(plaintext: ByteArray): ByteArray {
        require(plaintext.size <= MAX_PLAINTEXT_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Android Keystore keys with randomized encryption reject caller-provided IVs by design.
        // Let the Keystore generate the nonce, then store it beside the ciphertext.
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        check(iv.size == IV_BYTES)
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(HEADER_BYTES + iv.size + ciphertext.size)
            .putInt(MAGIC)
            .put(VERSION)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size !in MIN_SEALED_BYTES..MAX_SEALED_BYTES) throw SecurityException("invalid record")
        try {
            val buffer = ByteBuffer.wrap(sealed)
            if (buffer.int != MAGIC || buffer.get() != VERSION) throw SecurityException("invalid record")
            val ivLength = buffer.get().toInt() and 0xFF
            if (ivLength != IV_BYTES || buffer.remaining() <= ivLength + TAG_BYTES) {
                throw SecurityException("invalid record")
            }
            val iv = ByteArray(ivLength)
            buffer.get(iv)
            val ciphertext = ByteArray(buffer.remaining())
            buffer.get(ciphertext)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            return cipher.doFinal(ciphertext).also {
                if (it.size > MAX_PLAINTEXT_BYTES) {
                    it.fill(0)
                    throw SecurityException("invalid record")
                }
            }
        } catch (_: AEADBadTagException) {
            throw SecurityException("invalid record")
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            throw SecurityException("invalid record")
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore().getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun getExistingKey(): SecretKey =
        keyStore().getKey(alias, null) as? SecretKey ?: throw SecurityException("missing key")

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        private const val KEY_ALIAS = "local_camera_sender_pairing_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val MAGIC = 0x4B535031
        private const val VERSION: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val TAG_BITS = TAG_BYTES * 8
        private const val HEADER_BYTES = 6
        private const val MIN_SEALED_BYTES = HEADER_BYTES + IV_BYTES + TAG_BYTES + 1
        private const val MAX_PLAINTEXT_BYTES = 2048
        private const val MAX_SEALED_BYTES = 4096
        private val AAD = "dev.localstream.sender/pairing/v1".toByteArray(StandardCharsets.US_ASCII)
    }
}

/** One-record atomic store inside noBackupFilesDir; it never exposes a filename derived from input. */
class NoBackupKeyValueStore(context: Context) : KeyValueStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))

    @Synchronized
    override fun get(key: String): String? {
        require(key == EXPECTED_KEY)
        val base = file.baseFile
        if (!base.exists()) return null
        if (base.length() <= 0L || base.length() > MAX_FILE_BYTES) {
            file.delete()
            return null
        }
        return try {
            val bytes = file.readFully()
            if (bytes.size > MAX_FILE_BYTES) {
                file.delete()
                null
            } else {
                String(bytes, StandardCharsets.US_ASCII)
            }
        } catch (_: IOException) {
            null
        }
    }

    @Synchronized
    override fun put(key: String, value: String) {
        require(key == EXPECTED_KEY)
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        require(bytes.size <= MAX_FILE_BYTES)
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.flush()
            file.finishWrite(output)
        } catch (error: IOException) {
            file.failWrite(output)
            throw IllegalStateException("Pairing could not be saved.", error)
        } finally {
            bytes.fill(0)
        }
    }

    @Synchronized
    override fun remove(key: String) {
        require(key == EXPECTED_KEY)
        file.delete()
    }

    companion object {
        private const val EXPECTED_KEY = "paired_receiver_v1"
        private const val FILE_NAME = "paired_receiver_v1.enc"
        private const val MAX_FILE_BYTES = 5500
    }
}

object AndroidPairingStore {
    fun create(context: Context, nowEpochSeconds: () -> Long): EncryptedPairingStore =
        EncryptedPairingStore(
            secretBox = AndroidKeystoreSecretBox(),
            values = NoBackupKeyValueStore(context.applicationContext),
            nowEpochSeconds = nowEpochSeconds,
        )
}
