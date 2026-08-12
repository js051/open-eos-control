# Changelog

All notable release-level changes to Open EOS Control are documented here.

## [Unreleased]

## [0.2.0] - 2026-08-12

- Hardened Android, iOS, and PC Canon CCAPI discovery and Live View for EOS R6 Mark III firmware 1.1.0, including Canon developer-list fallback, POST-only lifecycle cleanup, advertised-size downgrade, bounded multipart and busy retries, RTP first-frame readiness, and deterministic AUTO fallback.
- Added capability-gated R6 Mark III movie quality, cropping, recording-format, sound-recording, microphone, wind-filter, attenuator, and manual-level handling across Android, iOS, PC, and Simulator, with exact advertised values and fresh readback contracts.
- Completed a non-destructive production-engine and installed-Android-AVD pass against a physical EOS R6 Mark III, covering discovery, 118 advertised reads, 28 reversible setting writes/restores, JPEG and multipart Live View, 6/15/30 FPS reporting, focus, events, and bounded media traversal/preview/download.
- Added fresh, read-only libgphoto2 per-item media information through the existing Desktop Bridge API, while preserving Live View restart and rejecting malformed or stale metadata.
- Improved iOS Desktop Bridge FPS control, cross-platform media enumeration, event cleanup, capability evidence, and camera-control UI reliability.

## [0.1.10] - 2026-08-10

- Added capability-gated Canon CCAPI media archive state and writes across Android, iOS, PC and Simulator. Clients require an advertised contents `PUT`, send Canon's exact `archive` action with `enable` or `disable`, and require a matching bounded `kind=info` readback before reporting success or observed evidence. Unknown archive state stays hidden, offline previews mutate locally, and the iOS metadata sheet now opens at a usable large detent.

## [0.1.9] - 2026-08-10

- Added capability-gated wired media upload across Android, iOS and PC. Android USB uses standard PTP `SendObjectInfo`/`SendObject` only with advertised operations, a writable card and a matching object format, snapshots SAF input before the transaction, requires exact ObjectInfo readback, and drops a partial/cancelled PTP session. Desktop Bridge requires runtime libgphoto2 File Upload evidence, exact bounded raw bodies, writable storage, cancellable gPhoto2 subprocesses and fresh name/size verification; iOS and Android Bridge clients stream file-backed requests with bounded responses. Direct Canon CCAPI remains explicitly unsupported, while Simulator-only contracts preserve exact bytes and enforce matching filename/MIME categories.
- Added capability-gated Canon CCAPI multipart JPEG Live View across Android, iOS, PC and Simulator. AUTO now prefers RTP, then a persistent `/shooting/liveview/multipart` stream, then bounded JPEG polling. Each client requires GET/DELETE multipart plus the regular start/stop lifecycle in one API version, continuously drains the stream while conflating old frames, validates bounded MIME headers, `Content-Length` and complete JPEG markers, records support from advertisement and observation only after a valid frame, and performs multipart DELETE before regular Live View cleanup.
- Added capability-gated Canon CCAPI sensor cleaning across Android, iOS, PC and Simulator. The maintenance action appears only for an advertised `POST /functions/sensorcleaning`, sends Canon's strict boolean `autopoweroff` body, requires HTTP 200, pauses Live View/event polling and either restores the session or disconnects after clean-and-power-off. Recording, Bulb, preview and conflicting operations remain disabled; libgphoto2 and Android USB make no unsupported cleaning claim.
- Added capability-gated Canon CCAPI Auto Power Off and immediate camera sleep across Android, iOS, PC and Simulator. Timed controls require an exact same-version GET/PUT pair, a fresh valid ability read and documented non-immediate values; `immediately` is excluded from normal settings and becomes a separate confirmed action only when advertised. Accepted sleep stops local work and disconnects, while libgphoto2 and Android USB make no unsupported immediate-sleep claim.
- Added capability-gated Canon CCAPI camera beep and display-off timeout across Android, iOS, PC and Simulator. Both controls require exact same-version GET/PUT endpoint pairs, a valid documented string ability containing the current value, and a fresh pre-write read; malformed, stale, single-choice and cross-version contracts stay hidden or fail without a camera write, while Canon busy/recording responses remain authoritative.
- Hardened Canon CCAPI discovery across Android, iOS and PC for firmware/model responses that identify a protocol version but contain no valid root commands. Clients now query Canon's documented `/ccapi/ver100/topurlfordev` list in that case, accept only validated same-origin method/path entries, and keep every control disabled when the developer list is empty, malformed or unavailable.
- Added capability-gated Canon CCAPI movie quality, high frame rate, cropping and recording format across Android, iOS, PC and Simulator. Video settings require exact same-version GET/PUT endpoint pairs, bounded camera-advertised string abilities and a fresh pre-write read; readable quality summaries remain separate from exact protocol tokens, while Still, recording, malformed, stale and cross-version states stay unavailable or fail without a write.
- Added capability-gated Canon CCAPI focus bracketing across Android, iOS, PC and Simulator. Photo settings expose enablement, shot count, focus increment and exposure smoothing only from exact same-version GET/PUT endpoint pairs after the root contract validates; integer ranges are bounded, every write re-reads camera abilities, and Movie, busy, shooting, malformed, stale and cross-version states remain unavailable or real failures.
- Added capability-gated Canon CCAPI sound recording mode, wind filter and attenuator across Android, iOS, PC and Simulator. Each control requires an exact same-version GET/PUT pair, a unique documented string ability containing the current value, and a fresh pre-write read; all three remain Video-only and transient Canon 503 responses stay authoritative.
- Added capability-gated Canon CCAPI sound recording level across Android, iOS, PC and Simulator. Clients require matching same-version `GET`/`PUT /shooting/settings/soundrecording/level`, strictly validate Canon's integer current/min/max/step contract, re-read the range before each write, send an integer `value`, and expose the control as a Video-only discrete slider. Malformed, single-choice, oversized, stale and cross-version contracts stay hidden or fail without a write.
- Added capability-gated Canon CCAPI lens and temperature status across Android, iOS, PC and Simulator. Clients use only advertised `GET /devicestatus/lens` and `GET /devicestatus/temperature`, strictly validate Canon's documented payloads, expose localized warnings and redacted diagnostics, refresh temperature immediately before still capture, recording start and Live View start, and never block the corresponding stop/release cleanup commands.
- Added capability-gated Canon CCAPI Movie Mode synchronization across Android, iOS, PC and Simulator. Photo/Video selection now uses matching same-version `GET`/`POST /shooting/control/moviemode`, accepts only Canon's `on`/`off` status, sends the exact `action` body, and remains local-only when the camera does not advertise a valid writable endpoint. Failed writes retain the camera-confirmed mode instead of leaving an optimistic UI state.
- Added capability-gated Canon CCAPI optical zoom across Android, iOS, PC and Simulator. Clients require matching same-version GET/POST discovery, validate the bounded integer range, send Canon's integer POST body only after slider release, and hide the control when the active camera/lens does not expose it.
- Added a required iOS HTTP-preset workflow that explicitly bypasses localhost Simulator detection and drives the production SwiftUI app through Canon discovery, versioned JPEG Live View and Canon 1.1 event polling. External ISO and still-capture events must update the exposure strip and an open media view without manual refresh, and disconnect must issue event DELETE plus Live View cleanup. Event refreshes now wait for active controls/media work and retry when a newer operation revision would otherwise let a stale snapshot overwrite interactive state.
- Added a required Android HTTP-preset workflow that explicitly bypasses the Simulator shortcut and drives the production UI, ViewModel, repository, and `CcapiClient` through Canon-style discovery, versioned settings/capture, JPEG Live View, Canon 1.1 event polling, camera-side ISO synchronization, and GET/DELETE cleanup. Simulator intent is now carried explicitly from the connection preset while the previous localhost heuristic remains only as a compatibility fallback when intent is unspecified.
- Extended the required PC Wireless CCAPI browser workflow through Canon 1.1 `GET`/`DELETE /event/polling`. External camera-side ISO and media changes now prove that the production event loop refreshes authoritative settings and an open media view without manual refresh, while disconnect proves that the in-flight long poll is explicitly cancelled. Browser refresh generations prevent an older event response from overwriting a newer interactive command; media refreshes requested during an active command now wait and re-read instead of silently leaving an empty page; diagnostic/physical-validation copies hold one consistent visible report through SHA-256 generation.
- Added a required PC Wireless CCAPI browser workflow that drives the production UI, FastAPI service, and `CcapiEngine` against Canon-shaped HTTP discovery and control endpoints. It asserts exposure, clock sync, balanced AF and half-press, still capture, R6 Mark III Live View 400 fallback, decoded JPEG frames, geometry-backed Tap AF/Click WB, focus drive, recording, Bulb, media preview/delete, diagnostics, and disconnect cleanup against simulator state.
- Expanded the PC browser workflow from visual interaction checks to exact UI-to-FastAPI-to-libgphoto2 command assertions for ISO, AF drive/cancel, half-press/release, host capture, media preview/delete, Live View, manual focus, magnification, recording, Bulb and disconnect cleanup. Blocking MJPEG frame waits now release the camera lock so controls can interrupt and restart the persistent stream, while browser teardown aborts in-flight frame requests without a false 409 error.
- Expanded iOS's required direct-CCAPI Simulator workflow through balanced AF-ON and shutter half-press, coordinate Tap AF, Click White Balance, manual focus drive, mode-gated Bulb start/stop, decoded media preview, confirmed deletion, and backend-state assertions. Swift Simulator sessions now advertise and execute the existing focus-drive and Bulb contracts instead of hiding them as planned capabilities, while an explicit image-bounded interaction layer makes Live View taps reliably hittable and accessible.
- Expanded Android's required CCAPI Simulator device workflow through Tap AF, Click White Balance, balanced AF-ON and shutter half-press, validated manual focus drive, mode-gated Bulb start/stop, decoded media display preview, confirmed deletion, and backend-state assertions. The Simulator now rejects invalid focus-drive values and Bulb starts outside Bulb mode instead of reporting false success.
- Refined Android's fixed Photo/Video mode rail with direction-aware labels, a rotating short selection underline, and a retained 48dp hit target inset from the physical edge. Sideways Traditional Chinese and 130% text now stay camera-like without a fixed selection rectangle turning into a clipped vertical block.
- Added a complete Android 16/API 36 Pixel UI gate alongside the existing API 34 job. Both run the production Simulator path and retain device-frame plus user-view screenshots for fixed camera geometry, system rotation lock, Traditional Chinese, enlarged text, and orientation-aware reading surfaces.
- Added an Android physical-camera validation checklist that exposes only advertised-and-observed features, requires explicit camera-side confirmation, rejects Simulator and Offline Preview sessions, clears confirmations across connections, and copies a privacy-safe Markdown record bound to the sanitized diagnostic by SHA-256.
- Extended the same physical-camera validation workflow to iOS and the PC control UI with memory-only confirmations, native CryptoKit/Web Crypto SHA-256 binding, simulator rejection, localized UI, and browser/iPhone interaction coverage.
- Stabilized the Android rotation-lock launch test by setting and restoring the system auto-rotate preference independently from the display user-rotation lock.

## [0.1.8] - 2026-08-01

- Added capability-gated Android playback for Canon CCAPI RTP `MP4A-LATM/48000` camera audio. Android now scopes SDP format parameters, performs bounded RFC 6416 fragmentation/loss recovery, extracts raw AAC with Media3's AOSP-derived LATM parser, decodes through `MediaCodec`, and streams PCM through `AudioTrack`. Monitoring remains default-muted, stops when the app enters the background, and reports packet/decode/playback health without allowing audio failure to stop video.
- Added capability-gated PC playback for Canon CCAPI RTP `MP4A-LATM/48000` camera audio. The Bridge now parses SDP format parameters, reassembles RFC 6416 audioMuxElements, decodes in-band LATM through PyAV/FFmpeg, exposes bounded authenticated PCM long polling and reports audio status independently from video. The browser remains muted until a user enables audio and tears playback down on every Live View/source/session transition.
- Added capability-gated iOS playback for Canon CCAPI RTP `MP4A-LATM/48000` camera audio. iOS now binds the separately advertised Wi-Fi UDP port, performs bounded RFC 6416 reassembly and AAC-LC LATM extraction, decodes through `AVAudioConverter`, and schedules PCM through `AVAudioEngine`. Monitoring is default-muted, foreground-only, session-scoped, independently diagnosed, and cannot stop ready video.
- Expanded Android's quarter-turn reading viewport and split offline guidance into an atomic icon/title row plus a full-long-axis description row. Bounded font fitting now preserves complete English and Traditional Chinese copy at up to 200% font scale while the camera layout remains fixed.

## [0.1.7] - 2026-07-31

- Added capability-gated Canon Auto Lighting Optimizer control to Android USB/PTP and the libgphoto2 Desktop Bridge. Exact `AloMode (0xD1C1)` UINT32 values are allow-listed from pinned upstream evidence; one-choice R6 Mark III `x3` state remains diagnostic-only, while usable advertised lists receive English and Traditional Chinese UI across Android, iOS, and PC.
- Added capability-gated camera date/time synchronization across Android, iOS, and the Desktop Bridge. Direct CCAPI writes Canon's RFC 1123 value and DST flag, then verifies a GET readback; direct Android USB prefers Canon EOS `UTCTime (0xD17C)` and falls back to `CameraTime (0xD113)`, requiring a matching post-write event; USB Bridge sessions require a writable libgphoto2 `syncdatetimeutc`/`syncdatetime` action paired with its DATE widget and verify a fresh camera-config readback.
- Added English and Traditional Chinese clock controls, success timestamps, diagnostics, simulator state, and deterministic CCAPI/Bridge/libgphoto2 contract coverage.
- Reconciled Android's public auto-rotate setting on start, resume, focus return, every posture sample, and immediately while Quick Settings owns focus; the orientation listener stops entirely while rotation lock is active so no stale sensor callback can rotate camera controls.
- Kept compact HUD atoms in fixed slots, stacked status icons over their exact values, and retained complete viewfinder copy through a centered portrait layout or a quarter-turn inline layout derived from the available Live View long axis. Settings content still remeasures against swapped axes across the full fixed panel.

## [0.1.6] - 2026-07-30

- Made Android camera controls follow the system auto-rotate setting by default, with explicit always-rotate and fixed alternatives.
- Kept the camera composition fixed while atomic controls rotate in place using quarter-turn-aware measurement.
- Preserved the complete English and Traditional Chinese offline preview copy in a bounded, readable sideways viewport.
- Added orientation policy and effective angle to diagnostics, with emulator and Compose coverage for rotation lock, localization, and enlarged text.

## [0.1.5] - 2026-07-30

- Established a stable Android development signing identity for `main` artifacts and tagged releases so previews from `0.1.5` onward can update in place.
- Kept the private key outside Git while pinning and verifying its public SHA-256 certificate fingerprint before every APK upload.
- Preserved ordinary pull-request and local debug builds without exposing release signing secrets.

## [0.1.4] - 2026-07-30

- Added memory-only 3D `.cube` LUT preview to decoded Live View on Android, iOS, and PC, using bounded parsers and platform-native GPU paths without exposing LUT identity in diagnostics.
- Added mutually exclusive luminance histogram and waveform scopes across Android, iOS, and PC.
- Reworked Android camera orientation behavior so the composition remains fixed while bounded controls follow physical orientation only when the Android system auto-rotate setting is enabled.
- Added compact quarter-turn camera HUD content, bounded readable notices, and nested-rotation protection for Traditional Chinese and enlarged text.
- Added a seven-day Android debug APK artifact to successful `main` CI runs for faster physical-camera validation before a tagged release.

## [0.1.0] - 2026-07-26

Initial development preview.

- Added direct Canon CCAPI control on Android, iOS, and the Desktop Bridge.
- Added Android USB/PTP and capability-gated Canon EOS USB control.
- Added JPEG and capability-gated RTP H.264 Live View with adjustable display FPS.
- Added still capture, recording, exposure, white balance, focus, media, and diagnostic workflows where the connected backend advertises support.
- Added English and Traditional Chinese interfaces plus offline UI preview.
- Added a simulator, deterministic cross-platform tests, public protocol references, and pre-push secret scanning.

This preview still requires broader Canon EOS R6 Mark III physical-device validation. iOS is distributed as source; physical-device builds must be built and signed by the developer.

[0.1.0]: https://github.com/js051/open-eos-control/releases/tag/v0.1.0
[0.1.4]: https://github.com/js051/open-eos-control/releases/tag/v0.1.4
[0.1.5]: https://github.com/js051/open-eos-control/releases/tag/v0.1.5
[0.1.6]: https://github.com/js051/open-eos-control/releases/tag/v0.1.6
[0.1.7]: https://github.com/js051/open-eos-control/releases/tag/v0.1.7
[0.1.8]: https://github.com/js051/open-eos-control/releases/tag/v0.1.8
[0.1.9]: https://github.com/js051/open-eos-control/releases/tag/v0.1.9
[0.1.10]: https://github.com/js051/open-eos-control/releases/tag/v0.1.10
[0.2.0]: https://github.com/js051/open-eos-control/releases/tag/v0.2.0
