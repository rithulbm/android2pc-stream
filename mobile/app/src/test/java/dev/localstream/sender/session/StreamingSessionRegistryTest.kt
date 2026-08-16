package dev.localstream.sender.session

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingSessionRegistryTest {
    @Test
    fun listenerReceivesCurrentAndSubsequentAllowlistedState() {
        val initial = PublicStreamSnapshot(PublicStreamState.IDLE, "Ready")
        val streaming = PublicStreamSnapshot(PublicStreamState.STREAMING, "Streaming", "1080p 30 FPS")
        StreamingSessionRegistry.publish(initial)
        val observed = mutableListOf<PublicStreamSnapshot>()
        val listener: (PublicStreamSnapshot) -> Unit = observed::add

        StreamingSessionRegistry.addListener(listener)
        StreamingSessionRegistry.publish(streaming)
        StreamingSessionRegistry.removeListener(listener)
        StreamingSessionRegistry.publish(PublicStreamSnapshot(PublicStreamState.ERROR, "Stopped"))

        assertEquals(listOf(initial, streaming), observed)
        StreamingSessionRegistry.publish(initial)
    }

    @Test
    fun duplicateListenerRegistrationDoesNotDuplicateNotifications() {
        val initial = PublicStreamSnapshot(PublicStreamState.IDLE, "Ready")
        StreamingSessionRegistry.publish(initial)
        var notifications = 0
        val listener: (PublicStreamSnapshot) -> Unit = { notifications += 1 }

        StreamingSessionRegistry.addListener(listener)
        StreamingSessionRegistry.addListener(listener)
        StreamingSessionRegistry.publish(PublicStreamSnapshot(PublicStreamState.CONNECTING, "Connecting"))
        StreamingSessionRegistry.removeListener(listener)

        // addListener intentionally emits the current snapshot each time; the publish itself is once.
        assertEquals(3, notifications)
        StreamingSessionRegistry.publish(initial)
    }
}
