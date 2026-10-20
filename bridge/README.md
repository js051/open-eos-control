# Open EOS Control Desktop Bridge

This package provides the PC camera backend and browser control surface for Open EOS Control 0.1.3.

It can connect to Canon cameras through native HTTP CCAPI or through a system-installed `gphoto2`/libgphoto2 USB stack. It also exposes a fail-closed provider boundary for a separately installed licensed EDSDK integration; no working EDSDK provider or Canon material is included. Camera commands and settings are exposed only when the selected backend advertises support.

Direct CCAPI sessions expose confirmation-gated sensor cleaning only when the camera advertises Canon's `POST /functions/sensorcleaning` endpoint. The Bridge requires the documented HTTP 200 response and supports an explicit clean-and-power-off choice; libgphoto2 USB sessions report this feature as unsupported until a verified public command contract exists.

USB camera events are exposed only after a bounded `gphoto2 --wait-event` probe succeeds. Body-property and file events refresh PC, Android, and iOS Bridge clients. Event waits pause while the exclusive persistent USB Live View process or a Bulb exposure is active, so synchronization never interrupts capture merely to simulate notifications.

Dual-card cameras expose a Photo-only recording-card selector only when gphoto2 reports at least two live writable `/store_<id>` devices and a writable `storageid` widget whose current value matches one of them. The Bridge re-runs both probes before every exact ID write; card IDs are never hardcoded, and a failed or stale refresh sends no command.

The built-in browser control surface includes decoded-frame monitoring assists for CCAPI JPEG, decoded RTP, libgphoto2 USB preview, and browser-owned local UVC/HDMI input: memory-only imported 3D `.cube` LUT preview through WebGL2, a mutually exclusive bounded luminance histogram or 64x64 luma waveform, configurable zebra, false color, focus peaking, frame guides, action/title safe areas, and anamorphic desqueeze. LUT import accepts only the bounded 2-64 3D subset and rejects 1D/shaper or oversized files; filenames, titles and table data stay out of diagnostics. Local video replaces only the viewfinder while the active Bridge session keeps camera control; device identifiers remain in page memory and all media tracks are stopped during source, device, session, or page teardown. Coordinate Canon controls stay unavailable because local video does not carry Canon Live View geometry. These overlays are local display tools and never imply that the camera accepted a control command.

When Canon advertises an RFC 6416 `MP4A-LATM/48000` stream beside RTP video, the PC Bridge binds its separate UDP port, reassembles complete audioMuxElements, decodes in-band LATM through PyAV/FFmpeg, and serves bounded 48 kHz stereo s16le PCM to the authenticated browser session. Camera audio is muted by default to satisfy browser autoplay policy and starts only from the visible speaker control. Audio bind or decode failure is diagnostic and does not stop usable H.264 video. Fresh PCM expires after one second, independently of receiver-thread/input activity. The speaker control reports the separate requested, decoded, scheduled, and supported output-clock progress stages; digital silence still counts as PCM. WebAudio output timestamps are software/device-clock evidence, never proof of physical audibility. Browsers without that API retain usable audio with output progress explicitly unverified.

Mute, background/pagehide, source change, Live View stop, disconnect, a suspended context, or a long event-loop pause clear pending playback and require an explicit new speaker gesture. A five-second deadline covers pending context resume, missing PCM, hanging GET/body reads, and stalled supported output-clock progress; it never stops video. Pending source nodes have a one-second PCM expiry and the schedule remains bounded to half a second. Cleanup runs at the next available JavaScript opportunity; it is not a hard-real-time or downstream-speaker guarantee. A replacement AudioContext is blocked until the old close promise fulfills and its state is closed. If cleanup fails or remains pending, wait or reload the page; no new audio context competes with an unconfirmed old owner.

Camera media downloads are consumed as cancellable Fetch streams with visible byte progress. Files below 64 MiB use a cross-browser Blob download after the streamed transfer completes. Unknown-size and larger files prefer the browser File System Access writer, whose temporary file is committed only after the camera stream completes; unsupported browsers retain the Blob fallback. Disconnecting, closing the page, or pressing cancel aborts the request and does not report a successful download.

Direct CCAPI media details expose Canon file protection, archive state, rating from 0 through 5, and display rotation at 0, 90, 180, or 270 degrees only when discovery advertises contents `PUT`. The engine sends Canon's exact action/value body, then requires `kind=info` readback to match before reporting success or observed evidence. The browser reads metadata only when its single media-actions dialog opens. libgphoto2 sessions keep these controls unavailable because no separately verified mutation contract is claimed.

## Shutter autofocus choice

The browser shows a per-session Shutter AF switch next to the shutter when the CCAPI camera advertises ordinary shutter POST or manual shutter POST/PUT. It defaults to on. Changing the switch sends no camera command. An ordinary photograph sends the selected JSON boolean once; a manual release always sends `af:false`. The choice does not change lens AF/MF or disable continuous/Servo AF, and it does not apply to Bulb, video, half-press, or AF-ON.

`GET /v1/session/{id}/capabilities` adds the optional `shutterAutofocusSupported` boolean. `POST /v1/session/{id}/capture/still` accepts an optional `{ "af": false }` or `{ "af": true }` body; an empty body or `{}` retains the old AF-on default. An `af` value must be a JSON boolean, and unknown fields are rejected. Only the capable CCAPI engine advertises selection. Other engines reject AF-off before calling the camera. Clients must reject AF-off locally when the capability is absent or invalid because older servers ignore the request body.

If capability is withdrawn during a session, a selected AF-off choice remains visible and capture is blocked until the user explicitly enables AF or support returns. Reconnecting resets to AF-on. Camera errors never trigger an AF-on fallback or an automatic repeated shutter.

The new reachable paths are iOS Direct CCAPI and the PC browser through Bridge CCAPI. Android Direct retains its existing choice. Mobile Bridge clients understand the optional capability and fail closed, but their current connection screens only configure local USB engines; this change does not add mobile Bridge CCAPI connection setup.

## CCAPI control stop recovery

If a Bulb, manual still, half-press, or autofocus operation cannot confirm its stop, the Bridge preserves the exact stop method, path, payload, and originating feature in that session. Camera mutations are blocked until an explicit Stop succeeds; status and capability reads, recording/Live View/event stops, and disconnect remain available. The PC control page shows a persistent **Retry camera stop** action even if the camera mode changes or a status refresh fails. Recovery sends only the saved stop and does not claim that an unacknowledged exposure completed or that a short control supports Bulb.

`CameraStatus.shutterReleaseUnconfirmed` is an additive boolean, defaulting to `false`. For an uncertain Bulb operation, `bulbExposureActive` is `null`; a short control does not claim a Bulb exposure. Failed stop and blocked mutations use the existing error envelope with code `SHUTTER_RELEASE_UNCONFIRMED`; clients must use the same session's `/bulb/stop` compatibility endpoint, never repeat a start, AF, or shutter command. Server-side mutation blocking applies to every client. Mobile compatibility does not add a Bridge-CCAPI connection entry point.

Disconnect makes one final stop attempt and closes the session. If that attempt fails, DELETE returns an error explaining that the stop is unconfirmed and the user must check and stop the active control on the camera before reconnecting. The removed session cannot be retried, and its stop operation is never sent through a new session. A separate previous-connection warning remains visible across reconnection until the user confirms they have checked the camera; it never marks the new session as exposing. Closing the browser is best-effort cleanup, so check the camera if an active control or warning was still present.

After an acknowledged shutter and any required release, a failed status read returns only the dedicated `CAPTURE_STATUS_READBACK_FAILED` code. The PC page retains that warning while allowing its existing read-only recent-media lookup and retry. Other network failures do not carry this acknowledgement. No recovery automatically repeats the shutter, and finding a JPEG is not physical proof of a new exposure.

This workflow is covered by an independent synthetic HTTP peer and automated tests; physical-camera validation remains pending.

### Selected CCAPI status body deadlines

Finite GET responses for `/devicestatus/batterylist`, `/devicestatus/battery`,
`/devicestatus/storage`, `/devicestatus/currentstorage`, `/contents`,
`/shooting/information/recordable`, `/devicestatus/lens`, and
`/devicestatus/temperature` use the existing request timeout as an absolute
response-body budget. The default remains 15 seconds. Bytes arriving at or after
that deadline are not published as success; an already-received HTTP rejection
keeps its status even if its optional diagnostic body cannot finish.

These reads share the provider lock with saved camera Stop operations. The
original request must unwind and close its body before the saved Stop runs;
timeout does not detach that producer, transfer its owner, or replay Start.
A successful release ACK remains confirmed even if its later status read fails.

This is a cooperative check between buffered reads, not a total Stop-latency
guarantee or forced cancellation of DNS, connection, headers, or a blocking
read. Each response has its own budget. Optional status refreshes through
`/functions/cardselection/stillimage`, `/functions/cardselection/movie`,
`/functions/beep`, and `/functions/displayoff` remain outside this selected
deadline policy. Media-file streams, Live View GET, and event polling retain
their separate behavior. See the [source scope and synthetic RED/GREEN evidence](
../docs/validation/bridge-ccapi-status-body-deadline-2026-10-20.zh-TW.md);
this evidence does not establish physical-camera Stop or network reliability.

## Local USB cleanup failures

If closing a registered local USB session raises, the Bridge retains that exact retired owner's reservation. New local connections, including AUTO, and camera discovery return `CAMERA_BUSY` for the rest of that Bridge process. Reconnecting, polling, and shutdown do not retry its close or clear the reservation: an already-closed provider can return successfully on a second close without performing any cleanup.

The failed session's handle has been removed, so its Stop and DELETE cannot be retried. Other existing sessions' Stop/disconnect operations and CCAPI remain available. Ordinary cleanup exceptions do not prevent shutdown from draining the other sessions; repeated shutdown reports the retained uncertainty without repeating the failed close. A process-level interruption still propagates immediately and can interrupt that drain. Check and stop the camera, repair the provider if needed, then restart the Bridge. Restart resets process-local state and does not prove physical shutter release or camera cleanup. Retry an explicit Stop before disconnecting whenever that original session is still available.

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

## Automatic original JPEG validation

Foreground automatic JPEG import requests the existing exact original once with
`attempt=single&validation=jpeg-v1`. The opt-in Bridge path rejects unsupported or
ambiguous HTTP MIME, caps the encoded transfer at 64 MiB, verifies its closed
private spool against the streaming SHA-256, parses the complete JPEG, then forces
strict native PyAV/FFmpeg decode in a separate process. Only success emits
`X-Open-EOS-Original-Validation: jpeg-v1`. The browser requires this proof before
writing or closing its pending output. Default/manual JPEG and RAW downloads, and
the older `attempt=single` route, retain their format behavior.

The [shared JPEG contract](../contracts/jpeg-original/v1/README.md) defines the
conservative envelope: 64,000,000 source pixels, 16,384 per source axis, bounded
markers/scans, and supported 8-bit sequential/progressive coding. Decode uses one
thread and one-eighth output resolution, capped at 2,048 per decoded axis. One
validation process can run at a time; its 15-second deadline includes startup,
and timeout triggers termination. Stop/disconnect prevents browser publication;
it does not instantly interrupt an opaque native call. This is bounded format
and decoder evidence, not proof that every corruption is detected or that the
camera supplied the intended photograph. Original bytes are never re-encoded.

Any validation, transfer or save failure stops the watcher without a redownload
or shutter resend. Only owned pending outputs are aborted/removed; already saved
files and existing transfer-history entries are retained. History remains subject
to the existing unavailable-storage warning. Review the failed result and any
partial file, then explicitly enable again to select a destination and establish
two fresh baselines. Existing files, including the failed ID, are skipped; the
new run watches newly observed photos only.

## RTP decoded-output and monitoring evidence

The RTP receiver advances frame progress only after a nonempty decoder output
returns. Packet arrival, NAL assembly, and an in-progress decoder call do not
refresh readiness. An undelivered buffer expires after five seconds; a closed
receiver cannot return cached readiness or publish a late decode. The existing
read deadline and exact-source Stop recovery remain in place. This deadline does
not claim to interrupt an opaque native decoder call or prove camera capture time.

The browser decodes each JPEG privately before installing that same image object
in the viewfinder. LUT submission and pixel analysis carry the same frame and LUT
revision, with five-second evidence expiry; no new image, unbounded request, or
pending decode can keep old monitoring overlays or FPS fresh. A LUT/context error
hides its overlays and remains diagnostic until a later valid render recovers.
The original frame can remain visible while awaiting the next bounded request.

Diagnostics distinguish decoded output and successful WebGL surface submission.
Neither an installed image nor a canvas draw is a compositor/physical-presentation
confirmation. The shared [monitor gate](../contracts/rtp-presentation/v1/monitor-vectors.json)
and controlled-decoder/JavaScript tests exercise lifecycle, same-frame identity,
expiry, errors, and recovery. Browser/GPU and physical-camera acceptance are
separate evidence and are not established by these tests.

Logical Stop also does not prove that a receiver worker has physically drained.
A worker alone closes its decoder after the active decode returns. Preparation
reserves the existing local UDP endpoint and, for CCAPI, the existing normalized
camera identity in memory. A replacement for that camera or endpoint is rejected
until all of its started video/audio workers finish cleanup, including after a
reconnect or SDP port change. Different cameras on distinct endpoints remain
independent. Failed cleanup retains the reservation; a codec that never returns
or a cleanup failure may require restarting the Bridge. The reservation neither
sends/persists camera identifiers nor acknowledges a camera Stop.

## Cancel a pending connection

The PC connection page offers **Cancel connection** while session creation or
initial metadata reads are pending. Cancel immediately retires that attempt's UI
publication rights and allows editing the connection method, camera, address and
authentication fields. It does not prove that a Bridge or camera request stopped.
The admitted creation request remains observed to recover its exact session ID;
all admitted metadata reads settle before the same captured Bridge endpoint,
token and session are used for one cleanup DELETE.

Scan and Connect remain unavailable while the shared backend is being retired.
No new attempt is queued: after cleanup, choose **Retry connection** explicitly.
Late success, error or completion cannot restore the canceled session, clear new
settings, replace a newer error, or unlock another owner. Only the Bridge's exact
HTTP 204 DELETE response acknowledges local session retirement; this is separate
from physical-camera validation and does not clear existing shutter warnings.
Late creation or cleanup replies identifying unconfirmed shutter/autofocus,
Live View, or recording retain separate fixed, localized previous-camera warnings.
Recording uncertainty explicitly says recording may still be active. These
warnings direct the user to the old camera and never grant Stop control to a new
session or display raw provider messages.

An explicit session-creation rejection permits correcting settings and retrying.
If the creation result is lost or malformed, or DELETE fails/has an unexpected
response, cleanup remains **unconfirmed** and camera access stays blocked. Check
the camera, restart this Bridge and reload the page before retrying. Reloading
alone does not close an unknown session. Page dismissal revokes publication while
JavaScript remains alive, but a destroyed browser page cannot guarantee observing
a late POST or delivering cleanup. The host tests cover controlled asynchronous
ordering; browser lifetime and physical-camera acceptance remain separate.


## Progressive USB media browsing

Clients that request the existing media-list NDJSON type now receive progressive
libgphoto2 snapshots from one controlled `--recurse --list-files` process. Cancel,
HTTP disconnect, session retirement, and Bridge shutdown stop that worker before
its ownership is released. Earlier complete snapshots remain partial on error;
only a successful process and complete parse finish the selected browsing scope.
Recent-61 still traverses the CLI output before choosing the newest 61 entries.
There is no extra camera query, automatic retry, or strict-inventory claim.

Native POSIX cleanup controls the process group. Windows-native/WSL abnormal
listing cleanup is conservatively unconfirmed: check the camera and restart the
Bridge before another camera command. Cancel does not certify physical USB
release. CLI buffering and existing mobile HTTP timeouts can delay or stop
progress; no unbounded or instant-loading claim is made.

See [the protocol](../docs/desktop-bridge-protocol.md#optional-progressive-media-browsing-ccapi-and-libgphoto2)
and [focused verification](../docs/gphoto-progressive-listing-validation.md).
