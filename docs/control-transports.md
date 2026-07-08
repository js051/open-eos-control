# Control Transports

Open EOS Control should grow around a shared camera-control contract, not around one protocol. The Android UI should stay focused on camera actions and state; each transport backend should decide how to talk to the camera.

## Current Backend

### CCAPI network

- Status: implemented.
- Connection: Wi-Fi or wired network when the camera exposes CCAPI over HTTP/HTTPS.
- Strengths: no driver, works directly from Android, easy to debug with HTTP logs, good for mobile-first control.
- Tradeoffs: live view is currently HTTP frame polling, so smoothness and latency depend heavily on Wi-Fi, camera response time, and Android JPEG decode cost.

## Planned Wired Paths

### Android USB PTP

- Status: planned.
- Connection: Android USB host/OTG to camera USB.
- Strengths: direct phone-to-camera wired path, no computer required, better physical reliability than Wi-Fi.
- Tradeoffs: requires a PTP engine plus Canon vendor extensions. Live view, capture, focus drive, and storage operations must be implemented and tested per camera generation.
- Best first milestone: detect Canon PTP device, open bulk/interrupt endpoints, read device info, list properties, and expose a read-only diagnostic panel.

### Desktop bridge

- Status: planned.
- Connection: Android app talks to a small desktop service; desktop service controls the camera over USB.
- Candidate engines: Canon EDSDK on Windows/macOS, libgphoto2 on Linux/macOS.
- Strengths: lets the Android UI reuse mature desktop camera-control stacks and gives the best route to high-speed tethering.
- Tradeoffs: requires a computer in the loop, so it is not the same product mode as direct Android control.
- Best first milestone: define a local WebSocket/HTTP bridge protocol that mirrors `CameraControlBackend`.

## Backend Contract

Each backend should provide the same core surface:

- connect/disconnect
- camera identity
- battery/storage/status
- shooting capabilities and dynamic settings
- set exposure and generic settings
- tap/trigger focus where available
- start/stop recording
- live view frame source

Backend-specific features should be exposed as capabilities, not hard-coded UI assumptions. For example, a USB/PTP backend may support capture download or manual focus drive, while CCAPI may support network-only live view endpoints.

## Recommended Order

1. Keep CCAPI stable and improve diagnostics.
2. Add a read-only Android USB/PTP backend: device discovery, PTP session open, device info, property list.
3. Add USB/PTP still capture and setting writes.
4. Add USB/PTP live view preview if R6 Mark III exposes compatible Canon vendor operations.
5. Add a desktop bridge backend for EDSDK/libgphoto2 where high-speed tethering matters more than phone-only operation.
