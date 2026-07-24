# Architecture

Open EOS Control is a multi-platform camera-control project for Android, iOS, and PC. The first real-camera target is Canon EOS R6 Mark III, but model-specific behavior must be expressed as profiles and capabilities so the same structure can grow across EOS bodies.

## Runtime Shape

```text
Android / iOS / PC app
  -> CameraRepository / platform session state
  -> CameraControlBackend
     -> CCAPI network backend
     -> Android USB/PTP backend
     -> desktop bridge backend
  -> Canon EOS camera
```

During development:

```text
Android app
  -> simulator FastAPI server
  -> fake camera state
```

The simulator is not part of the product runtime. It exists so UI, state flows, and backend contracts can be tested before a camera is connected.

## Core Contract

- `CameraControlBackend` is the transport boundary. Each backend exposes the same identity, status, settings, recording, focus, media, and live view surface.
- `CameraConnection` identifies how the app reaches a camera: CCAPI network, Android USB/PTP, or desktop bridge.
- `CameraProfile` identifies camera-family behavior. `Canon EOS R6 Mark III` is the primary profile and the golden validation target.
- `CapabilityMatrix` tells the UI and tests what is supported now versus planned for a backend.
- `CameraCapabilityEvidence` records where discovery came from, protocol/engine versions, advertised commands, and writable settings so physical-camera reports can distinguish missing advertisements from parser defects.
- `LiveViewRequest` and `LiveViewCapabilities` describe FPS, source, and size without hard-coding CCAPI polling into the UI.
- `UsbPtpDiagnostics` describes Android USB host devices, Canon vendor IDs, PTP interfaces, permission state, and endpoints before a PTP session is opened.
- `PtpSession` owns USB PTP transaction IDs, command/data/response validation, standard dataset/property parsing and writes, and streaming object transfers. `AndroidUsbPtpTransport` is limited to Android USB permission, interface claiming, and bulk endpoint I/O.

## Platform Strategy

- Android keeps the first complete app UI and owns direct phone-to-camera workflows.
- iOS uses the native `OpenEOSCore` Swift package and `OpenEOSControl` SwiftUI app over CCAPI/Wi-Fi with the same command and capability vocabulary; iOS USB/PTP remains research until platform constraints are proven.
- PC uses a desktop bridge service with a built-in browser control UI. The bridge exposes the shared open protocol while internally using libgphoto2 for USB, a native HTTP CCAPI engine for wireless control, or a future optional user-installed Canon EDSDK adapter.
- The bridge implementation is executable under `bridge/`: FastAPI owns auth/session/HTTP concerns, `GPhoto2Engine` maps only camera-advertised CLI abilities and configuration values, and `CcapiEngine` maps only camera-advertised HTTP operations and setting values into the shared contract.
- The same FastAPI process serves the PC UI at `/`; it calls only the public bridge contract, keeps authentication in page memory, and renders controls from the advertised capability/settings response.
- Android's `DesktopBridgeClient` maps that HTTP contract back into `CameraControlBackend`, including memory-only Bearer auth, bridge camera discovery/selection, binary Live View, and streaming media transfers.

## Backend Rules

- Backend-specific power should appear as capabilities, not UI assumptions.
- Unsupported operations must fail with explicit transport/feature errors.
- A CCAPI control is writable only when discovery advertises the exact endpoint and HTTP method; setting values must also come from the camera's current `ability` list. UI gating and backend enforcement must use the same rule so stale or direct calls cannot bypass it.
- Capability evidence is diagnostic output, not authorization. It never grants a feature independently of `CapabilityMatrix` and backend checks. Lists are de-duplicated, capped at 256 entries, limited to 512 characters per entry, and stripped of query strings and line breaks before crossing a platform boundary or entering a report.
- JPEG Live View requires an advertised start operation, at least one advertised frame endpoint, and an advertised stop operation. A 2xx text/JSON media response is metadata or an error, never a downloadable camera file. Media deletion requires an advertised backend operation, an exact opaque media ID, explicit user confirmation, and a successful backend response before local state changes.
- Android and iOS CCAPI RTP require advertised `GET /shooting/liveview/rtpsessiondesc` and `POST /shooting/liveview/rtp`, a camera-Wi-Fi IPv4 destination, an SDP H.264/90 kHz video description, a Wi-Fi-bound UDP listener, and a live native decoder/render session. `AUTO` may prefer RTP only after all gates pass and falls back to the complete JPEG lifecycle if RTP startup fails. The UI FPS value is a render cap; the Canon start contract does not contain an encoder frame-rate parameter.
- Canon EDSDK must not be committed or redistributed in this open-source repo; keep it as an optional local adapter.
- Live view sources are interchangeable at the contract level: CCAPI JPEG polling, CCAPI RTP, USB/PTP preview, and bridge streams.
- Public references and validity status live in [reference-projects.md](reference-projects.md). Features should remain `planned` or `unsupported` until the repo has an executable path and test or real-device evidence.

## Near-Term Milestones

1. Keep CCAPI stable for R6 Mark III.
2. Validate the implemented Android USB/PTP session, DeviceInfo, storage, media, conditional standard capture and property paths on R6 Mark III.
3. Validate the capability-gated Canon EOS remote release, half-press, ISO/Tv/Av/WB, focus drive and JPEG Live View paths on R6 Mark III.
4. Record remaining real vendor properties/events and add only setting, movie or Touch AF mappings supported by reliable evidence.
5. Validate the tested PC CCAPI, Android-to-libgphoto2 desktop bridge, and PC UI paths on R6 Mark III, then pursue persistent native streaming and an optional EDSDK adapter.
6. Validate the simulator-tested iOS CCAPI app on a physical iPhone and R6 Mark III.
