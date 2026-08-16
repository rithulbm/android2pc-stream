package dev.localstream.sender.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PairingPayloadParserTest {
    private val parser = PairingPayloadParser()
    private val now = 1_786_861_700L
    private val secret = ByteArray(32) { index -> index.toByte() }
    private val secretText = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)

    @Test
    fun validPayloadReturnsStrictRecord() {
        val result = parser.parse(validPayload(), now) as PairingParseResult.Success

        assertEquals("550e8400-e29b-41d4-a716-446655440000", result.record.receiverId)
        assertEquals("Studio PC", result.record.label)
        assertEquals("192.168.1.20", result.record.host)
        assertEquals(9000, result.record.port)
        assertEquals(120, result.record.latencyMs)
        assertEquals(32, result.record.pbKeyLength)
        assertArrayEquals(secret, result.record.copySecret())
    }

    @Test
    fun malformedUnknownVersionOversizedAndMissingInputsFailClosed() {
        assertFailure("https://example.test", PairingError.MALFORMED)
        assertFailure(validPayload().replace("/v1?", "/v2?"), PairingError.UNKNOWN_PROTOCOL_VERSION)
        assertFailure("x".repeat(2049), PairingError.TOO_LARGE)
        assertFailure(validPayload().replace("&pbkeylen=32", ""), PairingError.MISSING_FIELD)
        assertFailure(validPayload() + "&extra=x", PairingError.UNKNOWN_FIELD)
        assertFailure(validPayload() + "&port=9001", PairingError.DUPLICATE_FIELD)
        assertFailure(validPayload().replace("label=Studio%20PC", "label=Studio+PC"), PairingError.MALFORMED)
        assertFailure(validPayload().replace("label=Studio%20PC", "label=Studio%2fPC"), PairingError.MALFORMED)
        assertFailure(validPayload().replace("label=Studio%20PC", "label=Studio%2DPC"), PairingError.MALFORMED)
        assertFailure(validPayload().replace("label=Studio%20PC", "label=StudioéPC"), PairingError.MALFORMED)
    }

    @Test
    fun expiredAndUnreasonablyLongLivedPayloadsAreRejected() {
        assertFailure(validPayload(qrExpiry = now), PairingError.EXPIRED_QR)
        assertFailure(validPayload(qrExpiry = now + 601), PairingError.INVALID_EXPIRY)
        assertFailure(
            validPayload(credentialExpiry = now + 367L * 24L * 60L * 60L),
            PairingError.INVALID_EXPIRY,
        )
    }

    @Test
    fun unsafeEndpointsAndSecretsAreRejected() {
        assertFailure(validPayload(host = "8.8.8.8"), PairingError.INVALID_ENDPOINT)
        assertFailure(validPayload(host = "127.0.0.1"), PairingError.INVALID_ENDPOINT)
        assertFailure(validPayload(host = "192.168.001.20"), PairingError.INVALID_ENDPOINT)
        assertFailure(validPayload(host = "224.0.0.1"), PairingError.INVALID_ENDPOINT)
        assertFailure(validPayload(secretValue = "short"), PairingError.INVALID_SECRET)
        assertFailure(validPayload(secretValue = "A".repeat(42) + "%2F"), PairingError.INVALID_SECRET)
    }

    @Test
    fun labelsRejectControlsAndBidiOverrides() {
        assertFailure(
            validPayload().replace("label=Studio%20PC", "label=Studio%0APC"),
            PairingError.INVALID_LABEL,
        )
        assertFailure(
            validPayload().replace("label=Studio%20PC", "label=Studio%E2%80%AEPC"),
            PairingError.INVALID_LABEL,
        )
    }

    @Test
    fun everySupportedPrivateIpv4RangeIsAccepted() {
        val hosts = listOf("10.2.3.4", "172.16.0.1", "172.31.255.254", "192.168.0.2", "169.254.8.9")
        for (host in hosts) {
            assertTrue(parser.parse(validPayload(host = host), now) is PairingParseResult.Success)
        }
    }

    private fun assertFailure(payload: String, expected: PairingError) {
        val result = parser.parse(payload, now) as PairingParseResult.Failure
        assertEquals(expected, result.error)
    }

    private fun validPayload(
        host: String = "192.168.1.20",
        secretValue: String = secretText,
        qrExpiry: Long = now + 300,
        credentialExpiry: Long = now + 86_400,
    ): String = "pcstream://pair/v1?" +
        "receiver_id=550e8400-e29b-41d4-a716-446655440000" +
        "&label=Studio%20PC" +
        "&host=$host" +
        "&port=9000" +
        "&secret=$secretValue" +
        "&qr_expires=$qrExpiry" +
        "&credential_expires=$credentialExpiry" +
        "&latency_ms=120" +
        "&pbkeylen=32"
}
