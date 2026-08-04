# EOS R6 Mark III Android CCAPI Validation

- Initial validation date: 2026-07-22
- Latest diagnostic date: 2026-08-03

This record captures physical-camera evidence reported from the Android app. Identifiers are intentionally redacted. It proves only the rows marked **Passed**; deterministic tests remain separate evidence and untested controls remain capability-gated.

## Setup

- Camera: Canon EOS R6 Mark III
- Firmware: reported as updated to the latest available release; Canon's release current on the report date was 1.1.0, which added EDSDK/CCAPI, but the camera's numeric value was not captured and is not treated as proven device evidence
- Client: physical Android phone
- Transport: direct camera Wi-Fi and CCAPI HTTP
- Camera origin: `http://192.168.1.2:8080`
- API discovery: `/ccapi`, with `ver100` selected by the client

## Recorded Results

| Capability | Result | Evidence |
| --- | --- | --- |
| Discovery and identity | Passed | Camera model and API profile returned; serial was present but is redacted from this record |
| Battery | Passed | Camera returned LP-E6P, `quality=good`, `level=full` |
| Exposure capability discovery | Passed | ISO, Tv, Av, WB and advanced settings were advertised by the camera |
| JPEG Live View | Passed | Requested 15 FPS, rolling observed 15.1 FPS, JPEG content type, 66,086-byte recorded frame |
| Multipart JPEG Live View | Pending / not captured | A current build requires same-version regular Live View POST/DELETE plus multipart GET/DELETE advertisements. It marks the source observed only after parsing a complete bounded JPEG frame. R6 Mark III firmware 1.1.0 must be checked through fresh developer-list discovery rather than inferred from another EOS model. |
| RTP H.264 Live View | Pending / not advertised in captured report | The recorded `supported` set contains JPEG polling but no RTP. A current build will expose RTP only if fresh discovery includes `GET rtpsessiondesc` and `POST rtp`; absence of those commands is a camera capability result, not a decoder failure. |
| Live View size compatibility fallback | Passed | Initial POST with `liveviewsize` returned HTTP 400 `Invalid parameter`; retrying the camera-display-only payload restored Live View |
| CCAPI event polling and body-dial synchronization | Pending | Current clients require advertised GET and DELETE `/event/polling`, use Canon's version-specific long mode, and refresh authoritative state after a change. The recorded report predates this implementation and does not prove the R6 Mark III endpoint or behavior. |
| Still capture and half-press | Pending | The client did not expose these capabilities in either report. Current clients parse Canon's full same-origin discovery entries and query `topurlfordev` after either the exact marker or a zero-command root, but this camera still needs a fresh capability report and command result |
| Movie start/stop, Tap AF, and Click White Balance | Pending | No recorded physical result yet |
| Storage, media browser and download | Pending | Storage was `null` in the captured report; re-test after the canonical discovery `url` parser fix |
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

## Next Physical Pass

1. Install a development build produced after the zero-command developer-list fallback; the numeric app version remains `0.1.8` until the next release. Keep cellular data enabled while connected to the camera Wi-Fi, press Debug Refresh, and confirm `cameraRoute=WIFI_BOUND`, `cameraNetworkAvailable=true`, `systemDefaultTransport=CELLULAR`, `systemDefaultValidated=true`, and `wifiCellularCoexistence=true`. Open a public HTTPS page in another app without disconnecting the camera and record the result.
2. Install a current build and confirm discovery contains both `GET` and `DELETE .../event/polling`. Change ISO, Tv, Av, WB and shooting mode from the camera body without pressing App Refresh; each displayed value must converge to the camera state after one event. Start/stop recording on the body when allowed, remove/reinsert a card, disconnect, reconnect, and confirm no stale poll updates the new session. Require `EVENT_POLLING` in both `supported` and `observedFeatures` before marking this row passed.
3. Confirm `capabilitySource=GET /ccapi/ver100/topurlfordev (Canon developer API fallback)`, `advertisedCommandCount` is greater than zero, and the expected commands appear explicitly. Exercise each available still, half-press, movie, Tap AF, Click White Balance, storage, browse and download control, and only then copy a fresh diagnostic report. Retain `reportSchema`, `generatedAt`, `productVersion`, `capabilitySource`, `protocolVersions`, `advertisedCommandCount`, `advertisedCommands`, `writableSettings`, `observedFeatures`, `unverifiedAdvertisedFeatures`, and `capabilityEvidenceTruncated`. The report redacts the physical camera serial automatically. Treat `supported` as advertisement evidence and require the matching `observedFeatures` entry before marking a physical control as passed.
4. Check each API version independently for regular Live View POST/DELETE, multipart GET/DELETE, and RTP session-description/control pairs. When multipart is fully advertised in one version, run AUTO and explicit multipart at 6/15/30 FPS, verify `LIVE_VIEW_MULTIPART` is supported before start but appears in `observedFeatures` only after a complete frame, then confirm stop closes the local reader, deletes multipart with HTTP 200, and deletes regular Live View. Test RTP similarly when its pair is advertised. Record the effective `liveViewSource`, requested/observed FPS, content type, source path and any fallback error; absent pairs are unsupported for that camera/firmware rather than guessed from model name.
5. Connect over USB-C/OTG and complete the checklist in [Android USB/PTP](../android-usb-ptp.md).
