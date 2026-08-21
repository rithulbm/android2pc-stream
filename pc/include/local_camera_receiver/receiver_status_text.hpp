#pragma once

#include "local_camera_receiver/srt_listener.hpp"

#include <string>

namespace lcr {

inline std::string receiver_status_text(const ReceiverStatus &status)
{
    if (status.state == ReceiverState::streaming && !status.connected_device.empty()) {
        std::string message = "Connected phone: " + status.connected_device;
        if (!status.peer_address.empty()) message += " (" + status.peer_address + ")";
        return message;
    }
    if (status.state == ReceiverState::authenticating) {
        if (!status.connected_device.empty() && status.accepted_packets_this_peer > 0) {
            if (!status.pipe_connected) return "Phone connected securely. Valid TS arriving; waiting for decoder pipe...";
            if (!status.decoder_ready) return "Phone connected securely. Media is arriving; waiting for OBS to decode the first video frame...";
        }
        return "Phone found. Checking the secure pairing...";
    }
    if (status.state == ReceiverState::reconnecting) {
        return status.connected_device.empty()
            ? "Phone disconnected. Reconnecting automatically..."
            : "Reconnecting to " + status.connected_device + " automatically...";
    }
    if (status.state == ReceiverState::listening) {
        if (status.error == ReceiverError::authentication) {
            return "A phone was rejected. Show a new pairing QR and scan it again.";
        }
        if (status.error == ReceiverError::encryption_downgrade) {
            return "A phone was rejected because the secure connection did not match.";
        }
        return "Waiting for your paired phone. Scanning the QR starts streaming automatically.";
    }
    if (status.state == ReceiverState::failed) {
        switch (status.error) {
        case ReceiverError::bind:
            return "Receiver error: port 9000 is unavailable. Close any duplicate receiver source, then refresh.";
        case ReceiverError::invalid_config:
            return "Receiver setup is missing or expired. Show a new pairing QR.";
        case ReceiverError::startup:
        case ReceiverError::listen:
            return "Receiver could not start. Restart OBS, then refresh this status.";
        default:
            return "Receiver stopped after a connection error. Refresh this status to try again.";
        }
    }
    return "Waiting for receiver setup. Show the pairing QR to connect your phone.";
}

} // namespace lcr
