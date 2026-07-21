# Desktop Bridge Protocol

The desktop bridge is a local service that exposes the same camera-control concepts as `CameraControlBackend`. It lets mobile or desktop UI code use a mature PC-side camera engine without linking proprietary or platform-specific camera SDKs into the app.

## Implementation Status

`bridge/open_eos_bridge` is the first executable implementation. It uses FastAPI and invokes `gphoto2` with argument arrays, never through a shell. Its HTTP and command mappings are covered by deterministic tests shaped from the public libgphoto2 EOS R6 Mark III configuration snapshot. Physical R6 Mark III validation is still required and must not be inferred from those tests.

## Goals

- Keep the bridge protocol open and testable.
- Keep Canon EDSDK optional and user-installed; do not redistribute it in this repo.
- Support libgphoto2 as the default open-source USB engine.
- Keep transport-specific details behind capabilities.

## HTTP Endpoints

All endpoints return JSON unless marked as a stream.

```text
GET  /health
GET  /v1/cameras
POST /v1/session
GET  /v1/session/{id}/info
GET  /v1/session/{id}/status
GET  /v1/session/{id}/capabilities
POST /v1/session/{id}/liveview/start
POST /v1/session/{id}/liveview/stop
GET  /v1/session/{id}/liveview/frame
POST /v1/session/{id}/settings/{key}
POST /v1/session/{id}/capture/still
POST /v1/session/{id}/shutter/half-press
POST /v1/session/{id}/recording/start
POST /v1/session/{id}/recording/stop
POST /v1/session/{id}/focus/tap
POST /v1/session/{id}/focus/drive
GET  /v1/session/{id}/media
GET  /v1/session/{id}/media/{itemId}
DELETE /v1/session/{id}
```

Only `/health` is public. All `/v1` routes require either a loopback client or `Authorization: Bearer <OPEN_EOS_BRIDGE_TOKEN>`. The executable refuses a non-loopback bind when no token is configured.

## Session Request

```json
{
  "engine": "auto",
  "cameraId": "optional-camera-id",
  "profileHint": "Canon EOS R6 Mark III"
}
```

Engines:

- `auto`
- `libgphoto2`
- `edsdk`

## Capability Response

The bridge should mirror the app-side capability model:

```json
{
  "profile": {
    "modelName": "Canon EOS R6 Mark III",
    "family": "EOS_R",
    "priority": "PRIMARY"
  },
  "supported": [
    "CAMERA_IDENTITY",
    "DESKTOP_BRIDGE",
    "LIVE_VIEW",
    "LIVE_VIEW_JPEG_POLLING",
    "STILL_CAPTURE"
  ],
  "planned": ["LIVE_VIEW_RTP", "TAP_FOCUS"],
  "liveView": {
    "sources": ["DESKTOP_BRIDGE_STREAM"],
    "defaultSource": "DESKTOP_BRIDGE_STREAM",
    "sizes": ["MEDIUM"],
    "minFps": 1,
    "maxFps": 5
  },
  "settings": [
    {
      "key": "iso",
      "label": "ISO",
      "value": "800",
      "values": ["100", "200", "400", "800", "1600"]
    }
  ]
}
```

## Live View

`POST /liveview/start` accepts the same request shape as the app core:

```json
{
  "fps": 15,
  "size": "MEDIUM",
  "source": "DESKTOP_BRIDGE_STREAM"
}
```

The current CLI adapter serves `GET /liveview/frame` as JPEG polling. Each frame is one bounded `gphoto2 --capture-preview --stdout` process, so it advertises at most 5 FPS. The client controls polling at or below `requestedFps`; the server does not claim the camera delivered that rate. A later native libgphoto2 adapter can keep a persistent stream while preserving the endpoint and capability vocabulary.

## libgphoto2 Mapping

The adapter derives capabilities from `--abilities` and `--list-all-config` instead of assuming every EOS body supports every command:

- discovery: `--auto-detect`
- identity and status: `--summary`, `--list-all-config`, `--storage-info`
- settings: camera-advertised values through `--set-config-value`
- still capture: `--trigger-capture`, falling back to `--capture-image` only when advertised
- half-press: advertised `eosremoterelease` press/release values with guaranteed release
- recording: advertised `movierecordtarget` Card/None values
- focus drive: advertised `manualfocusdrive` Near/Far values while Live View is active
- Live View: advertised `viewfinder` lifecycle plus `--capture-preview --stdout`, with cleanup on stop, failed start, and session close
- media: recursive `--list-files` and streamed `--get-file ... --stdout`

Coordinate tap focus remains unavailable because the public CLI surface does not provide a verified normalized image-coordinate mapping for this camera. Unsupported controls return an error and are never reported as accepted.

## Run Locally

```powershell
cd bridge
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\open-eos-bridge.exe
```

The defaults are `127.0.0.1:18181`, the `libgphoto2` engine, and loopback-only access. Set `OPEN_EOS_GPHOTO2` when the executable is not named `gphoto2`. For a LAN bind, set both `OPEN_EOS_BRIDGE_HOST` and a strong `OPEN_EOS_BRIDGE_TOKEN`.

## Error Shape

```json
{
  "error": {
    "code": "UNSUPPORTED_FEATURE",
    "message": "Focus drive is not supported by this camera/engine.",
    "feature": "FOCUS_DRIVE",
    "engine": "libgphoto2"
  }
}
```

Errors must name the feature and engine so the UI can disable controls and show actionable diagnostics.

Pydantic request validation also uses this envelope with code `INVALID_REQUEST`, so clients do not need a second parser for malformed input responses.
