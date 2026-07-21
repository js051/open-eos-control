# Feature Status And Acceptance

Last audited: 2026-07-22.

This is the canonical completeness ledger for Open EOS Control. A UI control, interface method, or simulator response alone does not make a feature complete.

## Status Rules

- **Implemented**: an executable product path and automated coverage both exist.
- **Device validation**: implemented and test-covered, but still needs a recorded result from the named physical camera.
- **Planned**: architecture or interface only; users must not be shown an active product control.
- **Research**: public platform or protocol evidence is not yet sufficient for a product claim.

## Android And Canon CCAPI

| Capability | Code status | EOS R6 Mark III | Acceptance evidence |
| --- | --- | --- | --- |
| Discovery and API versions | Implemented | Device validation passed | `CcapiClientTest`; [sanitized physical-camera record](validation/eos-r6-mark-iii-android-ccapi.md) |
| Capability evidence diagnostics | Implemented | Fresh device report required | Android Debug/report exposes bounded discovery source, versions, advertised method/path pairs and writable setting keys without URL queries or credentials |
| Identity, battery, storage | Implemented | Identity and battery passed; storage pending | Unit tests plus diagnostic raw JSON |
| ISO, Tv, Av, WB | Implemented | Device validation passed | Advertised setting paths, unit tests, physical camera control |
| Dynamic camera settings | Implemented | Device validation in progress | Values come from camera `ability`; controls require an advertised setting-specific `PUT` path |
| JPEG Live View | Implemented | Device validation passed at requested 15 FPS | Frame parser tests and [physical rolling-FPS record](validation/eos-r6-mark-iii-android-ccapi.md) |
| Still capture | Implemented | Device validation required | Direct shutter requires advertised POST; manual PUT/POST flow always releases |
| Shutter half-press | Implemented | Device validation required | Advertised manual shutter endpoint, timed half-press, guaranteed release, unit tests |
| Movie start/stop | Implemented | Device validation required | Control is enabled only when `recbutton` is advertised |
| Tap focus | Implemented when advertised | Device validation required | `afpoint` must be advertised; failures are not reported as success |
| Media browser | Implemented | Device validation required | Bounded storage-tree and page traversal; same-origin path validation; unit tests |
| Media download | Implemented as a streaming transfer | Device validation required | SAF destination, same-origin stream, progress/cancel, best-effort incomplete-file cleanup, unit tests |
| CCAPI RTP Live View | Planned | Not valid | No decoder/session implementation |

## Wired And Other Platforms

| Capability | Status | Completion gate |
| --- | --- | --- |
| Android camera Wi-Fi routing | Implemented; device validation required | Camera HTTP sockets use the reachable Wi-Fi `Network` while cellular stays available; record `WIFI_BOUND` plus a successful internet check on a physical phone |
| Android USB device and PTP-interface diagnostics | Implemented; device validation required | Canon device, permission, interface and endpoints recorded on Android |
| Android PTP container transport, session and DeviceInfo | Implemented; device validation required | Exact USB packet, buffered reads, transaction sequencing and dataset parsing are unit-tested; record a real R6 Mark III response |
| Android PTP storage and media | Implemented; device validation required | Standard storage/object operations, bounded listing and streaming `GetObject` are test-covered; validate card behavior and downloads on R6 Mark III |
| Android PTP standard still capture | Implemented when advertised; device validation required | UI enables capture only when DeviceInfo advertises `InitiateCapture (0x100E)`; record the physical result |
| Android PTP battery and standard property control | Implemented when advertised; device validation required | Descriptor/value codecs and command/data writes are unit-tested; record battery, ISO, Tv, Av, WB and any advanced standard properties advertised by R6 Mark III |
| Android Canon EOS remote release and half-press | Implemented when the full operation sequence is advertised; device validation required | Remote/event preparation, half/full press with guaranteed release, captured-object event polling, timeout/failure paths and teardown are unit-tested against the pinned libgphoto2 sequence |
| Android Canon EOS manual focus drive | Implemented when advertised; device validation required | `DriveLens (0x9155)` Near/Far values 1-3 are capability-gated, wired to localized UI and unit/UI-tested |
| Android Canon EOS JPEG Live View | Implemented when advertised; device validation required | EVF mode/output writes, `GetViewFinderData (0x9153)`, busy retry, bounded block parsing and in-memory JPEG delivery are unit-tested |
| Android Canon EOS ISO/Tv/Av/WB | Implemented when advertised; device validation required | `0xC189/0xC18A` event parsing, camera-provided option gating, libgphoto2 value mappings and `SetDevicePropValueEx (0x9110)` writes are unit-tested; verify each mode on R6 Mark III |
| Android Canon EOS movie start/stop | Implemented when advertised; device validation required | `EVFRecordStatus (0xD1B8)` is exposed only when camera events advertise both Card and None; exact `SetDevicePropValueEx` packets, state updates, missing-capability and rejected-write paths are unit-tested |
| Android Canon EOS AF/drive/metering/picture-style vendor settings | Implemented when advertised; device validation required | Focus mode (`0xD108`), Continuous AF (`0xD1C9`), AF method (`0xD1BA`), Drive mode (`0xD106`), Metering (`0xD107`), Picture Style (`0xD110`) and Movie Servo AF (`0xD179`) use pinned libgphoto2 types/mappings; controls appear only for camera-advertised values, exact writes and rejection paths are unit-tested; English/Traditional Chinese labels are display-only and writes retain the raw camera value |
| Android Canon EOS image quality | Implemented when advertised; device validation required | Generic (`0xD120`), CF/CFexpress (`0xD121`) and SD (`0xD122`) ImageFormat events use bounded one/two-entry parsing; RAW/cRAW/JPEG labels, 28/44-byte writes, missing-capability, malformed-event and rejected-write paths are unit-tested against pinned libgphoto2 behavior and the R6 Mark III snapshot |
| Android Canon EOS remaining vendor settings | Research | Any additional property codes, value mappings and state transitions are proven before controls are exposed |
| Android Canon EOS Touch AF | Research | No active control until a writable coordinate command and R6 Mark III state semantics are proven; read-only focus-point data is not sufficient evidence |
| Desktop Bridge HTTP service | Implemented; device validation required | FastAPI service, loopback/Bearer security, stable errors, sessions, streaming responses and contract tests pass |
| libgphoto2 desktop adapter | Implemented; device validation required | Discovery, dynamic capabilities/settings, capture, half-press, recording, focus drive, bounded JPEG preview, media listing/download and subprocess timeout are test-covered; validate on R6 Mark III |
| PC direct CCAPI engine | Implemented; device validation required | Manual HTTP(S) origin and memory-only Basic Auth, advertised-operation discovery and bounded evidence, dynamic settings, capture/release, recording, Tap AF, 1-30 FPS bounded JPEG polling with the R6 Mark III HTTP 400 fallback, same-origin media traversal/download, secret redaction and deterministic tests pass |
| PC control UI | Implemented; device validation required | Built into the bridge root URL; USB/CCAPI mode selection, capability-gated controls, authenticated binary Live View/downloads, memory-only secrets, capability evidence in redacted diagnostics, English/Traditional Chinese, desktop/narrow browser interaction and overflow checks pass |
| Android Desktop Bridge client | Implemented; device validation required | Bearer token is memory-only; discovery, camera selection, session lifecycle, bounded capability-evidence parsing, controls, JPEG frames, media streaming, diagnostics redaction and Live View request clamping are test-covered |
| Optional Canon EDSDK adapter | Research | User-installed SDK works without redistributing Canon binaries |
| iOS CCAPI core | Implemented; device validation required | Native Swift package requires advertised writable settings and complete Live View lifecycle, capability-gates commands, records bounded discovery evidence, controls capture/record/focus, rejects text metadata downloads, redacts diagnostics, and passes macOS Swift tests |
| iOS CCAPI app | Implemented; device validation required | iOS 17 SwiftUI app reaches the native core for connection, capability-gated controls, JPEG Live View, capture/record/focus, media, and diagnostics; English/Traditional Chinese, offline preview, safe orientation behavior, five unit tests, and two iPhone Simulator UI workflows pass; record a physical iPhone/R6 Mark III result |
| iOS USB/PTP | Research | Public Apple API and a working physical-device path are demonstrated |

## Release Gate

A capability can move to **supported** only when:

1. The UI action reaches a real backend and unsupported transports do not expose an active control.
2. Request and response behavior is covered by deterministic tests.
3. Errors preserve the operation and transport context.
4. The target physical camera result is recorded when vendor-specific behavior is involved.
5. English and Traditional Chinese UI strings, accessibility labels, and offline preview states are present.

Canon's current public SDK page lists EOS R6 Mark III for CCAPI and describes remote settings, capture, and image retrieval. Canon's detailed API specification remains the conformance authority distributed through CAP; public reference implementations are corroborating evidence, not a substitute for physical R6 Mark III validation.
