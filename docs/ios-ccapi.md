# iOS Camera Transports

`ios/OpenEOSCore` is the native Swift transport and command foundation, and `ios/OpenEOSControl` is the iOS 17 SwiftUI app. They can communicate directly with a Canon camera/simulator over CCAPI HTTP(S), or with Open EOS Control Desktop Bridge over an authenticated LAN connection. They do not depend on the Android implementation.

## Implemented Core

- CCAPI discovery through `/ccapi`, including Canon's same-origin full `url` entries and relative `path` fixtures, versioned operation parsing, and `ver110`/`ver100` fallback; query data and unsafe origins/paths are never promoted to capabilities
- Capability-gated Canon event polling through a bounded long request and explicit stop lifecycle; partial event JSON is reduced to safe change keys and triggers a complete status/capability refresh without blocking Live View or controls
- Camera identity, battery, storage, exposure, white balance, and dynamic settings; writable controls require the exact setting-specific `PUT` operation and a value from camera `ability`. Object-valued `stillimagequality` is presented as separate RAW/JPEG/HEIF controls, while `wbshift` uses bounded B/A and M/G ranges. Both write Canon's complete nested value while preserving companion fields.
- Still capture, independent AF-ON through advertised Canon start/stop or a balanced half-press fallback, explicit timed half-press, recording, Tap AF, and Click White Balance only when supported
- JPEG Live View only when discovery advertises a complete lifecycle: a same-version general POST start, at least one polling frame endpoint or multipart GET/DELETE, and either matching general DELETE or Canon's POST-off general stop. Multipart continuously drains the camera stream while retaining only the newest complete JPEG; a rejected multipart DELETE cannot skip the general stop. Both paths retry without `liveviewsize` after HTTP 400, then downgrade an invalid advertised `LARGE` through `MEDIUM`/`SMALL` and remove only rejected sizes for that session. Polling conditionally restarts at `small` only after every candidate frame endpoint returns HTTP 503 `Mode not supported`. Multipart startup retries bounded transient 503 responses, and `flipdetail?kind=both` is sent without arbitrary cache query items rejected by R6 Mark III firmware 1.1.0. The effective size is returned to SwiftUI, and success is observed only after a complete frame.
- Decoded JPEG/Bridge Live View monitoring assists with memory-only imported 3D `.cube` LUT preview through Core Image `CIColorCube`, followed by a bounded 120x80 background analysis path for a mutually exclusive histogram or 64x64 luma waveform, configurable zebra, false color and focus peaking, plus geometry-only frame guides, action/title safe areas and anamorphic desqueeze. Import accepts the bounded 2-64 3D subset and rejects 1D/shaper and oversized files. Native RTP preserves direct sample-buffer rendering, so LUT and pixel analysis are intentionally unavailable there while guides and desqueeze remain active.
- Canon RTP H.264 Live View only when discovery advertises `GET rtpsessiondesc` and `POST rtp` and the App supplies a same-subnet camera-Wi-Fi IPv4 receiver; the core validates SDP, RFC 3550 packets and RFC 6184 single NAL/STAP-A/FU-A access units, owns exact start/stop cleanup, and falls back to JPEG for AUTO startup failures
- Independently advertised in-band `MP4A-LATM/48000` RTP audio with bounded sequence/timestamp/marker reassembly, an AAC-LC mono/stereo LATM extractor, `AVAudioConverter` decode and `AVAudioEngine` playback. Audio is default-muted, foreground-only, queue bounded, session scoped and failure-isolated from video; explicit `cpresent=0`, non-48 kHz streams and unsupported codecs/topologies remain unavailable.
- Bounded, same-origin media traversal and Canon CCAPI Reference 1.3 content representations on the advertised `GET /contents` operation: `?kind=thumbnail` thumbnails and `?kind=display` previews only for JPEG/CR3 candidates, plus file-backed downloads with Canon main-file query fallbacks, URLSession byte progress, cancellation, and incomplete-directory cleanup. Individual files may still reject a documented representation; text/JSON metadata is rejected even when returned with HTTP 2xx.
- Desktop Bridge media upload through an exact-length file-backed URLSession request, with byte progress, cancellation, a delegate-enforced 32 KiB response-body limit, and case-insensitive name/exact-size verification. A cancelled request performs an independent fresh media-list reconciliation so a Bridge upload committed immediately before cancellation is still reported as uploaded. Direct CCAPI does not expose upload because no verified Canon endpoint is advertised.
- Same-origin exact-path media deletion only when discovery advertises `DELETE` for `/contents` or a child operation
- Canon media protection, archive state, rating and display rotation only when discovery advertises contents `PUT`; writes use exact documented action/value bodies and require matching `kind=info` readback before success
- Basic Authentication held by the client instance and versioned diagnostic output that redacts credentials and camera serials
- Simulator mode and injectable HTTP transport for deterministic tests
- Desktop Bridge service validation, Bearer authentication, USB camera discovery/selection, session lifecycle, dynamic capability mapping, event polling, settings, capture, independent autofocus, half-press, recording, tap/drive focus when advertised, bounded JPEG frames, thumbnails and advertised display previews, file-backed media download/deletion, structured errors, and secret-redacted diagnostics

Focus drive without a camera/engine-advertised operation and direct iOS USB/PTP are not presented as implemented features. Desktop Bridge is the implemented iPhone/iPad route to a camera attached to a PC by USB. Direct iOS RTP AAC LATM audio is implemented only for the explicitly gated in-band subset above; it and the PC Bridge's PyAV RTP path still require physical-camera validation.

## Implemented App

- Direct HTTP/HTTPS/Simulator presets or Desktop Bridge URL/token with USB scan and camera selection; passwords/tokens stay in memory while non-secret URLs and usernames may be remembered
- Full-screen Photo/Video control with camera-capability gating, exposure sheets, still capture, AF-ON, recording, a Tap AF/Click White Balance action selector, manual focus drive when advertised, and adjustable JPEG polling, multipart JPEG, or Canon RTP H.264 Live View
- AUTO/RTP/multipart/JPEG source selection from camera-advertised paths; iOS uses Wi-Fi-only `NWListener` instances, RFC 6184 video depacketization and `AVSampleBufferVideoRenderer`, plus a separately gated default-muted AAC-LATM monitor. AUTO closes each failed native session before trying RTP, multipart, then JPEG polling.
- Live View FPS from 1-30, clamped to the camera-advertised range, plus JPEG size, automatic refresh, grid, rolling FPS, frame bytes, source type and endpoint diagnostics; RTP FPS is explicitly a display cap because Canon's start payload has no encoder-FPS field
- Media listing, file-backed download/share and Bridge upload with real byte progress and accessible cancel actions, a lazy details sheet for capability-gated protection/archive/rating/rotation, confirmation deletion, redacted diagnostic report, and no fake direct-CCAPI or iOS USB/PTP upload action
- English, Traditional Chinese, and system language selection
- Portrait and landscape layouts that respect system safe areas; whole-window upside-down rotation is disabled while key control content can rotate
- App icon and localization resources verified in the built bundle

## Host App Requirements

The iOS app includes a user-facing `NSLocalNetworkUsageDescription` because it connects directly to devices on the local network. Its App Transport Security configuration uses `NSAllowsLocalNetworking` for camera-local resources instead of broadly disabling ATS. `URLSessionCameraHTTPTransport` uses an ephemeral configuration, normal certificate validation, bounded timeouts, no cookies or credential storage, and `waitsForConnectivity` so the request can resume after the camera Wi-Fi route becomes available. RTP selects a non-loopback IPv4 address on the camera subnet and also requires the UDP listener's Network.framework path to be Wi-Fi, so unrelated internet traffic remains under normal iOS routing.

Official Apple references:

- [Local network privacy usage description](https://developer.apple.com/documentation/bundleresources/information-property-list/nslocalnetworkusagedescription)
- [App Transport Security local networking](https://developer.apple.com/documentation/bundleresources/information-property-list/nsapptransportsecurity/nsallowslocalnetworking)
- [`URLSessionConfiguration.waitsForConnectivity`](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/waitsforconnectivity)
- [`NWParameters.requiredInterfaceType`](https://developer.apple.com/documentation/network/nwparameters/requiredinterfacetype)
- [`NWConnection.receiveMessage`](https://developer.apple.com/documentation/network/nwconnection/receivemessage(completion:))
- [`AVSampleBufferDisplayLayer.sampleBufferRenderer`](https://developer.apple.com/documentation/avfoundation/avsamplebufferdisplaylayer/samplebufferrenderer)
- [`AVAudioConverter`](https://developer.apple.com/documentation/avfaudio/avaudioconverter)
- [`AVAudioCompressedBuffer`](https://developer.apple.com/documentation/avfaudio/avaudiocompressedbuffer)
- [`AVAudioEngine`](https://developer.apple.com/documentation/avfaudio/avaudioengine)

The app must not persist a CCAPI password or Desktop Bridge token, or print an `Authorization` header. HTTP should only be used on the isolated camera/LAN network; HTTPS keeps normal platform trust validation. A physical iPhone cannot use the Bridge loopback default: run the Bridge with an explicit LAN host and a strong `OPEN_EOS_BRIDGE_TOKEN`, then enter the computer's LAN URL in the App.

## Test

On a macOS host with Swift 5.10 or newer:

```bash
cd ios/OpenEOSCore
swift test
```

GitHub Actions runs the same command on macOS. Passing package tests proves the Swift command and parsing paths compile and behave against deterministic fixtures; it does not replace an iPhone and EOS R6 Mark III validation record.

To generate and open the app project on macOS:

```bash
brew install xcodegen
cd ios/OpenEOSControl
xcodegen generate
open OpenEOSControl.xcodeproj
```

The macOS CI job builds the app for iOS Simulator, verifies the compiled asset catalog, English/Traditional Chinese resources, launch screen, local-network metadata, and supported orientations, then runs the app unit tests and eight UI workflows on an iPhone Simulator. The retained screenshots cover portrait control, landscape control, landscape Debug, offline media download, confirmation-gated media deletion, offline monitoring settings, Traditional Chinese connection, and Desktop Bridge connection states. One required network workflow uses the Simulator preset and drives the production SwiftUI, `CameraAppState`, `OpenEOSCore`, and HTTP path through decoded Live View, ISO, capture, focus, Bulb, recording, media preview/delete, and disconnect. A second workflow selects the HTTP preset before replacing its URL with loopback, so `CCAPIClient` must use Canon discovery, versioned JPEG endpoints and the advertised Canon 1.1 event GET/DELETE lifecycle instead of Simulator routes. It performs ISO and still capture outside the app, requires the exposure strip and open media view to refresh without user action, and verifies polling/Live View cleanup on disconnect. Event snapshot application waits for active operations and retries across operation revisions to avoid stale overwrites. Every command and event is checked against backend state rather than inferred from UI changes. The fake-camera log and XCResult bundle are retained on failure. Physical iPhone and R6 Mark III validation is still required.
