# EOS R6 Mark III Android CCAPI Validation

Validation date: 2026-07-22

This record captures physical-camera evidence reported from the Android app. Identifiers are intentionally redacted. It proves only the rows marked **Passed**; deterministic tests remain separate evidence and untested controls remain capability-gated.

## Setup

- Camera: Canon EOS R6 Mark III
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
| RTP H.264 Live View | Pending / not advertised in captured report | The recorded `supported` set contains JPEG polling but no RTP. A current build will expose RTP only if fresh discovery includes `GET rtpsessiondesc` and `POST rtp`; absence of those commands is a camera capability result, not a decoder failure. |
| Live View size compatibility fallback | Passed | Initial POST with `liveviewsize` returned HTTP 400 `Invalid parameter`; retrying the camera-display-only payload restored Live View |
| Still capture and half-press | Pending | The client did not expose these capabilities in this older report. Current clients now parse Canon's full same-origin discovery `url` entries, but this camera still needs a fresh capability report and command result |
| Movie start/stop, Tap AF, and Click White Balance | Pending | No recorded physical result yet |
| Storage, media browser and download | Pending | Storage was `null` in the captured report; re-test after the canonical discovery `url` parser fix |
| Camera Wi-Fi plus cellular internet | Pending re-test | Android now binds camera HTTP sockets to the Wi-Fi route without process-wide binding; a post-change physical result is still required |
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

This report predates `capabilitySource`, `protocolVersions`, `advertisedCommands`, `writableSettings`, and the success-only `observedFeatures` session evidence. It also predates the cross-platform fix that accepts Canon's full same-origin `url` discovery entries instead of reading only relative `path` fixtures. A fresh report is required before concluding that the camera itself omitted shutter, recording, focus, storage, or media operations.

## Next Physical Pass

1. Keep cellular data enabled while connected to the camera Wi-Fi, press Debug Refresh, and confirm `cameraRoute=WIFI_BOUND`, `cameraNetworkAvailable=true`, `systemDefaultTransport=CELLULAR`, `systemDefaultValidated=true`, and `wifiCellularCoexistence=true`. Then open a public HTTPS page in another app without disconnecting the camera and record the result; `cellularAvailable` alone is not acceptance evidence.
2. Install a current build, exercise each available still, half-press, movie, Tap AF, Click White Balance, storage, browse and download control, and only then copy a fresh diagnostic report. Retain `reportSchema`, `generatedAt`, `productVersion`, `capabilitySource`, `protocolVersions`, `advertisedCommandCount`, `advertisedCommands`, `writableSettings`, `observedFeatures`, `unverifiedAdvertisedFeatures`, and `capabilityEvidenceTruncated`. The report now redacts the physical camera serial automatically. Treat `supported` as advertisement evidence and require the matching `observedFeatures` entry before marking a physical control as passed.
3. Check the fresh `advertisedCommands` for `GET .../shooting/liveview/rtpsessiondesc` and `POST .../shooting/liveview/rtp`. If both exist, switch the Live View source to RTP H.264, test 6/15/30 FPS render caps, rotate portrait/landscape, background/restore the app, and record `liveViewSource`, `observedFps`, `contentType=video/H264`, the `rtp://` source and any decoder error. If either command is absent, record RTP as unsupported by this camera/API version and keep JPEG polling.
4. Connect over USB-C/OTG and complete the checklist in [Android USB/PTP](../android-usb-ptp.md).
