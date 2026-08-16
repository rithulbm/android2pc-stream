package dev.localstream.sender.pairing

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidPairingStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val now = 1_800_000_000L
    private val store by lazy { AndroidPairingStore.create(context) { now } }

    @Before
    fun clearBefore() {
        store.remove()
    }

    @After
    fun clearAfter() {
        store.remove()
    }

    @Test
    fun keystoreRoundTripKeepsCompleteRecordOutOfPreferences() {
        val originalSecret = ByteArray(32) { it.toByte() }
        val record = record(originalSecret)

        store.save(record)
        record.destroy()
        val loaded = store.load()

        requireNotNull(loaded)
        assertEquals("Living room PC", loaded.label)
        assertEquals("192.168.1.20", loaded.host)
        val loadedSecret = loaded.copySecret()
        assertArrayEquals(originalSecret, loadedSecret)
        loadedSecret.fill(0)
        loaded.destroy()
        assertFalse(File(context.noBackupFilesDir, "shared_prefs").exists())
    }

    @Test
    fun ciphertextTamperingFailsClosedAndDeletesRecord() {
        val record = record(ByteArray(32) { 7 })
        store.save(record)
        record.destroy()
        val file = File(context.noBackupFilesDir, "paired_receiver_v1.enc")
        assertTrue(file.exists())
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        bytes.fill(0)

        assertNull(store.load())
        assertFalse(file.exists())
    }

    private fun record(secret: ByteArray): PairingRecord = PairingRecord(
        receiverId = "11111111-2222-4333-8444-555555555555",
        label = "Living room PC",
        host = "192.168.1.20",
        port = 9_000,
        secret = secret,
        credentialExpiresAtEpochSeconds = now + 86_400,
        latencyMs = 120,
        pbKeyLength = 32,
    )
}
