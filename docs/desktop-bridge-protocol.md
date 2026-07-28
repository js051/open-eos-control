# Desktop Bridge Protocol

The desktop bridge is a local service that exposes the same camera-control concepts as `CameraControlBackend`. It lets mobile or desktop UI code use a mature PC-side camera engine without linking proprietary or platform-specific camera SDKs into the app.

## Implementation Status

`bridge/open_eos_bridge` is the first executable implementation. It uses FastAPI and provides two open engines: `libgphoto2` for USB cameras and a native Python HTTP `ccapi` engine for direct wireless camera control. The gPhoto adapter invokes argument arrays, never a shell. Android and iOS implement the bridge protocol behind their shared camera sessions, including discovery, camera selection, memory-only Bearer auth, capability parsing, JPEG frames, bounded media thumbnails, and streamed media. Deterministic tests cover both PC engines and the mobile contracts. Physical R6 Mark III validation is still required and must not be inferred from those tests.

## Goals

- Keep the bridge protocol open and testable.
- Keep Canon EDSDK optional and user-installed; do not redistribute it in this repo.
- Support libgphoto2 as the default open-source USB engine.
- Support direct PC-to-camera CCAPI without requiring a phone or `gphoto2`.
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
POST /v1/session/{id}/focus/auto
POST /v1/session/{id}/recording/start
POST /v1/session/{id}/recording/stop
POST /v1/session/{id}/focus/tap
POST /v1/session/{id}/whitebalance/click
POST /v1/session/{id}/focus/drive
GET  /v1/session/{id}/media
GET  /v1/session/{id}/media/{itemId}/thumbnail
GET  /v1/session/{id}/media/{itemId}
DELETE /v1/session/{id}/media/{itemId}
DELETE /v1/session/{id}
```

`/health` and the browser UI assets are public. All `/v1` routes require either a loopback client or `Authorization: Bearer <OPEN_EOS_BRIDGE_TOKEN>`. The executable refuses a non-loopback bind when no token is configured.

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
- `ccapi`
- `edsdk`

For direct wireless CCAPI, the session request is:

```json
{
  "engine": "ccapi",
  "ccapiUrl": "http://192.168.1.2:8080",
  "ccapiUsername": "optional-camera-user",
  "ccapiPassword": "optional-memory-only-password"
}
```

The URL must be an HTTP(S) origin without credentials, path, query, or fragment. The password is accepted only in the session body, kept in memory for the session, and never returned by the API or included in diagnostics. Camera-provided media URLs are restricted to the active camera origin.

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
    "maxFps": 30
  },
  "settings": [
    {
      "key": "iso",
      "label": "ISO",
      "value": "800",
      "values": ["100", "200", "400", "800", "1600"]
    }
  ],
  "evidence": {
    "source": "GET /ccapi",
    "protocolVersions": ["ver100"],
    "advertisedCommands": ["POST /ccapi/ver100/shooting/control/shutterbutton"],
    "writableSettings": ["iso"],
    "truncated": false
  }
}
```

`evidence` is immutable diagnostic context for the active engine. CCAPI reports method/path pairs from discovery; libgphoto2 reports abilities and writable configuration paths. It does not enable a capability by itself. Producers de-duplicate and sort evidence, remove URL queries and line breaks, limit each list to 256 items and each item to 512 characters, and set `truncated` when data was omitted. The browser diagnostic report includes this object plus schema/version/time metadata and the advertised/observed set difference. A separately tested recursive sanitizer removes credentials, authorization values and camera serials before display or clipboard copy.

## Live View

`POST /liveview/start` accepts the same request shape as the app core:

```json
{
  "fps": 15,
  "size": "MEDIUM",
  "source": "AUTO"
}
```

The response includes the effective `source`, so clients can distinguish an `AUTO` request that selected `CCAPI_RTP` from one that fell back to `CCAPI_JPEG_POLLING`.

The libgphoto2 CLI adapter starts one cancellable `gphoto2 --capture-movie --stdout` process and incrementally extracts bounded JPEG frames from its concatenated MJPEG output. It accepts a 1-30 FPS output cap but does not claim the camera and USB link delivered that rate. Commands that need exclusive camera access stop the movie process first; the next frame request automatically starts a fresh process. Startup, early termination, malformed-frame, size-limit, or frame-timeout failures switch the session to bounded `--capture-preview --stdout` transactions and reduce effective `requestedFps` to at most 5. `CameraStatus.raw.liveViewTransport` and `liveViewFallbackReason` distinguish these paths.

`GET /v1/session/{id}/media/{itemId}/thumbnail` returns a bounded JPEG or PNG with `Cache-Control: private, no-store`. The libgphoto2 engine advertises `MEDIA_THUMBNAIL` only when `gphoto2 --abilities` reports file-preview support and then executes the documented `--folder ... --get-thumbnail ... --stdout` command. The direct CCAPI engine does not advertise this capability because no verified camera-advertised thumbnail resource is available; clients keep their file-type fallback.

The CCAPI engine advertises `CCAPI_JPEG_POLLING` from 1 through 30 FPS and defaults the PC UI to 15 FPS. It starts Live View with `cameradisplay` and the selected size, retries once without `liveviewsize` only when the camera returns HTTP 400, and then reads the first complete bounded JPEG from the advertised `flip`, `flipdetail`, or Live View endpoint. When coordinate Tap AF or Click White Balance is advertised, `flipdetail?kind=both` is preferred so the same bounded response supplies the JPEG and Canon image-position metadata. Requested FPS controls client polling; observed FPS remains a separate UI metric.

The CCAPI engine also advertises `CCAPI_RTP` only when discovery contains `GET /shooting/liveview/rtpsessiondesc` and `POST /shooting/liveview/rtp`, the camera route resolves to a usable local IPv4 address, and the installed PyAV runtime can create an H.264 decoder. It validates the SDP H.264/90 kHz stream, binds the advertised UDP port before posting `{"action":"start","ipaddress":"..."}`, parses RFC 3550 and RFC 6184 single NAL/STAP-A/FU-A packets, waits for SPS/PPS plus a keyframe, decodes every access unit, and converts only FPS-eligible decoded frames to JPEG. Start is not reported successful until the first frame is actually decoded; a timeout closes the receiver, sends Canon's stop body, and lets AUTO fall back to JPEG. Stop, failed HTTP start, session close, and AUTO fallback all clean up both sides. `GET /liveview/frame` remains `image/jpeg`, so existing Bridge clients need no video-container decoder. AAC LATM audio in the SDP is not implemented.

## CCAPI Mapping

The network engine discovers versions and HTTP methods from `GET /ccapi`; a fallback identity probe establishes connectivity but does not invent unsupported command capabilities. It maps only advertised operations:

- identity, battery, storage, and merged versioned shooting settings
- camera-advertised setting values and their discovered `PUT` paths
- direct shutter or manual full press with guaranteed release
- timed half-press with guaranteed release
- independent autofocus through advertised `POST /shooting/control/af` start/stop, falling back to the advertised balanced half-press operation
- movie start/stop through `recbutton`
- normalized UI Tap AF mapped through detailed Live View `image` geometry to integer `positionx`/`positiony`, then sent only through advertised `PUT /shooting/liveview/afframeposition`
- normalized UI Click White Balance mapped through the same geometry, then sent only through advertised `POST /shooting/liveview/clickwb`
- bounded JPEG Live View lifecycle and frame extraction
- advertised RTP H.264 lifecycle, UDP reception, RFC 3550/RFC 6184 depacketization and PyAV decode-to-JPEG output
- bounded/paged storage traversal plus opaque same-origin media IDs, streamed downloads, and deletion only when the camera advertises a matching `DELETE` operation

Basic Auth is sent preemptively when a username is supplied. The Authorization value, username, and password are never exposed in status, diagnostics, media URLs, or API responses. Focus drive is available only when the camera advertises the verified `drivefocus` POST operation. RTP is hidden when any camera-endpoint, route, or decoder gate is absent; no unusable source is advertised.

## libgphoto2 Mapping

The adapter derives capabilities from `--abilities` and `--list-all-config` instead of assuming every EOS body supports every command:

- discovery: `--auto-detect`
- identity and status: `--summary`, `--list-all-config`, `--storage-info`; remaining shots prefer the read-only `/main/status/availableshots` value and fall back to a valid storage summary count
- settings: camera-advertised values through `--set-config-value`; the R6 Mark III mapping includes WB A/B shifts, SD and CF/CFexpress image quality, aspect ratio, power-zoom speed, and Auto Power Off in addition to the existing exposure, AF, drive, metering, Picture Style, color, noise-reduction, AEB and movie controls
- still capture: select an advertised writable `Memory card` capture target first, then run `--trigger-capture`, falling back to `--capture-image` only when advertised
- half-press: advertised `eosremoterelease` press/release values with guaranteed release
- independent autofocus: paired writable `autofocusdrive=1` and guaranteed `autofocuscancel=1` actions when both exist, falling back to the balanced half-press path
- recording: advertised `movierecordtarget` Card/None values
- focus drive: advertised `manualfocusdrive` Near/Far values while Live View is active
- Live View: advertised `viewfinder` lifecycle plus cancellable `--capture-movie --stdout` MJPEG, command-safe restart and bounded `--capture-preview --stdout` fallback, with cleanup on stop, failed start, and session close
- media: recursive `--list-files`, streamed `--get-file ... --stdout`, and exact `--folder ... --delete-file ...` only when `--abilities` reports file deletion

Settings are exposed only when the runtime config is writable and has safe selectable values. The undocumented R6 Mark III Auto Power Off `0xFFFFFFFF` sentinel is rejected even if posted directly to the API, and one-choice advanced controls stay out of the product UI. `Capture Target` is intentionally not mapped as a user setting: selecting libgphoto2 host RAM without completing its object-transfer/cleanup lifecycle would create a misleading or lossy capture path. The bridge instead forces a writable camera-advertised `Memory card` target before shutter, and rejects a known host-RAM target when no card choice is available. Coordinate tap focus and Click White Balance remain unavailable because the public CLI surface does not provide verified normalized image-coordinate commands for this camera. Unsupported controls return an error and are never reported as accepted.

## Run Locally

```powershell
cd bridge
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\open-eos-bridge.exe
```

The defaults are `127.0.0.1:18181` and loopback-only access. The UI offers `libgphoto2` USB and direct `ccapi` connections; set `OPEN_EOS_GPHOTO2` when the native executable is not named `gphoto2`. On Windows, native gphoto2 wins; otherwise the runner uses `wsl.exe --exec gphoto2`, optionally adding `--distribution <OPEN_EOS_GPHOTO2_WSL_DISTRO>`. The command remains an argument array and never passes through a shell. Health preflights the WSL distribution before launching gphoto2, preserves UTF-16 Windows diagnostics, and reports missing distro, missing WSL package, or missing usbipd separately. `scripts/windows-gphoto2-doctor.ps1` performs the same read-only host audit and can emit JSON. For a LAN bind, set both `OPEN_EOS_BRIDGE_HOST` and a strong `OPEN_EOS_BRIDGE_TOKEN`.

The same process serves the responsive PC control UI at `http://127.0.0.1:18181/`. It scans USB cameras or accepts a manual CCAPI origin, opens one selected session, and renders only camera-advertised controls for Live View, exposure, capture, recording, focus, media, and diagnostics. Native Android and iOS clients also implement this `/v1` contract for LAN access to a PC-attached USB camera; LAN binding requires a Bearer token. Destructive media deletion has a filename-specific confirmation and removes a row only after the bridge returns success. English and Traditional Chinese are selectable. Bridge tokens and camera passwords stay in memory; only non-secret connection preferences may be persisted.

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
