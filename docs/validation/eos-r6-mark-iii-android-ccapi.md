# EOS R6 Mark III Android CCAPI Validation

- Initial validation date: 2026-07-22
- Latest protocol recheck: 2026-08-13

This record captures physical-camera evidence reported from the Android app. Identifiers are intentionally redacted. It proves only the rows marked **Passed**; deterministic tests remain separate evidence and untested controls remain capability-gated.

## Setup

- Camera: Canon EOS R6 Mark III
- Firmware: `1.1.0`, read directly from the camera on 2026-08-12
- Clients: physical Android phone for the original diagnostics; production Android APK on a Pixel 6 Pro API 34 AVD routed through the host for the 2026-08-12 installed-App pass
- Transport: direct camera Wi-Fi and CCAPI HTTP
- Camera origin: `http://192.168.1.2:8080`
- API discovery: `/ccapi`, with `ver100` selected by the client

## Recorded Results

| Capability | Result | Evidence |
| --- | --- | --- |
| Discovery and identity | Passed | Camera model and API profile returned; serial was present but is redacted from this record |
| Battery | Passed | Camera returned LP-E6P, `quality=good`, `level=full` |
| Exposure and writable settings | Passed through the integrated PC engine; installed Android recheck pending | 28 advertised choice settings were changed to an adjacent advertised value, read back, restored, and read back again; all 28 passed and all 28 original values were restored |
| JPEG Live View | Passed | Requested 15 FPS, rolling observed 15.1 FPS, JPEG content type, 66,086-byte recorded frame |
| Multipart JPEG Live View | Passed in the installed Android App through AVD-to-camera routing; physical-phone repeat pending | The latest developer list advertises a same-version general POST plus multipart GET/DELETE without general DELETE. The installed App selected multipart, returned current JPEG frames, reported 6.0 observed FPS, restarted cleanly, and completed disconnect cleanup. |
| RTP H.264 Live View | Advertised; camera rejected start in the 2026-08-11 recheck | Direct `POST rtp` returned HTTP 503 `Mode not supported` in both tested movie-mode states. AUTO then reached multipart. This does not prove RTP is universally unavailable; Android still reports bounded UDP/access-unit/keyframe evidence when camera start succeeds. |
| Live View size compatibility fallback | Passed through the integrated PC engine and installed Android App | `SMALL` and `MEDIUM` produced complete JPEGs through multipart and polling. Firmware 1.1.0 advertised `LARGE` but rejected its start payload with HTTP 400. The installed App downgraded without surfacing an error, removed `LARGE` for that session, then switched between `SMALL` and `MEDIUM` while maintaining 6.0 observed FPS. |
| CCAPI event polling and body-dial synchronization | Protocol lifecycle passed through the integrated PC engine; client refresh UI recheck pending | The first long poll returned bounded change keys. A subsequent long poll remained pending, advertised DELETE stopped it successfully, and the request completed with an empty event. A reversible beep change was restored exactly. |
| Still capture and half-press | Still capture intentionally not tested; half-press reached the camera but did not complete AF | No file-producing command was sent. Timed half-press returned Canon `AF NG` for the current scene and still executed the guaranteed release, so this is not recorded as a successful half-press result. |
| Movie start/stop, Tap AF, and Click White Balance | Tap AF passed through the integrated PC engine; other writes intentionally not tested | Center Tap AF succeeded after reading geometry from the exact Canon `flipdetail?kind=both` query. Firmware 1.1.0 rejects an additional `&t=` query item. Recording and Click WB were skipped because they create media or alter calibration state. |
| Storage, media browser and download | Browser, thumbnail, display preview and metadata passed in the installed Android App; full download passed through the integrated PC engine | The App loaded the bounded 500-item list, displayed thumbnails, opened the first item's display preview, and read protection/archive/rating/rotation metadata without invoking a write. The engine's 9,313,518-byte in-memory download exactly matched metadata; no file, image or hash was retained. |
| Camera Wi-Fi plus cellular internet | Passed by routing diagnostics; public-app confirmation pending | The 2026-08-03 report shows camera HTTP bound to `wlan0` while validated cellular remained the validated system default and `wifiCellularCoexistence=true` |
| Android USB/PTP | Pending | No USB validation is represented by this CCAPI record |

## Sanitized Diagnostic Excerpt

```text
camera=Canon EOS R6 Mark III
serial=[redacted]
transport=CCAPI_NETWORK
baseUrl=http://192.168.1.2:8080
supported=ADVANCED_SETTINGS, BATTERY_STATUS, CAMERA_IDENTITY, EXPOSURE_CONTROL, LIVE_VIEW, LIVE_VIEW_JPEG_POLLING, WHITE_BALANCE_CONTROL
battery={"kind":"battery","name":"LP-E6P","quality":"good","level":"full"}
storage=null
requestedFps=15
observedFps=15.1
frameBytes=66086
contentType=image/jpeg
source=http://192.168.1.2:8080/ccapi/ver100/shooting/liveview/flip?t=[redacted]
lastError=HTTP 400 Invalid parameter from the original Live View start payload
```

The later successful frame report confirms the compatibility retry fixed the Live View failure. It does not prove operations absent from the camera's advertised capability set.

This report predates `capabilitySource`, `protocolVersions`, `advertisedCommands`, `writableSettings`, and the success-only `observedFeatures` session evidence. It also predates the cross-platform fixes that accept Canon's full same-origin `url` discovery entries and request `/ccapi/ver100/topurlfordev` when the root returns the exact `No list of APIs` marker. A fresh report is required before concluding that the camera itself omitted shutter, recording, focus, storage, or media operations.

## 2026-08-03 Firmware-Updated Diagnostic

The Android `0.1.0` report after the firmware update proves that camera traffic was correctly bound to Wi-Fi while validated cellular remained the system default. It also proves the discovery parser retained `ver100` but produced zero advertised commands. Consequently only successfully observed identity and battery remained supported; Live View and every command capability stayed disabled. This is a safe failure, but not a usable session.

```text
productVersion=0.1.0
camera=Canon EOS R6 Mark III
transport=CCAPI_NETWORK
cameraRoute=WIFI_BOUND
cameraInterface=wlan0
cameraNetworkAvailable=true
cellularAvailable=true
cellularValidated=true
systemDefaultTransport=CELLULAR
systemDefaultValidated=true
wifiCellularCoexistence=true
capabilitySource=GET /ccapi
protocolVersions=ver100
advertisedCommandCount=0
advertisedCommands=none
supported=BATTERY_STATUS, CAMERA_IDENTITY
observedFeatures=BATTERY_STATUS, CAMERA_IDENTITY
liveViewHealthy=false
lastError=none
```

The report does not retain the raw `/ccapi` body, so it cannot prove which specific JSON key changed. It does prove the semantic condition that matters: a protocol version was present but no valid command survived parsing. Current Android, iOS and PC clients now query Canon's documented `/ccapi/ver100/topurlfordev` for both the exact `No list of APIs` marker and this zero-command condition. The returned list still passes the existing same-origin, path-traversal and HTTP-method validation; an empty or failed fallback creates no control capability.

The network-coexistence route is now **Passed by diagnostics**. A public HTTPS request in another app while camera control remains active is still useful end-user confirmation, but this failure was discovery-related rather than caused by Android selecting cellular for camera traffic.

## 2026-08-10 RTP Follow-Up

Android 0.1.10 successfully recovered the full developer operation list and the R6 Mark III advertised Canon RTP. However, the selected RTP source never produced a frame and the old Android receiver had no first-video timeout, so the session remained silent instead of failing or allowing AUTO to continue to another complete source.

```text
productVersion=0.1.10
camera=Canon EOS R6 Mark III
transport=CCAPI_NETWORK
cameraRoute=WIFI_BOUND
cameraInterface=wlan0
cameraNetworkAvailable=true
systemDefaultTransport=CELLULAR
systemDefaultValidated=true
wifiCellularCoexistence=true
capabilitySource=GET /ccapi/ver100/topurlfordev (Canon developer API fallback)
protocolVersions=ver140, ver120, ver110, ver100
advertisedCommandCount=246
supported=... LIVE_VIEW, LIVE_VIEW_RTP ...
liveViewSource=CCAPI_RTP
observedFps=0.0
frameBytes=unknown
contentType=unknown
source=unknown
lastFrameAtMillis=unknown
liveViewHealthy=false
lastError=none
```

This record proves advertisement and the silent-start defect; it does not prove whether the camera sent no UDP, Android rejected the packets, or decoding lacked parameter sets. The corrected diagnostic adds RTP video port, datagram, access-unit, keyframe, SPS/PPS, readiness, timestamp and error fields so the next physical run can distinguish those cases without retaining identifiers.

## 2026-08-11 Current-Firmware Protocol Recheck

The camera was connected to the PC only through its Wi-Fi access point; no USB transport participated. This was a camera-protocol and integrated-PC-engine check, not an installed Android App pass. No serial, SSID or credentials were retained.

- Canon's developer-list fallback returned `ver140`, `ver120`, `ver110` and `ver100` with 246 validated operations.
- RTP session description advertised H.264/90 kHz video on UDP and AAC LATM audio, but the camera returned HTTP 503 `Mode not supported` to RTP start in both tested movie-mode states. The stop action was still sent.
- General Live View was advertised with POST but without a matching DELETE. Canon's documented POST body with `liveviewsize=off` and `cameradisplay=on` returned success and reliably ended the session.
- Small JPEG polling returned a complete JPEG. Medium initially produced a 200 start followed by a 503 frame response, while a later integrated-engine run returned a complete medium JPEG. This is treated as a runtime compatibility condition rather than a fixed model limit.
- Multipart GET returned `multipart/x-mixed-replace` and a complete JPEG. Its DELETE may return 503 after the stream closes; the required general POST-off still succeeds.
- The integrated PC engine advertised RTP, multipart and JPEG, then AUTO selected multipart after RTP rejection, returned a complete JPEG and stopped cleanly. A separate explicit JPEG run returned a complete medium frame.

These results justify the POST-only lifecycle and conditional size fallback now shared by Android, iOS and PC. They do not mark Android/iOS RTP or sustained 6/15/30 FPS as physically passed.

## 2026-08-12 Non-Destructive Physical Pass

The same camera remained connected only by Wi-Fi. This pass exercised read-only commands and reversible writes through the production `CcapiEngine`; no capture, recording, bulb exposure, media mutation/deletion, directory creation, sensor cleaning, clock synchronization, sleep, or power command was sent.

- Identity/status returned firmware `1.1.0`, a mounted RF24-105mm lens, one available storage device, battery 92%, recordable shots, and normal temperature. The developer fallback returned four protocol versions, 246 validated commands, and 43 writable setting keys.
- All 118 advertised GET resources were rechecked without retaining payloads. Ninety-five returned HTTP 200; twenty-one returned only the expected current-state `Mode not supported` or `Live view not started`; the two event resources were handled separately with bounded GET/DELETE lifecycles. No unknown HTTP or transport failure occurred. The advertised power-zoom resources reported `equip=false`, `moving=false`, and `value=stop`, so `ZOOM_CONTROL` correctly remained planned for the attached non-power-zoom lens.
- The current capability planner returned 38 supported and 8 planned features across its 46 direct-Bridge candidates. The sets were complete and disjoint, and the unimplemented `MEDIA_UPLOAD` remained planned instead of disappearing from diagnostics.
- All 28 multi-choice settings tested by the harness accepted an adjacent advertised value and exact restoration. This included exposure, WB, AF, drive, metering, quality, bracketing, flash, picture style, shutter mode, aspect ratio, color space, tracking, and movie-mode context.
- Three AUTO starts all selected multipart after the known RTP rejection and returned complete medium JPEGs. Three explicit polling starts also returned complete medium JPEGs. Five additional immediate multipart stop/start cycles all returned complete frames.
- With a bounded explicit Live View session, `flip` returned a complete JPEG and `flipdetail`, `scroll`, `scrolldetail`, and multipart returned their advertised binary streams. The scroll responses exceeded the 512 KiB inspection cap and were closed without persistence; general POST-off still completed cleanup. Because the body does not advertise `/shooting/settings/lvzoom`, these data streams do not by themselves create a magnification control.
- Multipart and polling both returned complete `SMALL` and `MEDIUM` JPEGs. `LARGE` was advertised but both start paths returned HTTP 400 `Invalid parameter`; the corrected engine downgraded it to `MEDIUM`, exposed that effective size, and removed `LARGE` from the session capability list.
- Requested multipart rates of 6, 15, and 30 produced rolling observed rates of 6.0, 14.9, and 19.9 FPS. The 30 FPS control is retained, while requested and observed values remain separate because this camera/network combination delivered about 20 FPS in this pass.
- A sustained JPEG-polling repeat produced 6.0, 14.9, and 23.9 FPS for the same requests. At 30 FPS the camera can transiently return HTTP 503 `Device busy` before its next frame is ready. Android, iOS and PC now retry only that exact response after bounded 50 ms and 100 ms delays; the physical PC rerun continued instead of ending Live View. Other HTTP failures, malformed images, cancellation and `Mode not supported` remain immediate failures.
- Autofocus and balanced near/far small focus-drive commands passed and Live View recovered after each. Center Tap AF passed once the detailed geometry request omitted the firmware-rejected cache query. Half-press returned `AF NG` for the scene and was released; it remains unconfirmed rather than reported as success.
- Event long-poll start/stop, storage/media traversal, metadata, thumbnail, display preview, and bounded full-file streaming passed. The bounded list contained 487 images and 13 videos; one 8,776,167-byte image streamed into memory with an exact declared/received byte match and was not retained. After a poll had already completed, Canon returned HTTP 503 `Not started` to a repeated event DELETE; the corrected engine treats only this explicit already-stopped response as idempotent success, while other stop failures remain errors. Two consecutive idle stops then passed against the camera. The thumbnail contained a decodable 160x120 JPEG followed by camera-supplied trailing bytes; strict Pillow verification and decode passed, so the response was treated as valid rather than requiring EOI at the final response byte.
- Movie mode switched on and restored without recording. The production Bridge exposed and reversibly exercised movie quality, movie cropping, five camera-advertised `raw`/XF-HEVC S/XF-AVC S movie formats, overall sound recording, internal-microphone Auto/Manual mode, and the internal wind filter. Switching the internal microphone to Manual dynamically exposed a 0-63 level range; an adjacent level write and exact restore passed. External/accessory controls with one-value abilities stayed hidden, while absent-source and independently advertised resources returning HTTP 503 `Mode not supported` remained unavailable rather than becoming fake controls. The movie-quality tokens still preserve their camera-reported frame-rate component.
- Camera RTP state and general Live View were confirmed stopped at cleanup. RTP start remains advertised but rejected with HTTP 503 `Mode not supported` on this body/firmware state.

The Android and iOS implementations now carry deterministic tests for the exact `flipdetail` query, advertised-size pruning, start-payload downgrade, and bounded multipart-not-ready retry. Android now also has the installed AVD-to-camera evidence below; a physical-phone repeat and an installed iOS pass remain pending.

## 2026-08-12 Installed Android App Pass

A production-path debug APK was installed on a Pixel 6 Pro API 34 AVD. The emulator reached the physical R6 Mark III through the Windows host's active camera Wi-Fi route. This validates the Android App, repository, CCAPI backend, decoding and Compose state path against a real camera; it does not replace a physical-phone validation of Android Wi-Fi/cellular binding or USB host behavior.

- Connection returned the physical camera identity, 92% battery, 9,999 recordable shots, RF lens/status data, exposure values, four protocol versions and 246 validated advertised operations.
- AUTO selected Canon multipart JPEG. Debug reported `image/jpeg`, current frame timestamps, nonzero frame bytes, and 5.5-6.0 observed FPS for a 6 FPS request.
- The App's FPS control produced 14.9 observed FPS for a 15 FPS request. A 30 FPS request remained distinct from throughput and settled between roughly 14.7 and 20.0 FPS during this AVD route; no 400/503 was returned.
- Selecting the firmware-advertised `LARGE` size exposed a real Android defect: closing OkHttp's streaming response on the main thread raised `NetworkOnMainThreadException`, aborted restart, and caused repeated reads from a closed multipart session. The corrected build closes the response on `Dispatchers.IO` and stops the old frame loop before restart.
- Repeating `LARGE` on the corrected build triggered the expected camera compatibility downgrade, removed only `LARGE` for the session, maintained 6.0 observed FPS, and left no Live View error. Subsequent `SMALL`, `MEDIUM`, explicit Restart Live View and Disconnect operations all completed without closed-stream retries.
- Firmware 1.1.0 reported 47 photo pages but rejected `order=desc` with HTTP 400 `Illegal query parameter`; plain pages were ordered oldest to newest. The validated v0.2.0 build detected that response once, read from the final page backward, reversed each plain page, and stopped at its then-current 500-item total bound. It fairly merged sibling containers so a full photo container could not hide videos. The physical list completed in 9.4 seconds with 487 recent photos and all 13 videos, rendered thumbnails, opened a full photo display preview, and loaded video metadata. Current builds remove that 500-item bound, traverse every reported page, and publish partial results as pages arrive. No download, metadata write, capture, recording, deletion or other media mutation was invoked during this pass.

No camera identifier, media image or media filename is retained in this repository; the example filename above is intentionally omitted from the validation record.

## 2026-08-13 v0.2.0 Post-Release Recheck

The physical camera was connected to the PC only through its Wi-Fi access point. This pass used the released `0.2.0` production `CcapiEngine`; no Android device was visible to ADB, so these results do not close the physical-phone validation item. No serial, SSID, credential, media filename, media bytes or image was retained.

- Discovery again required Canon's developer-list fallback and returned firmware `1.1.0`, four protocol versions, 246 validated commands, 42 writable setting keys, 38 supported features and 8 planned features. Status reported one storage device, a mounted lens and normal temperature.
- AUTO Live View rejected RTP with HTTP 503 `Mode not supported`, cleaned it up and selected multipart. Explicit multipart and JPEG polling returned complete in-memory JPEGs at `SMALL` and `MEDIUM`. The camera again rejected advertised `LARGE`; both sources downgraded it to effective `MEDIUM` without leaving Live View active.
- Requested 6 and 15 FPS produced 6.0 and 15.0 FPS through multipart, and 6.1 and 14.6 FPS through polling. A 30 FPS request produced 16.2 FPS through multipart and 14.2 FPS through polling in this short pass. Requested FPS therefore remains a cap rather than a throughput promise.
- Independent AF, balanced Near Small/Far Small focus drive and center Tap AF all succeeded. A following frame proved Live View recovered after those commands. Timed half-press again returned Canon HTTP 503 `AF NG`; the guaranteed release path still ran, so this is recorded as unconfirmed autofocus for the current scene rather than a stuck shutter or successful half-press.
- The bounded media list returned 500 items: 487 images and 13 videos. One anonymous JPEG passed fresh information, decoded 160x120 thumbnail, decoded 1620x1080 display preview and exact streamed download checks. The declared and received download lengths were both 8,776,167 bytes across 134 chunks, and the stream was discarded without persistence.
- Event long polling returned 64 bounded structural change keys. Explicit stop, a repeated already-stopped stop and session close all completed successfully. Final Live View state was inactive.
- No still capture, recording, Bulb exposure, Click White Balance, media mutation/deletion, setting write, clock synchronization, directory/file naming write, sensor cleaning, sleep or power command was sent.

## 2026-08-13 Physical Android Phone Pass

The released `0.2.0` APK was verified against its published SHA-256 and pinned single-signer certificate, installed on a physical Android 14 phone, and connected directly to the same R6 Mark III over camera Wi-Fi. Device identifiers, camera identifiers, SSID, addresses, network handles, media filenames, media bytes and screenshots were not retained in the repository. The intended scope was non-destructive controls and read-only media operations; the coordinate-calibration exception is recorded below.

- The installed package reported version `0.2.0` (`versionCode=12`). The Traditional Chinese connection and control screens respected status/navigation safe areas. Direct connection returned the camera model, battery, remaining shots and current exposure values, and AUTO Live View selected multipart JPEG without an HTTP or application error.
- Five frames sampled 700 ms apart changed across roughly 27% of the central preview pixels from one sample to the next. The nearly black preview was therefore a live, underexposed scene rather than a frozen or missing frame.
- AF-ON, Near Small, Far Small and center Tap AF completed from the production Android UI. The application process remained alive, no HTTP/API exception was logged, and Live View continued after each operation. No still capture or recording was invoked.
- While calibrating ADB coordinates, the App's tap-action mode was temporarily left on Click WB and one Live View tap was sent before the mismatch was detected. The camera continued to report WB type `colortemp` and no API error, but the pre/post Kelvin value was not captured, so this pass does not claim that the underlying color-temperature value was unchanged. Tap action was then explicitly restored to Tap AF before the verified focus pass.
- The Live View control reported 5.9 observed FPS for a 6 FPS request and 14.4 observed FPS for a 15 FPS request. A 30 FPS request remained separately visible and settled at 17.4 observed FPS during this phone route. No HTTP 400/503 occurred while changing rates, and restoring the request to 6 FPS returned the observed rate to 5.9 FPS.
- The language picker switched the active control screen to English and then back to Traditional Chinese through `Automatic (system)`, confirming both explicit English and system-following localization on the installed App.
- The read-only media screen returned its bounded 500-item mixed image/video list, rendered thumbnails and decoded a full display preview on the phone. Download, rating, rotation, protection and deletion actions were not invoked.
- Five orientation-policy instrumentation tests passed on the physical phone using a debug/test pair built from Android sources identical to `v0.2.0`. They verified that enabling system auto-rotation rotates only opted-in controls, disabling it stops the orientation listener and resets control rotation, stale/legacy App preferences cannot override the system setting, Quick Settings changes apply without waiting for focus to return, and launching the fixed camera layout preserves an existing rotation lock. The original `accelerometer_rotation=0` and `user_rotation=0` values were restored, the test package was removed, and the hash-verified released `0.2.0` APK was reinstalled.
- The camera route used Wi-Fi and remained reachable. In the final network snapshot the phone's mobile-data setting was enabled, but Android exposed no cellular network agent, selected the unvalidated camera Wi-Fi as the system default, and could not reach a public Internet probe. Wi-Fi/cellular coexistence therefore remains unconfirmed for this carrier/device state rather than being reported as an App failure or a passing result.

## Next Physical Pass

1. Repeat the phone network pass when Android exposes an active, validated cellular network agent: press Debug Refresh, confirm `cameraRoute=WIFI_BOUND`, `systemDefaultTransport=CELLULAR`, `systemDefaultValidated=true`, and `wifiCellularCoexistence=true`, then open a public HTTPS page in another App without disconnecting the camera.
2. Complete the remaining physical-phone Android paths: event-driven exposure refresh, exact effective size after `LARGE` fallback, bounded download, reconnect/stale-event isolation, and a redacted copied diagnostic report. Require matching `observedFeatures` before marking those rows passed.
3. On iPhone/iPad, repeat the direct CCAPI Live View, event, focus, and media read-only pass after the macOS package/App CI is green. RTP remains a separate test because this camera currently rejects start before UDP delivery.
4. Test still capture, recording, Click WB, metadata writes, clock synchronization, directory/file naming changes, sensor cleaning and sleep only in an explicitly approved state-changing pass with suitable media and restore procedures.
5. Connect over USB-C/OTG and complete the checklist in [Android USB/PTP](../android-usb-ptp.md).
