# Desktop Bridge Protocol

The desktop bridge is a local service that exposes the same camera-control concepts as `CameraControlBackend`. It lets mobile or desktop UI code use a mature PC-side camera engine without linking proprietary or platform-specific camera SDKs into the app.

## Implementation Status

`bridge/open_eos_bridge` is the first executable implementation. It uses FastAPI and provides two executable open engines: `libgphoto2` for USB cameras and a native Python HTTP `ccapi` engine for direct wireless camera control. A third `edsdk` registration is an SDK-neutral provider boundary and reports unavailable unless a separately installed licensed provider implements it; the repository contains no working Canon binding. The gPhoto adapter invokes argument arrays, never a shell. Android and iOS implement the bridge protocol behind their shared camera sessions, including discovery, engine-preserving camera selection, memory-only Bearer auth, capability parsing, JPEG frames, bounded media thumbnails, capability-gated display previews, and streamed media. Deterministic tests cover the executable PC engines, the optional-provider boundary, and the mobile contracts. Physical R6 Mark III validation is still required and must not be inferred from those tests.

The built-in PC UI also supports a browser-owned local UVC/HDMI preview. This is intentionally not a Bridge endpoint or camera capability: it replaces only the page's viewfinder while the existing Bridge session continues to provide camera controls. Device identifiers stay in page memory, diagnostics contain only a count/selection mode and allowlisted track settings, and all tracks are stopped during source, device, session, or page teardown.

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
GET  /v1/session/{id}/events
DELETE /v1/session/{id}/events
POST /v1/session/{id}/liveview/start
POST /v1/session/{id}/liveview/stop
GET  /v1/session/{id}/liveview/frame
POST /v1/session/{id}/liveview/magnification
POST /v1/session/{id}/settings/{key}
POST /v1/session/{id}/directories
PUT  /v1/session/{id}/file-naming/{field}
POST /v1/session/{id}/capture/still
POST /v1/session/{id}/bulb/start
POST /v1/session/{id}/bulb/stop
POST /v1/session/{id}/shutter/half-press
POST /v1/session/{id}/focus/auto
POST /v1/session/{id}/recording/start
POST /v1/session/{id}/recording/stop
POST /v1/session/{id}/focus/tap
POST /v1/session/{id}/whitebalance/click
POST /v1/session/{id}/focus/drive
POST /v1/session/{id}/maintenance/sensor-cleaning
POST /v1/session/{id}/power/sleep
GET  /v1/session/{id}/media
POST /v1/session/{id}/media?filename={safeBasename}
GET  /v1/session/{id}/media/{itemId}/thumbnail
GET  /v1/session/{id}/media/{itemId}/preview
GET  /v1/session/{id}/media/{itemId}
DELETE /v1/session/{id}/media/{itemId}
DELETE /v1/session/{id}
```

Media upload is a raw, authenticated request body with an exact required `Content-Length`, a safe basename query parameter, and a 4 GiB-minus-one upper bound. The endpoint exists only for `MEDIA_UPLOAD`. The libgphoto2 engine requires runtime File Upload evidence and writable storage, stages the request in a private temporary directory, rejects case-insensitive card-name collisions, invokes `gphoto2 --folder ... --filename ... --upload-file ...` as an argument array, then refreshes the camera listing and requires exactly one case-insensitive name and exact-size match before success. The endpoint watches for client disconnect after staging; cancellation sets a server-side event and terminates or kills an active gPhoto2 subprocess before commit. Once the process exits successfully, bounded readback always finishes so clients can reconcile an ambiguous late abort through a fresh list. Missing, short, long, cancelled, duplicate, rejected or unverifiable transfers remain errors. A generic picker MIME is inferred only after the filename extension passes the fixed image/video allowlist. Direct CCAPI returns unsupported because no verified Canon CCAPI upload resource is claimed.

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

`GET /health` reports every registered engine. `GET /v1/cameras` aggregates only available local engines and each returned descriptor carries its engine. Clients should send that exact engine with the selected camera ID. `auto` defaults to `libgphoto2` when no selected ID resolves to another engine; an explicit `edsdk` request never falls back. See [Optional Canon EDSDK Provider](edsdk-provider.md).

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
  "fileNaming": {
    "stillFilenameMode": "preset_code",
    "stillFilenameModeOptions": ["preset_code", "usersetting1", "usersetting2"],
    "stillUserSetting1": "IMG_",
    "stillUserSetting2": "EOS",
    "movieIndex": "A_",
    "movieReelNumber": 1,
    "movieReelRange": {"minimum": 1, "maximum": 9999, "step": 1},
    "movieClipNumber": 1,
    "movieClipRange": {"minimum": 1, "maximum": 999, "step": 1},
    "movieUserDefined": "EOS01"
  },
  "evidence": {
    "source": "GET /ccapi",
    "protocolVersions": ["ver100"],
    "advertisedCommands": ["POST /ccapi/ver100/shooting/control/shutterbutton"],
    "writableSettings": ["iso"],
    "truncated": false
  }
}
```

`evidence` is immutable diagnostic context for the active engine. CCAPI reports method/path pairs from discovery; libgphoto2 reports abilities, writable configuration paths, and the successful bounded `--wait-event` probe when available. It does not enable any unrelated capability by itself. Producers de-duplicate and sort evidence, remove URL queries and line breaks, limit each list to 256 items and each item to 512 characters, and set `truncated` when data was omitted. The browser diagnostic report includes this object plus schema/version/time metadata and the advertised/observed set difference. A separately tested recursive sanitizer removes credentials, authorization values and camera serials before display or clipboard copy.

## Camera Events

For direct CCAPI, `EVENT_POLLING` requires both advertised `GET` and `DELETE /event/polling`. `GET /v1/session/{id}/events` blocks until Canon returns an event or its bounded long timeout expires. For libgphoto2, the session runs `gphoto2 --wait-event=1ms` once and advertises the capability only when that real command succeeds. Any event received during the probe is retained. Product polls use `--wait-event=250ms`; the parser accepts only gPhoto2's stable `UNKNOWN`, `CAPTURECOMPLETE`, `FILEADDED`, `FOLDERADDED`, and `FILECHANGED` markers and maps them to bounded `shooting`, `contents`, and `storage` refresh hints. Both engines return only sanitized change keys:

```json
{
  "changedKeys": ["shootingsettings", "batterylist"]
}
```

The event payload is partial and never replaces `CameraStatus`. Android, iOS, and the browser re-read status and capabilities after a non-empty result; clients also refresh an already-open media view after a `contents` hint. The browser waits for an active camera interaction to finish and rejects refresh results from an older interaction generation, so a delayed event read cannot overwrite the result of a newer setting or capture command. Media refresh requests share one in-flight task, wait through an active interaction, and retry when that generation changes, so opening Media during capture cannot strand the view without a list. `DELETE /v1/session/{id}/events` invalidates an in-flight result without waiting for the normal camera-control lock, and session close performs the same cleanup. The CLI event wait and camera commands share exclusive USB access, so each wait is deliberately short. While persistent libgphoto2 Live View or Bulb is active, the endpoint returns an empty bounded poll instead of stopping capture; `CameraStatus.raw.eventPollingPaused` and `eventPollingTransport` expose that state. A failed runtime probe keeps the feature planned and the product does not start a fake status polling loop.

## Live View

`POST /liveview/start` accepts the same request shape as the app core:

```json
{
  "fps": 15,
  "size": "MEDIUM",
  "source": "AUTO"
}
```

The response includes the effective `source`, so clients can distinguish an `AUTO` request that selected `CCAPI_RTP`, `CCAPI_MULTIPART`, or `CCAPI_JPEG_POLLING`.

`POST /liveview/magnification` accepts `{"value":1}` or `{"value":5}` and returns the accepted value. It is capability-gated and requires active Live View. The libgphoto2 engine exposes it only when a writable `eoszoom` runtime widget exists. Canon CCAPI `GET`/`POST /shooting/control/zoom` is exposed separately as the generic `zoom` camera setting and `ZOOM_CONTROL`; it never masquerades as Live View magnification.

Direct CCAPI dual-card selection is exposed through generic `cardselectionstillimage` and `cardselectionmovie` settings plus `CARD_SELECTION_CONTROL`. The Bridge only creates either setting from an exact same-version Canon GET/PUT pair with a valid `none`/`card1`/`card2` ability list; the existing session setting endpoint forwards only a currently advertised value. This is distinct from libgphoto2's runtime storage-ID-backed `capturestorage` setting.

Direct CCAPI capture-directory control is exposed through the generic `directoryselection` setting plus `DIRECTORY_CONTROL`. The Bridge requires one API version to advertise all of `POST /functions/directory/createdirectory` and `GET`/`PUT /functions/directory/directoryselection`, then accepts only a unique bounded ability list of one to 256 eight-character Canon directory values such as `100EOSXX`. A single-directory card still exposes creation. `POST /v1/session/{id}/directories` accepts an empty name for camera-generated `EOSXX`, or exactly five ASCII uppercase letters, digits, or underscores. It forwards Canon's `directoryname` body, validates the five-character response, refreshes the selection list, and never infers an equivalent libgphoto2 operation.

Direct CCAPI file naming is exposed as `FILE_NAMING_CONTROL` plus the structured `fileNaming` capability object. One API version must advertise GET and PUT for all seven Canon resources: `stills/filename`, `stills/usersetting1`, `stills/usersetting2`, `movies/index`, `movies/reelnum`, `movies/clipnum`, and `movies/userdefined`. Every response must validate before the capability appears. `PUT /v1/session/{id}/file-naming/{field}` accepts `{"value":"..."}` at the Bridge boundary, where `field` is one of `still-filename-mode`, `still-user-setting-1`, `still-user-setting-2`, `movie-index`, `movie-reel-number`, `movie-clip-number`, or `movie-user-defined`. The CCAPI engine maps that value to Canon's endpoint-specific field, preserves integer types for reel and clip, verifies the immediate response, re-reads the complete group, and returns the updated `FileNamingState`. Incomplete, malformed, stale, or cross-version contracts never produce a write. The libgphoto2 engine returns `UNSUPPORTED_FEATURE`; no similarly named USB setting is inferred.

Direct CCAPI sound recording level is exposed through the generic `soundrecordinglevel` setting plus `SOUND_RECORDING_LEVEL_CONTROL`. The Bridge requires an exact same-version Canon `GET`/`PUT /shooting/settings/soundrecording/level` pair and an exact bounded integer current/min/max/positive-step contract with 2-256 choices. `PUT /v1/session/{id}/setting` re-reads the Canon resource before forwarding an integer `value`; malformed, stale or unadvertised values never produce a camera write. Product UIs filter this setting into Video and render it as a discrete slider.

Direct CCAPI sound recording mode, wind filter and attenuator are exposed as `soundrecording`, `windfilter` and `attenuator` plus `SOUND_RECORDING_CONTROL`. Each Bridge setting requires an exact same-version GET/PUT endpoint pair and at least two unique camera-advertised strings drawn from Canon's documented values. `PUT /v1/session/{id}/setting` forces a fresh read before forwarding the exact string; malformed, stale, unknown and cross-version contracts never produce a camera write. Product UIs filter all three into Video settings.

Direct CCAPI focus bracketing is exposed as `focusbracketing`, `focusbracketingnumberofshots`, `focusbracketingfocusincrement` and `focusbracketingexposuresmoothing` plus `FOCUS_BRACKETING_CONTROL`. The Bridge validates the exact same-version root GET/PUT pair before reading independently advertised children. Root and exposure smoothing accept only camera-advertised `enable`/`disable`; shot count and focus increment require exact bounded integer ranges with at most 1,024 generated choices. `PUT /v1/session/{id}/setting` forces a complete advertised-group re-read and forwards integer values for the ranges. Product UIs expose the group only in Photo settings and use range controls for the integer resources.

Direct CCAPI movie recording settings are exposed as `moviequality`, `highframerate`, `moviecropping`, and `movieformat` plus `MOVIE_SETTINGS_CONTROL`. Every control requires an exact same-version GET/PUT pair and a bounded unique string ability containing the current value. `PUT /v1/session/{id}/setting` forces a complete advertised movie-setting re-read and forwards the exact camera token. Product UIs expose the group only in Video settings and render Canon movie-quality and recording-format tokens as readable summaries without altering writes.

Direct CCAPI camera beep and display-off timeout are exposed as generic `beep` and `displayoff` advanced settings. Each requires an exact same-version GET/PUT pair at `/functions/beep` or `/functions/displayoff`, at least two unique documented strings containing the current value, and a fresh group read before `PUT /v1/session/{id}/setting` forwards the exact token. Both settings remain available in Photo and Video contexts. Malformed, stale, single-choice and cross-version contracts never produce a camera write; immediate auto-power-off is a separate disconnecting action and is not represented by `displayoff`.

Direct CCAPI Auto Power Off is exposed as generic `autopoweroff` plus a separately gated `CAMERA_SLEEP` capability. The timed setting requires an exact same-version `GET`/`PUT /functions/autopoweroff` pair, a current value inside a complete unique ability and at least two documented non-immediate choices. Every setting write re-reads that resource and forwards only `30`, `60`, `120`, `180`, `300`, `600`, or `disable`. `POST /v1/session/{id}/power/sleep` is available only when the live ability explicitly contains `immediately`; the engine sends that exact PUT value, requires Canon's HTTP 202 response and then ends the session. Clients must confirm this destructive action and stop local Live View/event/media work first. The libgphoto2 engine returns `UNSUPPORTED_FEATURE` for this route because its proven timed Auto Power Off config does not establish an immediate-sleep command.

Direct CCAPI sensor cleaning is exposed only as `SENSOR_CLEANING` when discovery advertises `POST /functions/sensorcleaning`. `POST /v1/session/{id}/maintenance/sensor-cleaning` accepts `{"autoPowerOff":false}` at the Bridge boundary, forwards Canon's exact `{"autopoweroff":false}` body, requires HTTP 200, and stops CCAPI event polling and Live View before sending the command. Clients restore their active session only after a non-power-off success; clean-and-power-off ends the session. The libgphoto2 engine returns `UNSUPPORTED_FEATURE` because no verified public CLI contract is available.

The libgphoto2 CLI adapter starts one cancellable `gphoto2 --capture-movie --stdout` process and incrementally extracts bounded JPEG frames from its concatenated MJPEG output. It accepts a 1-30 FPS output cap but does not claim the camera and USB link delivered that rate. Commands that need exclusive camera access stop the movie process first; the next frame request automatically starts a fresh process. Startup, early termination, malformed-frame, size-limit, or frame-timeout failures switch the session to bounded `--capture-preview --stdout` transactions and reduce effective `requestedFps` to at most 5. `CameraStatus.raw.liveViewTransport` and `liveViewFallbackReason` distinguish these paths.

`GET /v1/session/{id}/media` accepts an optional `limit=1..1000`. Omitting it returns the complete list produced by the active engine; there is no 500-item product limit. With a limit, direct CCAPI still discovers every sibling content container so photo and movie trees are sampled fairly, but stops reading additional pages from each media leaf after enough newest paths are available and returns at most the requested total. The libgphoto2 CLI adapter bounds the HTTP response but still has to complete one documented `gphoto2 --recurse --list-files` command because that command exposes no page or early-stop argument. Android, iOS, and the PC UI default to requesting 61 and displaying the latest 60; **Full card** deliberately omits the limit. Diagnostics pair the current list size with load status, scope, and whether more items remain. `COMPLETE` means the selected scope finished; only a complete **Full card** traversal represents the card total. A recent, loading, cancelled, failed, or not-loaded count must not be reported as the card total.

`GET /v1/session/{id}/media/{itemId}/thumbnail` returns a bounded JPEG or PNG with `Cache-Control: private, no-store`. Camera-resident libgphoto2 media uses the documented `--folder ... --get-thumbnail ... --stdout` command only when `gphoto2 --abilities` reports file-preview support. Host-RAM captures use the Bridge's bounded Pillow decoder for supported local image formats; unsupported RAW preview formats return a real error while the original remains downloadable. The direct CCAPI engine follows Canon's official Android sample by adding the structured `kind=thumbnail` query to the exact same-origin content URL, bounds the response to 8 MiB, and rejects empty, textual or unrecognized payloads.

`GET /v1/session/{id}/media/{itemId}/preview` is separately capability-gated and returns a private, non-cacheable display image up to 32 MiB. Every media item includes `previewAvailable`; clients must require both that field and `MEDIA_PREVIEW` before exposing an action. Canon CCAPI Reference 1.3 section 4.7.5 defines `display` as a standard query kind under an advertised contents GET rather than a separately advertised endpoint. The direct engine therefore marks only JPEG/CR3 paths as eligible, sends `kind=display` to the exact same-origin content path, validates the response, and preserves camera errors because some otherwise eligible files remain unsupported. The libgphoto2 engine uses bounded `--folder ... --get-file ... --stdout` for camera-resident JPEG/PNG and bounded local reads for host captures, then validates a complete image. RAW, HEIF and video items report `previewAvailable: false` on wired paths.

`GET /v1/session/{id}/media/{itemId}/info` returns the authoritative media item including nullable `protected`, `archived`, `rating`, and `rotationDegrees`. Camera-resident libgphoto2 items use the documented read-only `--folder ... --show-info ...` command to refresh the primary file MIME type, exact byte size, capture time, width and height; the parser is fixed to the `File:` section so thumbnail or audio metadata cannot replace the original file values, and a valid response with missing fields produces unknown values instead of stale listing data. Width and height are also retained when `--list-files` reports its optional `WIDTHxHEIGHT` pair. Host-RAM items are refreshed from the confined local capture store without invoking gphoto2. The shared model still does not expose libgphoto2's downloaded or permission lines, and no permission value is misrepresented as Canon protection metadata. gphoto2 formats `Time` through the CLI environment's local timezone; native execution is deterministic, while WSL physical validation must confirm that its timezone matches the Windows Bridge host before relying on that optional timestamp. Direct CCAPI enables `MEDIA_PROTECT`, `MEDIA_ARCHIVE`, `MEDIA_RATING`, and `MEDIA_ROTATE` only when discovery advertises contents `PUT`. The corresponding Bridge routes are `PUT .../protection` and `PUT .../archive` with `{"enabled":true|false}`, `PUT .../rating` with `{"value":0..5}`, and `PUT .../rotation` with `{"degrees":0|90|180|270}`. The engine maps these to Canon's documented `protect=enable|disable`, `archive=enable|disable`, `rating=off|1..5`, and `rotate=0|90|180|270` action/value bodies, then performs up to three bounded `kind=info` readbacks. A mismatch is an error and never observed evidence. libgphoto2 still returns unsupported for all four metadata-write routes.

```json
{
  "id": "gphoto2:opaque-id",
  "name": "IMG_0001.JPG",
  "kind": "image",
  "sizeBytes": 8912384,
  "contentType": "image/jpeg",
  "widthPixels": 6000,
  "heightPixels": 4000,
  "previewAvailable": true,
  "protected": false,
  "rating": 3,
  "rotationDegrees": 90
}
```

The CCAPI engine advertises `CCAPI_JPEG_POLLING` from 1 through 30 FPS and defaults the PC UI to 15 FPS. Its lifecycle requires an advertised general `POST /shooting/liveview`, a same-origin frame endpoint, and either the matching general `DELETE` or Canon's documented POST-off stop body `{"cameradisplay":"on","liveviewsize":"off"}`; it never claims JPEG support without an explicit stop strategy. It starts Live View with `cameradisplay` and the selected size, retries once without `liveviewsize` only when the camera returns HTTP 400, and reads the first complete bounded JPEG before reporting startup success. If that first frame returns HTTP 503 `Mode not supported` for medium or large, it closes the selected lifecycle, starts `small`, and reports the effective `SMALL` size. When coordinate Tap AF or Click White Balance is advertised, `flipdetail?kind=both` is preferred so the same bounded response supplies the JPEG and Canon image-position metadata. Requested FPS controls client polling; observed FPS remains a separate UI metric.

The CCAPI engine advertises `CCAPI_MULTIPART` only for a same-version general POST start, multipart GET/DELETE pair, and a valid general stop strategy. This includes latest R6 Mark III firmware, where general DELETE is absent and stop is POST-off. It opens Canon's persistent `multipart/x-mixed-replace` stream, waits for the first complete bounded `image/jpeg` part before reporting startup success, drains continuously on a background reader and retains only the latest frame for `/liveview/frame`. Requested FPS caps Bridge output rather than camera production. Stop closes the local reader, treats multipart DELETE as best-effort because the camera may return 503, and always sends the selected general stop operation. AUTO tries RTP, same-version multipart and polling in that order, with complete cleanup between attempts; RTP HTTP 503 therefore reaches the verified multipart lifecycle before JPEG polling.

The CCAPI engine also advertises `CCAPI_RTP` only when discovery contains `GET /shooting/liveview/rtpsessiondesc` and `POST /shooting/liveview/rtp`, the camera route resolves to a usable local IPv4 address, and the installed PyAV runtime can create an H.264 decoder. It validates the SDP H.264/90 kHz stream, binds the advertised UDP port before posting `{"action":"start","ipaddress":"..."}`, parses RFC 3550 and RFC 6184 single NAL/STAP-A/FU-A packets, waits for SPS/PPS plus a keyframe, decodes every access unit, and converts only FPS-eligible decoded frames to JPEG. Start is not reported successful until the first frame is actually decoded; a timeout closes the receiver, sends Canon's stop body, and lets AUTO fall back to JPEG. Stop, failed HTTP start, session close, and AUTO fallback all clean up both sides. `GET /liveview/frame` remains `image/jpeg`, so existing Bridge clients need no video-container decoder.

If the same SDP advertises Canon's `MP4A-LATM/48000` audio port, omission of `cpresent` is treated as RFC 6416's default in-band configuration. The Bridge binds audio independently, validates RTP sequence/timestamp/marker boundaries, drops incomplete elements, wraps each completed `audioMuxElement` in LOAS framing, and decodes it with PyAV/FFmpeg. Explicit out-of-band `cpresent=0` remains unavailable instead of being guessed. Decoded output is bounded 48 kHz, stereo, signed 16-bit little-endian PCM; audio bind/decode failure is recorded under `CameraStatus.raw.rtpAudio` but never tears down ready video.

`GET /v1/session/{id}/liveview/audio?after={generation}&timeoutMs={0..5000}` is an authenticated bounded long poll. A chunk returns `audio/pcm;rate=48000;channels=2;format=s16le` plus `X-Open-EOS-Audio-Generation`, `-Sample-Rate`, `-Channels`, `-Frames`, and `-Discontinuity` headers. No newer chunk returns `204`; an inactive or unavailable path returns `409 UNSUPPORTED_FEATURE`. `after=0` starts at the newest buffered chunk to avoid playing stale muted audio. The browser validates the exact byte count, resets its WebAudio timeline on discontinuity, and remains muted until a user gesture enables playback.

## CCAPI Mapping

The network engine discovers versions and HTTP methods from `GET /ccapi`; a fallback identity probe establishes connectivity but does not invent unsupported command capabilities. It maps only advertised operations:

- identity, battery, storage, and merged versioned shooting settings
- camera-advertised setting values and their discovered `PUT` paths
- direct shutter or manual full press with guaranteed release
- held Bulb exposure through manual `full_press` and explicit `release`; capability requires the advertised manual shutter operation and successful release is the observation point
- timed half-press with guaranteed release
- independent autofocus through advertised `POST /shooting/control/af` start/stop, falling back to the advertised balanced half-press operation
- camera Photo/Video context through matching same-version `GET`/`POST /shooting/control/moviemode`, accepting only Canon's `on`/`off` status and posting the exact `action`; this remains distinct from recording
- movie recording start/stop through `recbutton`
- normalized UI Tap AF mapped through detailed Live View `image` geometry to integer `positionx`/`positiony`, then sent only through advertised `PUT /shooting/liveview/afframeposition`
- normalized UI Click White Balance mapped through the same geometry, then sent only through advertised `POST /shooting/liveview/clickwb`
- bounded `event/polling` on an independent wait path, with v1.0 `continue=on`, v1.1+ `timeout=long`, explicit `DELETE`, and authoritative state refresh by clients
- bounded JPEG polling and persistent multipart Live View lifecycles with frame extraction
- advertised RTP H.264 lifecycle, UDP reception, RFC 3550/RFC 6184 depacketization and PyAV decode-to-JPEG output; independently advertised PC `MP4A-LATM/48000` reception, RFC 6416 reassembly, PyAV decode and bounded PCM delivery
- bounded/paged storage traversal plus opaque same-origin media IDs, streamed downloads, and deletion only when the camera advertises a matching `DELETE` operation

Basic Auth is sent preemptively when a username is supplied. The Authorization value, username, and password are never exposed in status, diagnostics, media URLs, or API responses. Focus drive is available only when the camera advertises the verified `drivefocus` POST operation. RTP is hidden when any camera-endpoint, route, or decoder gate is absent; no unusable source is advertised.

## libgphoto2 Mapping

The adapter derives capabilities from `--abilities` and `--list-all-config` instead of assuming every EOS body supports every command:

- discovery: `--auto-detect`
- identity and status: `--summary`, `--list-all-config`, `--storage-info`; the parser supports gphoto2's official `[Storage N]` key/value output, treats `totalcapacity`/`free` as KB, and obtains PTP storage IDs only from a validated `/store_<8 hex>` base directory. Remaining shots prefer the read-only `/main/status/availableshots` value and fall back to a valid storage summary count
- settings: camera-advertised values through `--set-config-value`; the R6 Mark III mapping includes WB A/B shifts, SD and CF/CFexpress image quality, aspect ratio, power-zoom speed, timed Auto Power Off and active recording card in addition to the existing exposure, AF, drive, metering, Picture Style, color, noise-reduction, AEB and movie controls; no immediate-sleep capability is inferred from the timed property
- still capture: expose only recognized writable Capture Target choices; `Memory card` runs `--trigger-capture` with advertised `--capture-image` fallback, while `Internal RAM`/`SDRAM` requires image-capture ability and runs `--capture-image-and-download` with a Bridge-owned unique filename template
- half-press: advertised `eosremoterelease` press/release values with guaranteed release
- Bulb: expose only when writable `eosremoterelease` includes both a full-press choice and a full-release choice; keep the session active until release succeeds and attempt release again during session teardown
- independent autofocus: paired writable `autofocusdrive=1` and guaranteed `autofocuscancel=1` actions when both exist, falling back to the balanced half-press path
- recording: advertised `movierecordtarget` Card/None values
- focus drive: advertised `manualfocusdrive` Near/Far values while Live View is active
- camera events: successful `--wait-event=1ms` runtime probe followed by bounded `--wait-event=250ms` property/capture/media hints; event waits pause rather than taking the camera away from persistent Live View or Bulb
- Live View focus magnification: advertised writable `eoszoom` with only the R6 Mark III-backed values 1 and 5 while Live View is active
- Live View: advertised `viewfinder` lifecycle plus cancellable `--capture-movie --stdout` MJPEG, command-safe restart and bounded `--capture-preview --stdout` fallback, with cleanup on stop, failed start, and session close
- media: merge camera-resident recursive `--list-files` items with opaque-ID host captures; camera JPEG/PNG items use bounded `--get-file ... --stdout` display previews plus the existing streamed download and ability-gated exact deletion, while host items use bounded local previews/thumbnails, chunked download, and exact store-confined deletion. Per-item `previewAvailable` prevents unsupported RAW/HEIF/video actions.

Settings are exposed only when the runtime config is writable and has safe selectable values. The undocumented R6 Mark III Auto Power Off `0xFFFFFFFF` sentinel is rejected even if posted directly to the API, and one-choice advanced controls stay out of the product UI. Capture Target accepts only camera-advertised `Memory card`, `Card`, `Internal RAM`, or `SDRAM` values; host targets are hidden unless image capture is advertised. Active recording card is a separate Photo-only `capturestorage` control: it requires writable TEXT `storageid`, a current value matching at least two writable IDs from `--storage-info`, and a fresh successful config/storage read immediately before the exact ID write. The API accepts only a display value it previously advertised, keeps duplicate-label fallbacks bound to IDs across enumeration reordering, and never accepts an arbitrary hex value. Host files are written into same-volume staging and become visible only after gPhoto2 reports that capture, download, and camera-side temporary deletion all succeeded; failure removes partial staging and never reports a fake capture. RAW+JPEG companion events share one unique template and are promoted together. The API never returns the storage path. Coordinate tap focus and Click White Balance remain unavailable because the public CLI surface does not provide verified normalized image-coordinate commands for this camera. Unsupported controls return an error and are never reported as accepted.

## Run Locally

```powershell
cd bridge
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\open-eos-bridge.exe
```

The defaults are `127.0.0.1:18181` and loopback-only access. The UI offers `libgphoto2` USB and direct `ccapi` connections; set `OPEN_EOS_GPHOTO2` when the native executable is not named `gphoto2`. On Windows, native gphoto2 wins; otherwise the runner uses `wsl.exe --exec gphoto2`, optionally adding `--distribution <OPEN_EOS_GPHOTO2_WSL_DISTRO>`. The command remains an argument array and never passes through a shell. Windows capture paths are mapped to `/mnt/<drive>/...` for WSL; UNC storage is rejected because it has no reliable local-drive mapping. `OPEN_EOS_CAPTURE_DIR` may select another absolute host media directory; platform user-data storage is the default and its path is excluded from API responses and diagnostics. Health preflights the WSL distribution before launching gphoto2, preserves UTF-16 Windows diagnostics, and reports missing distro, missing WSL package, or missing usbipd separately. `scripts/windows-gphoto2-doctor.ps1` performs the same read-only host audit and can emit JSON. For a LAN bind, set both `OPEN_EOS_BRIDGE_HOST` and a strong `OPEN_EOS_BRIDGE_TOKEN`.

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
