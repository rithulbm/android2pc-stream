package dev.localstream.sender

/**
 * Keeps the scan-to-stream handoff deterministic and independently testable.
 * Android permission prompts remain the final gate before camera or microphone
 * access begins.
 */
enum class PairingAutoStartAction {
    NONE,
    WAIT_FOR_CAPABILITIES,
    REQUEST_PERMISSIONS,
    SHOW_UNAVAILABLE_CONFIGURATION,
}

fun pairingAutoStartAction(
    pendingAfterSuccessfulScan: Boolean,
    capabilitiesReady: Boolean,
    streamConfigurationAvailable: Boolean,
    streamActive: Boolean,
): PairingAutoStartAction = when {
    !pendingAfterSuccessfulScan || streamActive -> PairingAutoStartAction.NONE
    !capabilitiesReady -> PairingAutoStartAction.WAIT_FOR_CAPABILITIES
    !streamConfigurationAvailable -> PairingAutoStartAction.SHOW_UNAVAILABLE_CONFIGURATION
    else -> PairingAutoStartAction.REQUEST_PERMISSIONS
}
