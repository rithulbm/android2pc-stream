package dev.localstream.sender.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SafeDiagnosticsTest {
    @Test
    fun outputContainsOnlyAllowlistedBoundedFields() {
        val output = SafeDiagnostics.format(
            DiagnosticEvent(
                code = SafeErrorCode.QUEUE_FULL,
                profileName = "UHD_60",
                reconnectAttempt = 99,
                queueUtilizationPercent = 500,
            ),
        )
        assertEquals("code=QUEUE_FULL profile=UHD_60 reconnect_attempt=10 queue_percent=100", output)
    }

    @Test
    fun arbitraryProfileAndExceptionMessagesNeverReachDiagnostics() {
        val secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val output = SafeDiagnostics.format(
            DiagnosticEvent(SafeErrorCode.INTERNAL_FAILURE, secret, null, null),
        )
        assertFalse(output.contains(secret))
        assertEquals(
            SafeErrorCode.RECEIVER_UNREACHABLE,
            SafeDiagnostics.codeFor(IOException("host=192.168.1.20 secret=$secret")),
        )
        assertTrue(output.startsWith("code="))
    }
}

