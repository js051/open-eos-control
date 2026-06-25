# Architecture

Open EOS Control is Android-first.

## Runtime

```text
Android app
  -> Canon CCAPI over Wi-Fi
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
- `CameraRepository` owns camera session operations and hides the CCAPI client from UI code.
- `CcapiClient` owns raw HTTP request/response mapping for the current CCAPI-compatible endpoint set.
- Simulator owns fake CCAPI responses for local development and automated checks.
- No desktop backend is required in the product path.

## Next Decisions

- Confirm Canon R6 Mark III CCAPI endpoint paths against Canon documentation and a real body.
- Decide whether live view is CCAPI MJPEG, Android local UVC capture, HDMI capture, or a source switch.
- Add reconnect, timeout, and camera-busy states before expanding monitor tools.
