# EOS R6 Mark III Android CCAPI Validation

- Initial validation date: 2026-07-22
- Latest protocol recheck: 2026-08-12

This record captures physical-camera evidence reported from the Android app. Identifiers are intentionally redacted. It proves only the rows marked **Passed**; deterministic tests remain separate evidence and untested controls remain capability-gated.

## Setup

- Camera: Canon EOS R6 Mark III
- Firmware: `1.1.0`, read directly from the camera on 2026-08-12
- Client: physical Android phone
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
| Multipart JPEG Live View | Camera protocol and integrated PC engine passed; Android device pass pending | The latest developer list advertises a same-version general POST plus multipart GET/DELETE without general DELETE. The camera returned a complete bounded multipart JPEG, and the integrated PC engine completed AUTO fallback and POST-off cleanup. Android uses the same tested lifecycle but still needs an installed-build pass. |
| RTP H.264 Live View | Advertised; camera rejected start in the 2026-08-11 recheck | Direct `POST rtp` returned HTTP 503 `Mode not supported` in both tested movie-mode states. AUTO then reached multipart. This does not prove RTP is universally unavailable; Android still reports bounded UDP/access-unit/keyframe evidence when camera start succeeds. |
| Live View size compatibility fallback | Passed through the integrated PC engine; installed mobile recheck pending | `SMALL` and `MEDIUM` produced complete JPEGs through multipart and polling. Firmware 1.1.0 advertised `LARGE` but rejected its start payload with HTTP 400; all three clients now try the no-size compatibility form, then downgrade `LARGE` to `MEDIUM`/`SMALL`, report the effective size, and remove the rejected size for that session. |
| CCAPI event polling and body-dial synchronization | Protocol lifecycle passed through the integrated PC engine; client refresh UI recheck pending | The first long poll returned bounded change keys. A subsequent long poll remained pending, advertised DELETE stopped it successfully, and the request completed with an empty event. A reversible beep change was restored exactly. |
| Still capture and half-press | Still capture intentionally not tested; half-press reached the camera but did not complete AF | No file-producing command was sent. Timed half-press returned Canon `AF NG` for the current scene and still executed the guaranteed release, so this is not recorded as a successful half-press result. |
| Movie start/stop, Tap AF, and Click White Balance | Tap AF passed through the integrated PC engine; other writes intentionally not tested | Center Tap AF succeeded after reading geometry from the exact Canon `flipdetail?kind=both` query. Firmware 1.1.0 rejects an additional `&t=` query item. Recording and Click WB were skipped because they create media or alter calibration state. |
| Storage, media browser and download | Passed through the integrated PC engine; installed mobile recheck pending | Storage, 500 bounded media entries, one item's metadata, JPEG thumbnail and display preview, and a 9,313,518-byte in-memory download all succeeded. The received download length exactly matched metadata; no file, filename, timestamp, image or hash was retained. |
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
- All 28 multi-choice settings tested by the harness accepted an adjacent advertised value and exact restoration. This included exposure, WB, AF, drive, metering, quality, bracketing, flash, picture style, shutter mode, aspect ratio, color space, tracking, and movie-mode context.
- Three AUTO starts all selected multipart after the known RTP rejection and returned complete medium JPEGs. Three explicit polling starts also returned complete medium JPEGs. Five additional immediate multipart stop/start cycles all returned complete frames.
- Multipart and polling both returned complete `SMALL` and `MEDIUM` JPEGs. `LARGE` was advertised but both start paths returned HTTP 400 `Invalid parameter`; the corrected engine downgraded it to `MEDIUM`, exposed that effective size, and removed `LARGE` from the session capability list.
- Requested multipart rates of 6, 15, and 30 produced rolling observed rates of 6.0, 14.9, and 19.9 FPS. The 30 FPS control is retained, while requested and observed values remain separate because this camera/network combination delivered about 20 FPS in this pass.
- Autofocus and balanced near/far small focus-drive commands passed and Live View recovered after each. Center Tap AF passed once the detailed geometry request omitted the firmware-rejected cache query. Half-press returned `AF NG` for the scene and was released; it remains unconfirmed rather than reported as success.
- Event long-poll start/stop, storage/media traversal, metadata, thumbnail, display preview, and bounded full-file streaming passed. The thumbnail contained a decodable 160x120 JPEG followed by camera-supplied trailing bytes; strict Pillow verification and decode passed, so the response was treated as valid rather than requiring EOI at the final response byte.
- Camera RTP state and general Live View were confirmed stopped at cleanup. RTP start remains advertised but rejected with HTTP 503 `Mode not supported` on this body/firmware state.

The Android and iOS implementations now carry deterministic tests for the exact `flipdetail` query, advertised-size pruning, start-payload downgrade, and bounded multipart-not-ready retry. They still require installed-device passes before their rows can be marked physically passed.

## Next Physical Pass

1. Install a development build containing the Android RTP readiness fix. Keep cellular data enabled while connected to the camera Wi-Fi, press Debug Refresh, and confirm `cameraRoute=WIFI_BOUND`, `cameraNetworkAvailable=true`, `systemDefaultTransport=CELLULAR`, `systemDefaultValidated=true`, and `wifiCellularCoexistence=true`. Open a public HTTPS page in another app without disconnecting the camera and record the result.
2. Install a current Android build and verify the already proven protocol paths through the App UI: event-driven exposure refresh, AUTO multipart, exact effective size after `LARGE` fallback, 6/15/30 requested versus observed FPS, center Tap AF, storage list, thumbnail, preview, bounded download, reconnect, and stale-event isolation. Require matching `observedFeatures` before marking an App row passed.
3. On iPhone/iPad, repeat the direct CCAPI Live View, event, focus, and media read-only pass after the macOS package/App CI is green. RTP remains a separate test because this camera currently rejects start before UDP delivery.
4. Test still capture, recording, Click WB, metadata writes, clock synchronization, directory/file naming changes, sensor cleaning and sleep only in an explicitly approved state-changing pass with suitable media and restore procedures.
5. Connect over USB-C/OTG and complete the checklist in [Android USB/PTP](../android-usb-ptp.md).
