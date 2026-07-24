# Control Transports

Open EOS Control grows around a shared camera-control contract, not one protocol. UI code asks for camera actions; each backend decides how to perform them.

## Current Backend

### CCAPI network

- Status: implemented.
- Platforms: direct Android and iOS clients; PC uses the native CCAPI engine inside Desktop Bridge.
- Connection: Wi-Fi or wired network when the camera exposes CCAPI over HTTP/HTTPS.
- Current strengths: no driver, direct mobile/PC control, easy HTTP diagnostics, working JPEG Live View on R6 Mark III, strictly advertised writable settings and shutter/autofocus/focus-drive commands, complete lifecycle gating, and paged media retrieval with capability-gated deletion. Android and iOS implement Canon's advertised H.264 RTP path with persistent native decode surfaces; PC receives the same RFC 3550/RFC 6184 stream and decodes it through PyAV/FFmpeg for the Bridge JPEG contract.
- Android routes camera HTTP and RTP UDP sockets only through the Wi-Fi network that reaches the camera, allowing cellular internet to remain available without process-wide network binding. A physical coexistence result is still required.
- Current tradeoffs: JPEG polling smoothness and latency depend on Wi-Fi, camera response time, and JPEG decode cost. RTP is exposed only when discovery advertises both required operations and the phone has a camera-Wi-Fi IPv4 address; its video path still needs physical-camera validation and does not render the SDP's AAC LATM audio stream.
- Device-validation queue: still capture, independent AF-ON, shutter half-press, movie recording, tap focus, Click White Balance, manual focus drive, media browser, media download, and media deletion on EOS R6 Mark III.
- RTP status: Android, iOS, and PC H.264 video receive/decode is implemented and contract-tested. The last sanitized R6 Mark III report did not advertise RTP, so that camera correctly remains on JPEG polling until a new discovery report proves otherwise. Physical-camera RTP validation and AAC LATM audio remain pending; audio is not exposed as supported.

## Wired Backends

### Android USB/PTP

- Status: standards-based backend and a focused Canon EOS vendor layer are implemented; EOS R6 Mark III device validation is required.
- Connection: Android USB host/OTG to camera USB.
- Current implementation: enumerate Android USB devices, request permission, claim a `06/01/01` Still Image interface, use buffered bulk transfers, open/close a PTP session, read DeviceInfo/storage/property descriptors and values, perform safe advertised standard property writes, list object metadata, stream object downloads to Android SAF destinations, and delete exact object handles only when `DeleteObject (0x100B)` is advertised.
- Standard still capture is enabled only when DeviceInfo advertises `InitiateCapture (0x100E)`. A successful response is reported as command acceptance; the physical result still needs an R6 Mark III validation record.
- Standard property controls are enabled only for writable camera-advertised descriptors with bounded options. Canon ISO/Tv/Av/WB controls separately require `0xC189/0xC18A` event state and use only camera-advertised choices with `SetDevicePropValueEx`; both paths still need an R6 Mark III validation record.
- Canon still capture and half-press require the full remote/event operation set. Capture is not reported as success until a captured-object event arrives; press/release commands are balanced on failure paths.
- Canon manual focus uses the advertised `DriveLens` operation with Near/Far steps 1-3. Canon JPEG Live View enables EVF mode/output, polls `GetViewFinderData`, and parses only validated JPEG block types.
- Canon movie control writes `EVFRecordStatus (0xD1B8)` through `SetDevicePropValueEx`: Card (`4`) starts card recording and None (`0`) stops it. The capability is exposed only after camera events advertise both values; SDRAM (`3`) is treated as preview rather than recording.
- Canon shooting-mode control writes camera-advertised `AutoExposureMode (0xD105)` UINT16 values through `SetDevicePropValueEx`. The app's Photo/Video context synchronizes only when the writable `shootingmode` list proves a target value, and restores only a previously observed photo mode.
- The Canon mappings follow a pinned libgphoto2 revision and are test-covered. They remain in device-validation status until exercised and recorded on the physical R6 Mark III.
- Next milestone: validate the standard and Canon paths, including movie start/stop, on the camera, then map only measured property gaps.
- Research track: Touch AF and Canon vendor settings beyond the implemented shooting-mode, exposure, aspect-ratio, power-zoom and movie paths. No active controls are exposed without proven state and value semantics.
- Tradeoffs: best pure phone-to-camera wired path, but it requires a real PTP engine plus Canon vendor-extension testing.

### Desktop bridge and PC control

- Status: HTTP service, built-in PC control UI, libgphoto2 CLI engine, direct CCAPI engine, and Android/iOS clients are implemented and tested; EOS R6 Mark III device validation remains.
- Connection: the PC UI can control a USB camera through libgphoto2 or connect directly to the camera's wireless CCAPI origin. Android and iOS can use the same authenticated bridge for a computer-attached USB camera.
- Engines: `libgphoto2` is the executable open-source USB path; `ccapi` is the executable HTTP(S) wireless path. The Canon EDSDK adapter remains optional research and no Canon binary is redistributed.
- Current implementation: both engines map into the same session/capability API. libgphoto2 provides camera discovery, dynamic settings, capture, independent AF-ON through its balanced half-press path, explicit half-press, movie target control, relative focus drive, persistent MJPEG preview with control-safe restart and bounded single-frame fallback, media transfer and ability-gated deletion. CCAPI provides advertised-operation discovery, dynamic settings, capture/release, AF-ON/half-press, recording, geometry-backed Tap AF/Click WB, focus drive, bounded JPEG polling, and advertised RTP H.264 through a routed UDP receiver plus PyAV decoder. RTP capability requires both Canon endpoints, a valid route and decoder; AUTO falls back to JPEG only after cleanup. The built-in responsive PC UI offers USB/CCAPI mode selection, Auto/RTP/JPEG selection, English/Traditional Chinese, authenticated binary transfer, confirmed deletion and redacted diagnostics. Bridge Bearer tokens and camera passwords are memory-only; camera URL and username may be remembered. Loopback is the secure service default; LAN use requires a Bearer token.
- Strengths: immediate access to mature libgphoto2 Canon mappings, including the checked-in upstream R6 Mark III capability snapshot.
- Tradeoffs: Android Bridge use requires a computer in the loop. USB CLI preview now keeps one `capture-movie` process for MJPEG and allows up to 30 FPS, but camera controls must briefly restart that process and physical R6 Mark III throughput remains unverified; fallback preview is capped at 5 FPS. A future native libgphoto2 adapter could avoid process restarts. Direct PC CCAPI defaults to 15 FPS and allows up to 30 FPS without claiming the camera will sustain it. RTP still decodes every H.264 access unit to preserve prediction state, while the requested FPS caps JPEG conversion/output; AAC LATM audio is not decoded.

### iOS CCAPI and Desktop Bridge

- Status: native Swift command/transport core and iOS 17 SwiftUI product UI are implemented and simulator-tested; physical iPhone and camera validation remain.
- Connection: iPhone/iPad can connect directly to camera CCAPI over Wi-Fi, or reach a PC over LAN and control its USB camera through Desktop Bridge.
- Current implementation: `ios/OpenEOSCore` provides direct CCAPI discovery plus a native authenticated Bridge client with service validation, USB discovery, camera selection and session cleanup. Both map capability-gated settings and commands, JPEG Live View, still capture, independent AF-ON, half-press, recording, available focus operations, media download/deletion and redacted diagnostics into one app session; the Bridge path also exposes bounded media thumbnails when its engine advertises them. `ios/OpenEOSControl` adds mode-specific connection forms, Photo/Video control, manual focus drive when advertised, adjustable Live View, lazy thumbnail media rows, confirmation-gated media and Debug views, offline preview, English/Traditional Chinese selection, and safe portrait/landscape behavior.
- Automated evidence: macOS CI builds the final app bundle, verifies resources and network/orientation metadata, runs core/app unit tests, and completes English control/debug, media-deletion confirmation, Traditional Chinese connection, and offline Desktop Bridge form workflows on an iPhone Simulator.
- Next milestone: validate direct CCAPI and PC-attached USB Bridge paths on a physical iPhone and EOS R6 Mark III.
- USB/PTP stance: research track only until Apple platform constraints and public APIs are validated against Canon EOS bodies.

## Shared Backend Surface

Each backend should map into this surface:

- connect/disconnect
- camera identity and profile
- battery, storage, and camera status
- capability matrix and dynamic settings
- exposure, white balance, and generic setting writes
- still capture, half-press, recording, tap focus, Click White Balance, and focus drive where available
- media list/download/delete where available
- live view source, size, and FPS request

## Implementation Order

1. Keep CCAPI stable and improve diagnostics.
2. Validate the implemented Android USB/PTP session, DeviceInfo, storage, media, download, standard properties, and conditional standard capture paths on EOS R6 Mark III.
3. Validate the implemented Canon EOS remote release, half-press, ISO/Tv/Av/WB, movie recording, manual focus drive, and JPEG Live View paths.
4. Record the remaining R6 Mark III vendor property/event gaps and add only mappings supported by reliable evidence.
5. Prove USB Touch AF coordinate semantics before exposing that control.
6. Validate the implemented PC direct CCAPI and Android-to-desktop bridge paths with EOS R6 Mark III.
7. Measure persistent CLI MJPEG throughput and control-restart latency on R6 Mark III, then consider a native libgphoto2 adapter only if it materially improves the recorded result.
8. Add an optional local EDSDK bridge adapter after SDK access, licensing, and supported host platforms are verified.
