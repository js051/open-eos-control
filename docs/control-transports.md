# Control Transports

Open EOS Control grows around a shared camera-control contract, not one protocol. UI code asks for camera actions; each backend decides how to perform them.

## Current Backend

### CCAPI network

- Status: implemented.
- Platforms: Android now; iOS and PC can reuse the same protocol model later.
- Connection: Wi-Fi or wired network when the camera exposes CCAPI over HTTP/HTTPS.
- Current strengths: no driver, direct mobile control, easy HTTP diagnostics, working live view path on R6 Mark III.
- Current tradeoffs: live view is JPEG polling today, so smoothness and latency depend on Wi-Fi, camera response time, and device JPEG decode cost.
- Planned upgrades: capability diagnostics, media browser/download, still capture, shutter half-press, RTP live view experiments.

## Planned Wired Backends

### Android USB/PTP

- Status: planned backend in code; not product-ready yet.
- Connection: Android USB host/OTG to camera USB.
- First milestone: enumerate Canon USB devices, request permission, open bulk/interrupt endpoints, open a PTP session, read device info, and list properties.
- Second milestone: still capture, exposure writes, storage/media listing, and clear error reporting.
- Third milestone: live view preview if EOS R6 Mark III exposes compatible Canon PTP vendor operations.
- Tradeoffs: best pure phone-to-camera wired path, but it requires a real PTP engine plus Canon vendor-extension testing.

### Desktop bridge

- Status: planned backend in code; bridge protocol to be implemented.
- Connection: app talks to a local desktop service; desktop service controls the camera over USB.
- Engines: libgphoto2 for the open-source path; optional user-installed Canon EDSDK adapter for Windows/macOS where licensing allows local use.
- Strengths: fastest path to mature tethering, capture download, and high-speed live view.
- Tradeoffs: requires a computer in the loop, so it is a different product mode from direct mobile control.

### iOS CCAPI

- Status: planned platform client, not in this Android module.
- Connection: iPhone/iPad to camera CCAPI over Wi-Fi.
- First milestone: reuse the CCAPI command model, capability matrix, and live view contract.
- USB/PTP stance: research track only until Apple platform constraints and public APIs are validated against Canon EOS bodies.

## Shared Backend Surface

Each backend should map into this surface:

- connect/disconnect
- camera identity and profile
- battery, storage, and camera status
- capability matrix and dynamic settings
- exposure, white balance, and generic setting writes
- still capture, half-press, recording, tap focus, and focus drive where available
- media list/download where available
- live view source, size, and FPS request

## Implementation Order

1. Keep CCAPI stable and improve diagnostics.
2. Add Android USB/PTP read-only diagnostics.
3. Add Android USB/PTP still capture and setting writes.
4. Add USB/PTP live view preview if R6 Mark III allows it.
5. Add desktop bridge protocol tests.
6. Add libgphoto2 bridge adapter.
7. Add optional local EDSDK bridge adapter.
