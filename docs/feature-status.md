# Feature Status And Acceptance

Last audited: 2026-07-21.

This is the canonical completeness ledger for Open EOS Control. A UI control, interface method, or simulator response alone does not make a feature complete.

## Status Rules

- **Implemented**: an executable product path and automated coverage both exist.
- **Device validation**: implemented and test-covered, but still needs a recorded result from the named physical camera.
- **Planned**: architecture or interface only; users must not be shown an active product control.
- **Research**: public platform or protocol evidence is not yet sufficient for a product claim.

## Android And Canon CCAPI

| Capability | Code status | EOS R6 Mark III | Acceptance evidence |
| --- | --- | --- | --- |
| Discovery and API versions | Implemented | Device validation passed | `CcapiClientTest`; diagnostic reports from the physical camera |
| Identity, battery, storage | Implemented | Identity and battery passed; storage pending | Unit tests plus diagnostic raw JSON |
| ISO, Tv, Av, WB | Implemented | Device validation passed | Advertised setting paths, unit tests, physical camera control |
| Dynamic camera settings | Implemented | Device validation in progress | Values come from camera `ability`; writes use discovered versioned paths |
| JPEG Live View | Implemented | Device validation passed at requested 15 FPS | Frame parser tests and physical rolling-FPS report |
| Still capture | Implemented | Device validation required | Basic and manual shutter paths support advertised POST or PUT; manual flow always releases |
| Shutter half-press | Implemented | Device validation required | Advertised manual shutter endpoint, timed half-press, guaranteed release, unit tests |
| Movie start/stop | Implemented | Device validation required | Control is enabled only when `recbutton` is advertised |
| Tap focus | Implemented when advertised | Device validation required | `afpoint` must be advertised; failures are not reported as success |
| Media browser | Implemented | Device validation required | Bounded storage-tree and page traversal; same-origin path validation; unit tests |
| Media download | Implemented as a streaming transfer | Device validation required | SAF destination, same-origin stream, progress/cancel, best-effort incomplete-file cleanup, unit tests |
| CCAPI RTP Live View | Planned | Not valid | No decoder/session implementation |

## Wired And Other Platforms

| Capability | Status | Completion gate |
| --- | --- | --- |
| Android USB device and PTP-interface diagnostics | Implemented; device validation required | Canon device, permission, interface and endpoints recorded on Android |
| Android PTP container transport, session and DeviceInfo | Implemented; device validation required | Exact USB packet, buffered reads, transaction sequencing and dataset parsing are unit-tested; record a real R6 Mark III response |
| Android PTP storage and media | Implemented; device validation required | Standard storage/object operations, bounded listing and streaming `GetObject` are test-covered; validate card behavior and downloads on R6 Mark III |
| Android PTP standard still capture | Implemented when advertised; device validation required | UI enables capture only when DeviceInfo advertises `InitiateCapture (0x100E)`; record the physical result |
| Android PTP battery and standard property control | Implemented when advertised; device validation required | Descriptor/value codecs and command/data writes are unit-tested; record battery, ISO, Tv, Av, WB and any advanced standard properties advertised by R6 Mark III |
| Android Canon EOS vendor property control | Research | Vendor codes, value mappings and state transitions are proven by authoritative documentation or recorded R6 Mark III traces |
| Android PTP Live View | Research | Canon vendor operation and frame format proven on R6 Mark III |
| Desktop Bridge HTTP service | Planned | Executable service passes the documented bridge contract tests |
| libgphoto2 desktop adapter | Planned | Discovery, capture, settings, Live View and media tested on supported hardware |
| Optional Canon EDSDK adapter | Research | User-installed SDK works without redistributing Canon binaries |
| iOS CCAPI app | Planned | Native client connects, controls, renders Live View and passes shared fixtures |
| iOS USB/PTP | Research | Public Apple API and a working physical-device path are demonstrated |

## Release Gate

A capability can move to **supported** only when:

1. The UI action reaches a real backend and unsupported transports do not expose an active control.
2. Request and response behavior is covered by deterministic tests.
3. Errors preserve the operation and transport context.
4. The target physical camera result is recorded when vendor-specific behavior is involved.
5. English and Traditional Chinese UI strings, accessibility labels, and offline preview states are present.

Canon's current public SDK page lists EOS R6 Mark III for CCAPI and describes remote settings, capture, and image retrieval. Canon's detailed API specification remains the conformance authority distributed through CAP; public reference implementations are corroborating evidence, not a substitute for physical R6 Mark III validation.
