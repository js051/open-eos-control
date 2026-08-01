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
- `CameraProfile` identifies camera-family behavior. Its wire values are canonical across Android, iOS, and the Desktop Bridge: family is `EOS_R`, `EOS_DSLR`, `EOS_M`, `POWERSHOT`, or `UNKNOWN`; priority is `PRIMARY`, `SUPPORTED`, or `RESEARCH`. `Canon EOS R6 Mark III` is the primary profile and the golden validation target.
- `CapabilityMatrix` tells the UI and tests what is supported now versus planned for a backend.
- `CameraCapabilityEvidence` records where discovery came from, protocol/engine versions, advertised commands, and writable settings so physical-camera reports can distinguish missing advertisements from parser defects.
- `LiveViewRequest` and `LiveViewCapabilities` describe FPS, source, and size without hard-coding CCAPI polling into the UI.
- `UsbPtpDiagnostics` describes Android USB host devices, Canon vendor IDs, PTP interfaces, permission state, and endpoints before a PTP session is opened.
- `PtpSession` owns USB PTP transaction IDs, command/data/response validation, standard dataset/property parsing and writes, and streaming object transfers. `AndroidUsbPtpTransport` is limited to Android USB permission, interface claiming, and bulk endpoint I/O.

## Platform Strategy

- Android keeps the first complete app UI and owns direct phone-to-camera workflows.
- iOS uses the native `OpenEOSCore` Swift package and `OpenEOSControl` SwiftUI app over CCAPI/Wi-Fi with the same command and capability vocabulary; iOS USB/PTP remains research until platform constraints are proven.
- PC uses a desktop bridge service with a built-in browser control UI. Windows x64 releases include a CI-launched PyInstaller executable containing the Python runtime, PyAV/FFmpeg, and static UI; wheel/source packages remain available for other hosts. The bridge exposes the shared open protocol while internally using native libgphoto2/gphoto2 for USB, a no-shell WSL 2 gphoto2 command prefix on Windows when native gphoto2 is absent, a native HTTP CCAPI engine for wireless control, or a future optional user-installed Canon EDSDK adapter.
- The bridge implementation is executable under `bridge/`: FastAPI owns auth/session/HTTP concerns, `GPhoto2Engine` maps only camera-advertised CLI abilities and configuration values, and `CcapiEngine` maps only camera-advertised HTTP operations and setting values into the shared contract.
- The same FastAPI process serves the PC UI at `/`; it calls only the public bridge contract, keeps authentication in page memory, and renders controls from the advertised capability/settings response.
- The PC viewfinder may select a browser-owned local UVC/HDMI `MediaStream` instead of a Bridge Live View frame. This changes only the preview producer: the Bridge session and all capability-gated camera commands remain intact. Local device identifiers stay in page memory, every track is closed on source/device/session/page teardown, and coordinate-dependent Canon controls remain disabled because `MediaStream` frames do not carry Canon Live View geometry.
- Android's `DesktopBridgeClient` maps that HTTP contract back into `CameraControlBackend`, including memory-only Bearer auth, bridge camera discovery/selection, binary Live View, and streaming media transfers.

## Backend Rules

- Backend-specific power should appear as capabilities, not UI assumptions.
- Camera model classification must normalize spacing and punctuation before matching shared aliases. Every backend emits the canonical `CameraProfile`; client-side inference is only a backward-compatible fallback for older peers that omit the profile.
- Unsupported operations must fail with explicit transport/feature errors.
- A CCAPI control is writable only when discovery advertises the exact endpoint and HTTP method; setting values must also come from the camera's current `ability` list. UI gating and backend enforcement must use the same rule so stale or direct calls cannot bypass it.
- CCAPI lens and temperature state is accepted only from advertised exact endpoints and documented response types. Temperature is refreshed immediately before still capture, recording start, and Live View start, then enforced in the backend as well as the UI. Stop recording, release Bulb, and stop Live View are intentionally never blocked by a newly reported restriction, because cleanup must remain possible.
- Object-valued CCAPI settings are adapted into stable leaf controls only when their schema is documented. `stillimagequality` exposes camera-advertised RAW/JPEG/HEIF leaves and `wbshift` exposes bounded B/A and M/G integer ranges. Writes preserve the other current leaves and send Canon's complete nested `value` object.
- Canon CCAPI optical zoom is a separate `ZOOM_CONTROL`, not Live View magnification or the USB power-zoom-speed setting. It requires same-version `GET` and `POST /shooting/control/zoom`, an exact integer current value, and a bounded `min`/`max`/`step` ability with at most 256 choices. The UI sends the selected integer only after slider release and hides the control when any gate fails.
- Canon CCAPI Movie Mode is a separate `MOVIE_MODE_CONTROL`, not recording. It requires exact same-version `GET` and `POST /shooting/control/moviemode`; only `status=on|off` becomes a two-value internal setting, and writes use `{"action":"on|off"}`. Android, iOS and PC prefer this setting for their Photo/Video context, read back the resulting capabilities, and retain the previous camera-confirmed mode when a write fails. `VIDEO_RECORDING` remains independently gated by `recbutton`.
- Canon CCAPI card selection is a separate `CARD_SELECTION_CONTROL`. The still-image and movie resources are paired independently by exact API-version path; each must advertise GET and PUT and return a unique list of at least two exact `none`/`card1`/`card2` values containing the current value. The generic setting UI places the two controls in Photo and Video respectively, while writes remain exact string PUTs followed by authoritative state refresh.
- Capability evidence is diagnostic output, not authorization. It never grants a feature independently of `CapabilityMatrix` and backend checks. Lists are de-duplicated, capped at 256 entries, limited to 512 characters per entry, and stripped of query strings and line breaks before crossing a platform boundary or entering a report. Shareable reports carry a schema number, UTC generation time and installed product version, summarize advertised/observed set differences, and redact camera serials in addition to credentials.
- Physical-result confirmation is session-scoped operator evidence, not a backend capability. Android, iOS and PC expose only the intersection of advertised and successfully observed features, keep confirmation state in memory, reject Simulator/Offline Preview sessions, and bind copied Markdown to the exact sanitized diagnostic with a platform-native SHA-256 implementation.
- JPEG Live View requires an advertised start operation, at least one advertised frame endpoint, and an advertised stop operation. A 2xx text/JSON media response is metadata or an error, never a thumbnail, display preview, or downloadable camera file. Thumbnail and display-preview capabilities remain separate because USB engines may support only the former. Media deletion requires an advertised backend operation, an exact opaque media ID, explicit user confirmation, and a successful backend response before local state changes.
- Android, iOS, and PC CCAPI RTP require advertised `GET /shooting/liveview/rtpsessiondesc` and `POST /shooting/liveview/rtp`, a routed local IPv4 destination, an SDP H.264/90 kHz video description, a bound UDP receiver, and a working decoder. Mobile uses Wi-Fi-bound native render sessions; PC uses PyAV/FFmpeg to decode every H.264 access unit and FPS-cap JPEG output through the stable Bridge frame endpoint. All three platforms independently bind an advertised in-band `MP4A-LATM/48000` audio stream and perform bounded RFC 6416 reassembly. Android wraps each `audioMuxElement` in LOAS for Media3 1.8.1's AOSP-derived parser, sends raw AAC plus AudioSpecificConfig to `MediaCodec`, and streams PCM through `AudioTrack`; iOS extracts the bounded AAC-LC mono/stereo subset in Swift, decodes through `AVAudioConverter`, and schedules PCM through `AVAudioEngine`; PC decodes through PyAV/FFmpeg and exposes bounded 48 kHz stereo s16le PCM through authenticated long polling. Every audio path is default-muted, foreground/session scoped, backpressure bounded, independently diagnosed, and isolated from ready video. Explicit `cpresent=0` remains unsupported instead of guessing an out-of-band configuration. `AUTO` may prefer RTP only after all video gates pass and falls back to the complete JPEG lifecycle if RTP startup fails. The UI FPS value is a display/output cap; the Canon start contract does not contain an encoder frame-rate parameter.
- Canon EDSDK must not be committed or redistributed in this open-source repo; keep it as an optional local adapter.
- Camera Live View sources are interchangeable at the contract level: CCAPI JPEG polling, CCAPI RTP, USB/PTP preview, and bridge streams. PC local UVC/HDMI is a UI-owned supplemental preview source rather than a `CameraControlBackend` capability.
- Public references and validity status live in [reference-projects.md](reference-projects.md). Features should remain `planned` or `unsupported` until the repo has an executable path and test or real-device evidence.

## Near-Term Milestones

1. Keep CCAPI stable for R6 Mark III.
2. Validate the implemented Android USB/PTP session, DeviceInfo, storage, media, conditional standard capture and property paths on R6 Mark III.
3. Validate the capability-gated Canon EOS remote release, half-press, ISO/Tv/Av/WB, focus drive and JPEG Live View paths on R6 Mark III.
4. Record remaining real vendor properties/events and add only setting, movie or Touch AF mappings supported by reliable evidence.
5. Validate the tested PC CCAPI JPEG/RTP, persistent gphoto2 MJPEG desktop bridge, control-restart behavior, and PC UI paths on R6 Mark III, then consider a native libgphoto2 engine and optional EDSDK adapter from measured results.
6. Validate the simulator-tested iOS CCAPI app on a physical iPhone and R6 Mark III.
