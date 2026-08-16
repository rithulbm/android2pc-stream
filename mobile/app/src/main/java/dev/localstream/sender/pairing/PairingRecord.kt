package dev.localstream.sender.pairing

import java.util.Arrays

/**
 * Validated receiver configuration. The secret is mutable so callers can erase it after
 * configuring the native transport. It must never be interpolated into logs or UI text.
 */
class PairingRecord(
    val receiverId: String,
    val label: String,
    val host: String,
    val port: Int,
    secret: ByteArray,
    val credentialExpiresAtEpochSeconds: Long,
    val latencyMs: Int,
    val pbKeyLength: Int,
) {
    private val secretBytes = secret.copyOf()

    fun copySecret(): ByteArray = secretBytes.copyOf()

    fun isCredentialValid(nowEpochSeconds: Long): Boolean =
        credentialExpiresAtEpochSeconds > nowEpochSeconds

    fun destroy() {
        Arrays.fill(secretBytes, 0.toByte())
    }
}

