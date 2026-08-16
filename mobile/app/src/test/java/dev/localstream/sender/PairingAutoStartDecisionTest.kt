package dev.localstream.sender

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingAutoStartDecisionTest {
    @Test
    fun successfulScanWaitsForCameraProbeThenRequestsPermissions() {
        assertEquals(
            PairingAutoStartAction.WAIT_FOR_CAPABILITIES,
            pairingAutoStartAction(
                pendingAfterSuccessfulScan = true,
                capabilitiesReady = false,
                streamConfigurationAvailable = false,
                streamActive = false,
            ),
        )
        assertEquals(
            PairingAutoStartAction.REQUEST_PERMISSIONS,
            pairingAutoStartAction(
                pendingAfterSuccessfulScan = true,
                capabilitiesReady = true,
                streamConfigurationAvailable = true,
                streamActive = false,
            ),
        )
    }

    @Test
    fun successfulScanStopsCleanlyWhenNoSupportedStreamConfigurationExists() {
        assertEquals(
            PairingAutoStartAction.SHOW_UNAVAILABLE_CONFIGURATION,
            pairingAutoStartAction(
                pendingAfterSuccessfulScan = true,
                capabilitiesReady = true,
                streamConfigurationAvailable = false,
                streamActive = false,
            ),
        )
    }

    @Test
    fun noScanOrAlreadyActiveStreamNeverStartsAgain() {
        assertEquals(
            PairingAutoStartAction.NONE,
            pairingAutoStartAction(
                false,
                capabilitiesReady = true,
                streamConfigurationAvailable = true,
                streamActive = false,
            ),
        )
        assertEquals(
            PairingAutoStartAction.NONE,
            pairingAutoStartAction(
                true,
                capabilitiesReady = true,
                streamConfigurationAvailable = true,
                streamActive = true,
            ),
        )
    }
}
