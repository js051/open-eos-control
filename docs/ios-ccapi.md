# iOS Camera Transports

`ios/OpenEOSCore` is the native Swift transport and command foundation, and `ios/OpenEOSControl` is the iOS 17 SwiftUI app. They can communicate directly with a Canon camera/simulator over CCAPI HTTP(S), or with Open EOS Control Desktop Bridge over an authenticated LAN connection. They do not depend on the Android implementation.

## Implemented Core

- CCAPI discovery through `/ccapi`, including Canon's same-origin full `url` entries and relative `path` fixtures, versioned operation parsing, and `ver110`/`ver100` fallback; query data and unsafe origins/paths are never promoted to capabilities
- Camera identity, battery, storage, exposure, white balance, and dynamic settings; writable controls require the exact setting-specific `PUT` operation and a value from camera `ability`
- Still capture, timed half-press with guaranteed release, recording, and tap focus only when advertised
- JPEG Live View only when discovery advertises a complete start/frame/stop lifecycle, with a bounded parser, endpoint fallback, cache busting, and retry without `liveviewsize` after Canon returns HTTP 400 `Invalid parameter`
- Bounded, same-origin media traversal and file-backed downloads with Canon main-file query fallbacks; text/JSON metadata is rejected even when returned with HTTP 2xx
- Same-origin exact-path media deletion only when discovery advertises `DELETE` for `/contents` or a child operation
- Basic Authentication held by the client instance and redacted diagnostic output
- Simulator mode and injectable HTTP transport for deterministic tests
- Desktop Bridge service validation, Bearer authentication, USB camera discovery/selection, session lifecycle, dynamic capability mapping, settings, capture, half-press, recording, tap/drive focus when advertised, bounded JPEG frames and media thumbnails, file-backed media download/deletion, structured errors, and secret-redacted diagnostics

CCAPI RTP, focus drive without a camera/engine-advertised operation, and direct iOS USB/PTP are not presented as implemented features. Desktop Bridge is the implemented iPhone/iPad route to a camera attached to a PC by USB.

## Implemented App

- Direct HTTP/HTTPS/Simulator presets or Desktop Bridge URL/token with USB scan and camera selection; passwords/tokens stay in memory while non-secret URLs and usernames may be remembered
- Full-screen Photo/Video control with camera-capability gating, exposure sheets, still capture, half-press, recording, tap focus or manual focus drive when advertised, and adjustable JPEG Live View
- Live View FPS from 1-30, clamped to the camera-advertised range, plus size, automatic refresh, grid, rolling FPS, frame bytes, and source diagnostics
- Media listing, file-backed download/share, capability-gated confirmation deletion, redacted diagnostic report, and no fake USB/PTP action
- English, Traditional Chinese, and system language selection
- Portrait and landscape layouts that respect system safe areas; whole-window upside-down rotation is disabled while key control content can rotate
- App icon and localization resources verified in the built bundle

## Host App Requirements

The iOS app includes a user-facing `NSLocalNetworkUsageDescription` because it connects directly to devices on the local network. Its App Transport Security configuration uses `NSAllowsLocalNetworking` for camera-local resources instead of broadly disabling ATS. `URLSessionCameraHTTPTransport` uses an ephemeral configuration, normal certificate validation, bounded timeouts, no cookies or credential storage, and `waitsForConnectivity` so the request can resume after the camera Wi-Fi route becomes available.

Official Apple references:

- [Local network privacy usage description](https://developer.apple.com/documentation/bundleresources/information-property-list/nslocalnetworkusagedescription)
- [App Transport Security local networking](https://developer.apple.com/documentation/bundleresources/information-property-list/nsapptransportsecurity/nsallowslocalnetworking)
- [`URLSessionConfiguration.waitsForConnectivity`](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/waitsforconnectivity)

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

The macOS CI job builds the app for iOS Simulator, verifies the compiled asset catalog, English/Traditional Chinese resources, launch screen, local-network metadata, and supported orientations, then runs the app unit tests and four UI workflows on an iPhone Simulator. The retained screenshots cover portrait control, landscape control, landscape Debug, confirmation-gated media deletion, Traditional Chinese connection, and Desktop Bridge connection states. Physical iPhone and R6 Mark III validation is still required.
