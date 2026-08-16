# Physical-device validation checklist

Automated tests and emulators do not prove the items below.

## Required setup

- Android 14 or newer physical phone whose vendor documentation/capability dump confirms 3840x2160 at 60 FPS and hardware HEVC encode
- Wi-Fi 6 or newer local network; PC on Ethernet where possible
- Future protocol-v1 SRT listener/receiver with aggregate metrics but no media/secret logging
- Charger disconnected for battery measurements, then a separate powered thermal run

## Runs

- 30-minute and 120-minute camera streams at the highest supported profile
- Display off and device locked for the sustained portion
- Activity backgrounded and removed from Recents
- Lock/unlock cycles and notification Stop action
- Wi-Fi loss, return, DHCP/local-IP change, receiver restart, and mesh/AP roaming
- Incoming call or audio-focus interruption
- Thermal warning, severe fallback, and critical safe stop
- OEM battery-management defaults plus the documented system help route where necessary
- Force-stop and reboot confirm that capture does not restart

## Record

- selected/actual size, frame rate, codec/profile/level, bitrate, keyframe interval, and encoder name
- SRT RTT, latency, packet loss/retransmits, send queue high-water mark, and reconnect count
- A/V offset and timestamp discontinuities
- thermal status and temperature observations
- battery percentage and estimated drain per hour
- foreground-service, camera, microphone, and notification survival
- every quality fallback and user-visible reason

