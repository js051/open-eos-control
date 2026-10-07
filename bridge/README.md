# Open EOS Control Desktop Bridge

This package provides the PC camera backend and browser control surface for Open EOS Control 0.1.3.

It can connect to Canon cameras through native HTTP CCAPI or through a system-installed `gphoto2`/libgphoto2 USB stack. It also exposes a fail-closed provider boundary for a separately installed licensed EDSDK integration; no working EDSDK provider or Canon material is included. Camera commands and settings are exposed only when the selected backend advertises support.

Direct CCAPI sessions expose confirmation-gated sensor cleaning only when the camera advertises Canon's `POST /functions/sensorcleaning` endpoint. The Bridge requires the documented HTTP 200 response and supports an explicit clean-and-power-off choice; libgphoto2 USB sessions report this feature as unsupported until a verified public command contract exists.

USB camera events are exposed only after a bounded `gphoto2 --wait-event` probe succeeds. Body-property and file events refresh PC, Android, and iOS Bridge clients. Event waits pause while the exclusive persistent USB Live View process or a Bulb exposure is active, so synchronization never interrupts capture merely to simulate notifications.

Dual-card cameras expose a Photo-only recording-card selector only when gphoto2 reports at least two live writable `/store_<id>` devices and a writable `storageid` widget whose current value matches one of them. The Bridge re-runs both probes before every exact ID write; card IDs are never hardcoded, and a failed or stale refresh sends no command.

The built-in browser control surface includes decoded-frame monitoring assists for CCAPI JPEG, decoded RTP, libgphoto2 USB preview, and browser-owned local UVC/HDMI input: memory-only imported 3D `.cube` LUT preview through WebGL2, a mutually exclusive bounded luminance histogram or 64x64 luma waveform, configurable zebra, false color, focus peaking, frame guides, action/title safe areas, and anamorphic desqueeze. LUT import accepts only the bounded 2-64 3D subset and rejects 1D/shaper or oversized files; filenames, titles and table data stay out of diagnostics. Local video replaces only the viewfinder while the active Bridge session keeps camera control; device identifiers remain in page memory and all media tracks are stopped during source, device, session, or page teardown. Coordinate Canon controls stay unavailable because local video does not carry Canon Live View geometry. These overlays are local display tools and never imply that the camera accepted a control command.

When Canon advertises an RFC 6416 `MP4A-LATM/48000` stream beside RTP video, the PC Bridge binds its separate UDP port, reassembles complete audioMuxElements, decodes in-band LATM through PyAV/FFmpeg, and serves bounded 48 kHz stereo s16le PCM to the authenticated browser session. Camera audio is muted by default to satisfy browser autoplay policy and starts only from the visible speaker control. Audio bind or decode failure is diagnostic and does not stop usable H.264 video; source changes, Live View stop, disconnect and page teardown cancel polling and scheduled playback.

Camera media downloads are consumed as cancellable Fetch streams with visible byte progress. Files below 64 MiB use a cross-browser Blob download after the streamed transfer completes. Unknown-size and larger files prefer the browser File System Access writer, whose temporary file is committed only after the camera stream completes; unsupported browsers retain the Blob fallback. Disconnecting, closing the page, or pressing cancel aborts the request and does not report a successful download.

Direct CCAPI media details expose Canon file protection, archive state, rating from 0 through 5, and display rotation at 0, 90, 180, or 270 degrees only when discovery advertises contents `PUT`. The engine sends Canon's exact action/value body, then requires `kind=info` readback to match before reporting success or observed evidence. The browser reads metadata only when its single media-actions dialog opens. libgphoto2 sessions keep these controls unavailable because no separately verified mutation contract is claimed.

## Shutter autofocus choice

The browser shows a per-session Shutter AF switch next to the shutter when the CCAPI camera advertises ordinary shutter POST or manual shutter POST/PUT. It defaults to on. Changing the switch sends no camera command. An ordinary photograph sends the selected JSON boolean once; a manual release always sends `af:false`. The choice does not change lens AF/MF or disable continuous/Servo AF, and it does not apply to Bulb, video, half-press, or AF-ON.

`GET /v1/session/{id}/capabilities` adds the optional `shutterAutofocusSupported` boolean. `POST /v1/session/{id}/capture/still` accepts an optional `{ "af": false }` or `{ "af": true }` body; an empty body or `{}` retains the old AF-on default. An `af` value must be a JSON boolean, and unknown fields are rejected. Only the capable CCAPI engine advertises selection. Other engines reject AF-off before calling the camera. Clients must reject AF-off locally when the capability is absent or invalid because older servers ignore the request body.

If capability is withdrawn during a session, a selected AF-off choice remains visible and capture is blocked until the user explicitly enables AF or support returns. Reconnecting resets to AF-on. Camera errors never trigger an AF-on fallback or an automatic repeated shutter.

The new reachable paths are iOS Direct CCAPI and the PC browser through Bridge CCAPI. Android Direct retains its existing choice. Mobile Bridge clients understand the optional capability and fail closed, but their current connection screens only configure local USB engines; this change does not add mobile Bridge CCAPI connection setup.

## CCAPI Bulb release recovery

If a Bulb start or stop cannot confirm shutter release, the Bridge preserves the exact release operation in that session. Camera mutations are blocked until an explicit Stop succeeds; status and capability reads, recording/Live View/event stops, and disconnect remain available. The PC control page shows a persistent **Retry Stop Bulb** action even if the camera mode changes or a status refresh fails. Recovery confirms release without claiming that an unacknowledged exposure completed.

`CameraStatus.shutterReleaseUnconfirmed` is an additive boolean, defaulting to `false`. When `true`, `bulbExposureActive` is `null`, because actual exposure state is unknown. Failed release and blocked mutations use the existing error envelope with code `SHUTTER_RELEASE_UNCONFIRMED`; clients must use the same session's `/bulb/stop`, never repeat `/bulb/start`. Existing Android and iOS Bridge adapters tolerate the additive field but do not yet expose this recovery state; their UI recovery is a separate follow-up. Server-side mutation blocking also applies to those clients.

Disconnect makes one final release attempt and closes the session. If that attempt fails, DELETE returns an error explaining that release is unconfirmed and the user must check and stop the exposure on the camera before reconnecting. The removed session cannot be retried, and its release operation is never sent through a new session. A separate previous-connection warning remains visible across reconnection until the user confirms they have checked the camera; it never marks the new session as exposing. Closing the browser is best-effort cleanup, so check the camera if an exposure or warning was still present.

This workflow is covered by an independent synthetic HTTP peer and automated tests; physical-camera validation remains pending.

## Install

Windows x64 release assets include a single-file executable:

```powershell
.\open-eos-control-bridge-windows-x64-X.Y.Z.exe
```

It bundles the Python runtime, PyAV/FFmpeg, and the browser UI. The loopback control page opens only after the service is ready. Keep the console window open while using the Bridge; `--no-browser` suppresses automatic browser launch. The executable is a development-preview artifact and is not code-signed.

The wheel is the cross-platform installation path:

```bash
python -m pip install open_eos_control_bridge-0.1.3-py3-none-any.whl
```

USB control requires `gphoto2` to be available on `PATH`. CCAPI control does not require `gphoto2`. The optional EDSDK engine remains unavailable unless a compatible local provider is installed; see [Optional Canon EDSDK Provider](../docs/edsdk-provider.md).

## Run

```bash
open-eos-bridge
```

The service listens on `127.0.0.1:18181` by default. LAN binding requires `OPEN_EOS_BRIDGE_TOKEN`; the standalone executable intentionally accepts the token only through the environment and never through a process-list-visible command argument. See the main [Open EOS Control repository](https://github.com/js051/open-eos-control) for platform clients, source code, documentation, and issue tracking.

This is an unofficial Canon EOS project and is licensed under Apache-2.0.
