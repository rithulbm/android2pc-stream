package dev.localstream.sender.session

import java.util.concurrent.CopyOnWriteArraySet

enum class PublicStreamState {
    IDLE,
    STARTING,
    CONNECTING,
    STREAMING,
    RECONNECTING,
    STOPPING,
    ERROR,
}

data class PublicStreamSnapshot(
    val state: PublicStreamState,
    val message: String,
    val profileName: String? = null,
)

/** In-process UI status only. It contains no endpoint, identifier, secret, or provider data. */
object StreamingSessionRegistry {
    private val listeners = CopyOnWriteArraySet<(PublicStreamSnapshot) -> Unit>()

    @Volatile
    private var snapshot = PublicStreamSnapshot(PublicStreamState.IDLE, "Ready")

    fun current(): PublicStreamSnapshot = snapshot

    fun publish(next: PublicStreamSnapshot) {
        snapshot = next
        listeners.forEach { listener -> listener(next) }
    }

    fun addListener(listener: (PublicStreamSnapshot) -> Unit) {
        listeners += listener
        listener(snapshot)
    }

    fun removeListener(listener: (PublicStreamSnapshot) -> Unit) {
        listeners -= listener
    }
}
