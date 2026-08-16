package dev.localstream.sender.session

enum class StreamState {
    UNPAIRED,
    READY,
    PERMISSION_REQUIRED,
    STARTING,
    CONNECTING,
    STREAMING,
    RECONNECTING,
    PAUSED,
    STOPPING,
    STOPPED,
    FAILED,
}

sealed interface StreamEvent {
    data object PairingAvailable : StreamEvent
    data object PairingRemoved : StreamEvent
    data class PermissionsUpdated(val granted: Boolean) : StreamEvent
    data object StartRequested : StreamEvent
    data object StopRequested : StreamEvent
    data class PipelineReady(val generation: Long) : StreamEvent
    data class TransportConnected(val generation: Long) : StreamEvent
    data class NetworkLost(val generation: Long) : StreamEvent
    data class RetryConnecting(val generation: Long) : StreamEvent
    data class Interrupted(val generation: Long) : StreamEvent
    data class Resumed(val generation: Long) : StreamEvent
    data class TerminalFailure(val generation: Long) : StreamEvent
    data class CleanupComplete(val generation: Long) : StreamEvent
}

data class StateSnapshot(
    val state: StreamState,
    val generation: Long,
    val paired: Boolean,
    val permissionsGranted: Boolean,
)

/**
 * The service is the only production owner of this machine. Generation checks prevent a late
 * callback from an old camera, codec, socket, or reconnect attempt from reviving a stopped stream.
 */
class StreamStateMachine(initiallyPaired: Boolean, initiallyPermitted: Boolean) {
    private var paired = initiallyPaired
    private var permissionsGranted = initiallyPermitted
    private var generation = 0L
    private var state = if (initiallyPaired) StreamState.READY else StreamState.UNPAIRED

    @Synchronized
    fun snapshot(): StateSnapshot = StateSnapshot(state, generation, paired, permissionsGranted)

    @Synchronized
    fun handle(event: StreamEvent): StateSnapshot {
        when (event) {
            StreamEvent.PairingAvailable -> {
                paired = true
                if (state == StreamState.UNPAIRED || state == StreamState.STOPPED || state == StreamState.FAILED) {
                    state = StreamState.READY
                }
            }
            StreamEvent.PairingRemoved -> {
                paired = false
                if (isActive(state)) {
                    invalidateSession()
                    state = StreamState.STOPPING
                } else {
                    state = StreamState.UNPAIRED
                }
            }
            is StreamEvent.PermissionsUpdated -> {
                permissionsGranted = event.granted
                if (!event.granted && isActive(state)) {
                    invalidateSession()
                    state = StreamState.STOPPING
                } else if (event.granted && state == StreamState.PERMISSION_REQUIRED && paired) {
                    state = StreamState.READY
                }
            }
            StreamEvent.StartRequested -> when {
                !paired -> state = StreamState.UNPAIRED
                !permissionsGranted -> state = StreamState.PERMISSION_REQUIRED
                state == StreamState.READY || state == StreamState.STOPPED || state == StreamState.FAILED -> {
                    generation += 1L
                    state = StreamState.STARTING
                }
                else -> Unit // Duplicate Start is intentionally idempotent.
            }
            StreamEvent.StopRequested -> {
                if (isActive(state) || state == StreamState.PERMISSION_REQUIRED || state == StreamState.FAILED) {
                    invalidateSession()
                    state = StreamState.STOPPING
                }
            }
            is StreamEvent.PipelineReady -> ifCurrent(event.generation) {
                if (state == StreamState.STARTING) state = StreamState.CONNECTING
            }
            is StreamEvent.TransportConnected -> ifCurrent(event.generation) {
                if (state == StreamState.CONNECTING || state == StreamState.RECONNECTING) {
                    state = StreamState.STREAMING
                }
            }
            is StreamEvent.NetworkLost -> ifCurrent(event.generation) {
                if (state == StreamState.STREAMING || state == StreamState.CONNECTING) {
                    state = StreamState.RECONNECTING
                }
            }
            is StreamEvent.RetryConnecting -> ifCurrent(event.generation) {
                if (state == StreamState.RECONNECTING) state = StreamState.CONNECTING
            }
            is StreamEvent.Interrupted -> ifCurrent(event.generation) {
                if (state == StreamState.STREAMING) state = StreamState.PAUSED
            }
            is StreamEvent.Resumed -> ifCurrent(event.generation) {
                if (state == StreamState.PAUSED) state = StreamState.STREAMING
            }
            is StreamEvent.TerminalFailure -> ifCurrent(event.generation) {
                if (isActive(state)) state = StreamState.FAILED
            }
            is StreamEvent.CleanupComplete -> {
                // Cleanup belongs to the invalidated generation and is allowed only while stopping.
                if (state == StreamState.STOPPING && event.generation <= generation) {
                    state = if (paired) StreamState.STOPPED else StreamState.UNPAIRED
                }
            }
        }
        return snapshot()
    }

    private inline fun ifCurrent(eventGeneration: Long, block: () -> Unit) {
        if (eventGeneration == generation) block()
    }

    private fun invalidateSession() {
        generation += 1L
    }

    private fun isActive(value: StreamState): Boolean = when (value) {
        StreamState.STARTING,
        StreamState.CONNECTING,
        StreamState.STREAMING,
        StreamState.RECONNECTING,
        StreamState.PAUSED,
        -> true
        else -> false
    }
}

