package dev.localstream.sender.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReconnectPolicyTest {
    private val policy = ReconnectPolicy()

    @Test
    fun exponentialBackoffIsCapped() {
        assertEquals(1_000L, policy.delayMs(0, 0))
        assertEquals(2_000L, policy.delayMs(1, 1_000))
        assertEquals(8_000L, policy.delayMs(3, 10_000))
        assertEquals(30_000L, policy.delayMs(9, 200_000))
    }

    @Test
    fun attemptsAndElapsedWindowAreBounded() {
        assertNull(policy.delayMs(-1, 0))
        assertNull(policy.delayMs(10, 0))
        assertNull(policy.delayMs(0, -1))
        assertNull(policy.delayMs(0, 300_000))
    }
}

