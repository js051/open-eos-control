# Architecture

Open EOS Control is Android-first.

## Runtime

```text
Android app
  -> CameraControlBackend
     -> CCAPI network backend
     -> future USB PTP backend
     -> future desktop bridge backend
  -> Canon EOS camera
```

During development:

```text
Android app
  -> simulator FastAPI server
  -> fake camera state
```

The simulator is not part of the product runtime. It exists so Android UI and state flows can be tested before a camera is connected.

## Boundaries

- Android owns product UI, camera session state, connection handling, and monitor-style controls.
- `CameraViewModel` owns UI state, busy/error transitions, and user actions.
- `CameraRepository` owns camera session operations and hides transport-specific backends from UI code.
- `CameraControlBackend` is the transport boundary. Each backend exposes the same camera actions, status, settings, and live view contract.
- `CcapiCameraBackend` adapts the raw CCAPI client into the shared backend contract.
- `CcapiClient` owns raw HTTP request/response mapping for the current CCAPI-compatible endpoint set.
- Simulator owns fake CCAPI responses for local development and automated checks.
- No desktop backend is required for the current product path, but the architecture leaves room for one.

## Next Decisions

- Decide whether the first wired backend should be Android USB/PTP direct control or a desktop bridge to EDSDK/libgphoto2.
- Decide how to represent capabilities that only exist on some backends, such as direct capture download, bulb capture, or higher quality live view streams.
- Decide whether live view should stay per-backend or become a source switch that can show CCAPI JPEG polling, RTP, USB/PTP preview, UVC, or HDMI capture.
- Add reconnect, timeout, and camera-busy states before expanding monitor tools.
