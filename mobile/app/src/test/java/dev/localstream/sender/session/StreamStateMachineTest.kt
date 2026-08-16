package dev.localstream.sender.session

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamStateMachineTest {
    @Test
    fun unpairedAndPermissionGatesFailClosed() {
        val machine = StreamStateMachine(initiallyPaired = false, initiallyPermitted = false)
        assertEquals(StreamState.UNPAIRED, machine.handle(StreamEvent.StartRequested).state)
        machine.handle(StreamEvent.PairingAvailable)
        assertEquals(StreamState.PERMISSION_REQUIRED, machine.handle(StreamEvent.StartRequested).state)
        assertEquals(
            StreamState.READY,
            machine.handle(StreamEvent.PermissionsUpdated(granted = true)).state,
        )
    }

    @Test
    fun happyPathAndDuplicateStartAreIdempotent() {
        val machine = StreamStateMachine(initiallyPaired = true, initiallyPermitted = true)
        val started = machine.handle(StreamEvent.StartRequested)
        val duplicate = machine.handle(StreamEvent.StartRequested)
        assertEquals(StreamState.STARTING, duplicate.state)
        assertEquals(started.generation, duplicate.generation)
        assertEquals(
            StreamState.CONNECTING,
            machine.handle(StreamEvent.PipelineReady(started.generation)).state,
        )
        assertEquals(
            StreamState.STREAMING,
            machine.handle(StreamEvent.TransportConnected(started.generation)).state,
        )
    }

    @Test
    fun stopWhileConnectingInvalidatesLateCallbacksAndDuplicateStop() {
        val machine = StreamStateMachine(initiallyPaired = true, initiallyPermitted = true)
        val generation = machine.handle(StreamEvent.StartRequested).generation
        machine.handle(StreamEvent.PipelineReady(generation))
        val stopping = machine.handle(StreamEvent.StopRequested)
        val duplicateStop = machine.handle(StreamEvent.StopRequested)
        assertEquals(StreamState.STOPPING, duplicateStop.state)
        assertEquals(stopping.generation, duplicateStop.generation)

        assertEquals(
            StreamState.STOPPING,
            machine.handle(StreamEvent.TransportConnected(generation)).state,
        )
        assertEquals(
            StreamState.STOPPED,
            machine.handle(StreamEvent.CleanupComplete(stopping.generation)).state,
        )
    }

    @Test
    fun stopRetryRaceCannotReviveSession() {
        val machine = StreamStateMachine(initiallyPaired = true, initiallyPermitted = true)
        val generation = machine.handle(StreamEvent.StartRequested).generation
        machine.handle(StreamEvent.PipelineReady(generation))
        machine.handle(StreamEvent.NetworkLost(generation))
        val stopGeneration = machine.handle(StreamEvent.StopRequested).generation

        machine.handle(StreamEvent.RetryConnecting(generation))
        machine.handle(StreamEvent.TransportConnected(generation))

        assertEquals(StreamState.STOPPING, machine.snapshot().state)
        assertEquals(StreamState.STOPPED, machine.handle(StreamEvent.CleanupComplete(stopGeneration)).state)
    }

    @Test
    fun removingPairingDuringStreamStopsThenBecomesUnpaired() {
        val machine = StreamStateMachine(initiallyPaired = true, initiallyPermitted = true)
        val generation = machine.handle(StreamEvent.StartRequested).generation
        machine.handle(StreamEvent.PipelineReady(generation))
        machine.handle(StreamEvent.TransportConnected(generation))
        val stopping = machine.handle(StreamEvent.PairingRemoved)
        assertEquals(StreamState.STOPPING, stopping.state)
        assertEquals(
            StreamState.UNPAIRED,
            machine.handle(StreamEvent.CleanupComplete(stopping.generation)).state,
        )
    }

    @Test
    fun interruptionPermissionRevocationAndFailureCannotLeakAnActiveState() {
        val interrupted = StreamStateMachine(initiallyPaired = true, initiallyPermitted = true)
        val firstGeneration = interrupted.handle(StreamEvent.StartRequested).generation
        interrupted.handle(StreamEvent.PipelineReady(firstGeneration))
        interrupted.handle(StreamEvent.TransportConnected(firstGeneration))
        assertEquals(StreamState.PAUSED, interrupted.handle(StreamEvent.Interrupted(firstGeneration)).state)
        assertEquals(StreamState.STREAMING, interrupted.handle(StreamEvent.Resumed(firstGeneration)).state)
        val revoked = interrupted.handle(StreamEvent.PermissionsUpdated(granted = false))
        assertEquals(StreamState.STOPPING, revoked.state)
        assertEquals(StreamState.STOPPED, interrupted.handle(StreamEvent.CleanupComplete(revoked.generation)).state)

        val failed = StreamStateMachine(initiallyPaired = true, initiallyPermitted = true)
        val failureGeneration = failed.handle(StreamEvent.StartRequested).generation
        assertEquals(StreamState.FAILED, failed.handle(StreamEvent.TerminalFailure(failureGeneration)).state)
        val stopping = failed.handle(StreamEvent.StopRequested)
        assertEquals(StreamState.STOPPED, failed.handle(StreamEvent.CleanupComplete(stopping.generation)).state)
    }

    @Test
    fun freshProcessNeverRestoresAnActiveStreamWithoutNewUserIntent() {
        val freshProcess = StreamStateMachine(initiallyPaired = true, initiallyPermitted = true)

        assertEquals(StreamState.READY, freshProcess.snapshot().state)
        assertEquals(0L, freshProcess.snapshot().generation)
    }
}
