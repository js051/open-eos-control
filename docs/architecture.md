# Architecture

Open EOS Control is an Android-first project with a multi-platform camera core. The first real-camera target is Canon EOS R6 Mark III, but model-specific behavior must be expressed as profiles and capabilities so the same structure can grow across EOS bodies.

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
- `LiveViewRequest` and `LiveViewCapabilities` describe FPS, source, and size without hard-coding CCAPI polling into the UI.
- `UsbPtpDiagnostics` describes Android USB host devices, Canon vendor IDs, PTP interfaces, permission state, and endpoints before a PTP session is opened.

## Platform Strategy

- Android keeps the first complete app UI and owns direct phone-to-camera workflows.
- iOS should start with CCAPI/Wi-Fi using the same command and capability vocabulary; iOS USB/PTP remains research until platform constraints are proven.
- PC should start as a desktop bridge service. The bridge exposes the shared open protocol while internally using libgphoto2 or an optional user-installed Canon EDSDK adapter.

## Backend Rules

- Backend-specific power should appear as capabilities, not UI assumptions.
- Unsupported operations must fail with explicit transport/feature errors.
- Canon EDSDK must not be committed or redistributed in this open-source repo; keep it as an optional local adapter.
- Live view sources are interchangeable at the contract level: CCAPI JPEG polling, CCAPI RTP, USB/PTP preview, and bridge streams.

## Near-Term Milestones

1. Keep CCAPI stable for R6 Mark III.
2. Open an Android USB/PTP session after the existing USB diagnostics finds a Canon PTP device with permission.
3. Add PTP device info and property list.
4. Add Android USB/PTP still capture and setting writes.
5. Add USB/PTP live view if R6 Mark III exposes compatible Canon vendor operations.
6. Add desktop bridge contract tests, then libgphoto2 and optional EDSDK adapters.
