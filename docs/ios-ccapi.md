# iOS CCAPI

`ios/OpenEOSCore` is the native Swift transport and command foundation for the Open EOS Control iOS app. It communicates directly with a Canon camera or the development simulator over HTTP/HTTPS and does not depend on the Android implementation.

## Implemented Core

- CCAPI discovery through `/ccapi`, versioned operation parsing, and `ver110`/`ver100` fallback
- Camera identity, battery, storage, exposure, white balance, and dynamic camera-advertised settings
- Still capture, timed half-press with guaranteed release, recording, and tap focus only when advertised
- JPEG Live View with a bounded parser, endpoint fallback, cache busting, and retry without `liveviewsize` after Canon returns HTTP 400 `Invalid parameter`
- Bounded, same-origin media traversal and file-backed downloads with Canon main-file query fallbacks
- Basic Authentication held by the client instance and redacted diagnostic output
- Simulator mode and injectable HTTP transport for deterministic tests

CCAPI RTP, focus drive without a camera-advertised operation, the SwiftUI product UI, and iOS USB/PTP are not presented as implemented features.

## Host App Requirements

The future iOS app target must include a user-facing `NSLocalNetworkUsageDescription` because it connects directly to devices on the local network. Its App Transport Security configuration should use `NSAllowsLocalNetworking` for camera-local resources instead of broadly disabling ATS. `URLSessionCameraHTTPTransport` uses an ephemeral configuration, normal certificate validation, bounded timeouts, no cookies or credential storage, and `waitsForConnectivity` so the request can resume after the camera Wi-Fi route becomes available.

Official Apple references:

- [Local network privacy usage description](https://developer.apple.com/documentation/bundleresources/information-property-list/nslocalnetworkusagedescription)
- [App Transport Security local networking](https://developer.apple.com/documentation/bundleresources/information-property-list/nsapptransportsecurity/nsallowslocalnetworking)
- [`URLSessionConfiguration.waitsForConnectivity`](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/waitsforconnectivity)

The app must not persist a CCAPI password or print an `Authorization` header. HTTP should only be used on the isolated camera network; HTTPS keeps normal platform trust validation.

## Test

On a macOS host with Swift 5.10 or newer:

```bash
cd ios/OpenEOSCore
swift test
```

GitHub Actions runs the same command on macOS. Passing package tests proves the Swift command and parsing paths compile and behave against deterministic fixtures; it does not replace an iPhone and EOS R6 Mark III validation record.
