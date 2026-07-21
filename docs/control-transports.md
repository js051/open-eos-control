# Control Transports

Open EOS Control grows around a shared camera-control contract, not one protocol. UI code asks for camera actions; each backend decides how to perform them.

## Current Backend

### CCAPI network

- Status: implemented.
- Platforms: Android now; iOS and PC can reuse the same protocol model later.
- Connection: Wi-Fi or wired network when the camera exposes CCAPI over HTTP/HTTPS.
- Current strengths: no driver, direct mobile control, easy HTTP diagnostics, working live view path on R6 Mark III, advertised still/manual shutter commands, and paged media retrieval.
- Current tradeoffs: live view is JPEG polling today, so smoothness and latency depend on Wi-Fi, camera response time, and device JPEG decode cost.
- Device-validation queue: still capture, shutter half-press, movie recording, tap focus, media browser, and media download on EOS R6 Mark III.
- Planned upgrades: focus drive where a documented endpoint is advertised and RTP live view experiments.

## Wired Backends

### Android USB/PTP

- Status: standards-based backend implemented; EOS R6 Mark III device validation is required.
- Connection: Android USB host/OTG to camera USB.
- Current implementation: enumerate Android USB devices, request permission, claim a `06/01/01` Still Image interface, use buffered bulk transfers, open/close a PTP session, read DeviceInfo/storage/property descriptors and values, perform safe advertised standard property writes, list object metadata, and stream object downloads to Android SAF destinations.
- Standard still capture is enabled only when DeviceInfo advertises `InitiateCapture (0x100E)`. A successful response is reported as command acceptance; the physical result still needs an R6 Mark III validation record.
- Standard property controls are enabled only for writable camera-advertised descriptors with bounded options; the physical values and required Canon EOS vendor gaps still need an R6 Mark III validation record.
- Next milestone: record the real-device operations and property descriptors, then implement only the Canon EOS vendor operations needed for capabilities absent from standard PTP.
- Research track: USB Live View, half-press, focus, movie control, and any setting absent from standard PTP. These require proven Canon vendor operations on EOS R6 Mark III.
- Tradeoffs: best pure phone-to-camera wired path, but it requires a real PTP engine plus Canon vendor-extension testing.

### Desktop bridge

- Status: planned backend in code; bridge protocol to be implemented.
- Connection: app talks to a local desktop service; desktop service controls the camera over USB.
- Engines: libgphoto2 for the open-source path; optional user-installed Canon EDSDK adapter for Windows, macOS, or Linux where Canon's current package and licensing allow local use.
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
2. Validate the implemented Android USB/PTP session, DeviceInfo, storage, media, download, and conditional standard capture paths on EOS R6 Mark III.
3. Validate the implemented Android USB/PTP property descriptors, values, and safe standard writes.
4. Prove and add the minimum Canon EOS vendor operations required for capture and setting gaps.
5. Add USB/PTP live view preview if R6 Mark III allows it.
6. Add desktop bridge protocol tests.
7. Add libgphoto2 bridge adapter.
8. Add optional local EDSDK bridge adapter.
