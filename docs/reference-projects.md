# Reference Projects And Validity Map

This project should stay grounded in public specifications, official platform APIs, and working open-source camera-control projects. Features must be marked as implemented only when the code path exists in this repo and has passed local tests.

## Primary References

- Canon CCAPI: Canon documents CCAPI as an HTTP-based API for controlling Canon cameras over a network, usable from a smartphone, tablet, or computer. Reference: [Canon CCAPI product manual](https://cam.start.canon/en/C017/manual/html/UG-06_Network_0130.html).
- Canon CAP release status: Canon's public release notes list CameraControlAPI Reference v1.4.0 Rev.1.4 as of March 31, 2026. The API specification and official Android sample are distributed through Canon's CAP program and remain the conformance source for endpoint payloads. References: [Canon CAP overview](https://asia.canon/en/campaign/developerresources/camera/cap/cap), [CCAPI release notes](https://asia.canon/en/campaign/developerresources/camera/cap/camera-control-api-release-note).
- Android USB host: Android documents `UsbManager`, `UsbDevice`, `UsbInterface`, `UsbEndpoint`, explicit permission requests, and endpoint communication. Reference: [Android USB host overview](https://developer.android.com/develop/connectivity/usb/host).
- PTP: PTP is ISO 15740:2013 and is defined as a transport- and platform-independent protocol for digital still photography devices. Reference: [ISO 15740:2013](https://www.iso.org/standard/63602.html).
- USB still image class: USB-IF publishes the Still Image Capture Device Class specification used by USB PTP devices. Reference: [USB-IF Still Image Capture Device Definition](https://www.usb.org/document-library/still-image-capture-device-definition-10-and-errata-16-mar-2007).
- libgphoto2 / gPhoto: libgphoto2 is the main open-source reference for camera access/control and PTP-backed Canon EOS tethering. Reference: [libgphoto2 GitHub](https://github.com/gphoto/libgphoto2), [gPhoto remote control docs](https://gphoto.sourceforge.io/doc/remote/), [supported cameras](https://gphoto.sourceforge.io/proj/libgphoto2/support.php).
- EOS R6 Mark III libgphoto2 evidence: upstream includes a generated R6 Mark III configuration snapshot with Canon EOS capture abilities, storage, battery, shooting mode, exposure, color temperature, WB shifts, color space, aspect ratio, power-zoom speed, High ISO noise reduction, AEB, remote release, movie target, and manual focus-drive values. It is concrete adapter evidence, but it does not replace this project's physical-device acceptance record. Reference: [pinned R6 Mark III snapshot](https://github.com/gphoto/libgphoto2/blob/ce6c5f7c7fdde404e9897f618df6168c01df70f5/camlibs/ptp2/cameras/canon-eos-r6-markIII.txt), [gphoto2 CLI manual](https://www.gphoto.org/doc/manual/ref-gphoto2-cli.html).
- Canon EOS PTP behavior reference: pinned libgphoto2 source defines the remote/event operations, property-event layouts and value tables, balanced release sequence, `DriveLens` values, movie target, EVF properties, `GetViewFinderData` request and block format mirrored by the Android backend. References: [operation/property definitions](https://github.com/gphoto/libgphoto2/blob/ce6c5f7c7fdde404e9897f618df6168c01df70f5/camlibs/ptp2/ptp.h), [Canon property packet writer](https://github.com/gphoto/libgphoto2/blob/ce6c5f7c7fdde404e9897f618df6168c01df70f5/camlibs/ptp2/ptp.c), [Canon event parser](https://github.com/gphoto/libgphoto2/blob/ce6c5f7c7fdde404e9897f618df6168c01df70f5/camlibs/ptp2/ptp-pack.c), [capture and Live View behavior](https://github.com/gphoto/libgphoto2/blob/ce6c5f7c7fdde404e9897f618df6168c01df70f5/camlibs/ptp2/library.c), [settings, focus and remote controls](https://github.com/gphoto/libgphoto2/blob/ce6c5f7c7fdde404e9897f618df6168c01df70f5/camlibs/ptp2/config.c).
- Canon EOS R6 Mark III Traditional Chinese terminology: display-only shooting-mode, AF, metering and Picture Style translations follow Canon's official Taiwan manual; backend writes continue to use the camera-advertised raw values. References: [shooting modes](https://cam.start.canon/tc/C022/manual/html/UG-02_ShootingStill_0010.html), [AF operation](https://cam.start.canon/tc/C022/manual/html/UG-05_AF-Drive_0040.html), [AF area](https://cam.start.canon/tc/C022/manual/html/UG-05_AF-Drive_0060.html), [metering mode](https://cam.start.canon/tc/C022/manual/html/UG-04_Shooting_0230.html), [Picture Style](https://cam.start.canon/tc/C022/manual/html/UG-04_Shooting_0260.html).
- PTP packet and dataset implementation reference: libgphoto2's maintained PTP engine corroborates the USB command/data/response container layout and standard operation codes used by this project. Reference: [pinned libgphoto2 PTP source](https://github.com/gphoto/libgphoto2/tree/ce6c5f7c7fdde404e9897f618df6168c01df70f5/camlibs/ptp2).

## Open-Source Behavior To Mirror

- Capability-driven UI: libgphoto2 exposes configuration and capture support per camera model, so Open EOS Control must expose backend capabilities instead of assuming every camera/transport supports every command.
- EOS USB control path: gPhoto lists many Canon EOS R bodies, including EOS R6 and R6 Mark II, with image capture, trigger capture, liveview, and configuration support. R6 Mark III still needs direct validation, so it remains this project's golden test target rather than a claimed upstream-supported body.
- Desktop bridge path: the PC bridge should follow the libgphoto2 model first. Canon EDSDK can be an optional local adapter, but proprietary SDK binaries must not be committed or redistributed here.

## Current Validity Map

Implemented and test-covered:

- CCAPI network backend shape, simulator contract, dynamic settings, recording, tap focus, JPEG live view polling, still/manual shutter control, paged media browsing, and streaming media download.
- Android USB host diagnostics: enumerate devices, identify Canon vendor ID `0x04A9`, identify PTP still-image interfaces, show endpoints, and request Android USB permission.
- Android standards-based USB/PTP path: claim the interface, open/close a session, parse DeviceInfo, property, storage and object datasets, safely write bounded camera-advertised standard properties, conditionally issue advertised standard still capture, browse media, and stream `GetObject` downloads.
- Android Canon EOS USB path: capability-gated remote/event setup, shooting mode, ISO/Tv/Av/WB, exposure compensation, color temperature, signed WB shifts, color space, aspect ratio, power-zoom speed, High ISO noise reduction and AEB event state/writes, still capture with balanced release and event confirmation, timed half-press, Card/None movie control, Near/Far focus drive, EVF start/stop, and in-memory JPEG Live View parsing.
- Desktop Bridge service, libgphoto2 CLI adapter, direct PC CCAPI engine, and Android bridge client: authenticated sessions, dynamic settings/capabilities, capture, focus, JPEG preview, media streaming and deterministic contract tests.
- Multi-backend contract: unsupported operations throw explicit transport/feature errors.

Implemented but requires real-camera verification:

- Real Canon CCAPI endpoint variants for R6 Mark III live view and shooting settings.
- R6 Mark III still/manual shutter, recording, tap focus, media browsing, and media download.
- Android USB/PTP diagnostics, session, DeviceInfo, standard properties, storage/media, download, advertised standard capture, and the implemented Canon remote/exposure/movie/focus/Live View paths on a physical Android device with the camera connected over OTG/USB-C.
- PC direct CCAPI, Desktop Bridge USB, and Android bridge client with a physical R6 Mark III.
- iOS CCAPI app with a physical iPhone and R6 Mark III.

Recorded physical evidence:

- [Sanitized Android/R6 Mark III CCAPI validation](validation/eos-r6-mark-iii-android-ccapi.md): discovery, identity, battery, advertised exposure controls, the Live View parameter fallback, and requested/observed 15 FPS are recorded; shutter, movie, Tap AF, media, Wi-Fi/cellular coexistence, and USB remain explicitly pending.

Planned only, not product-valid yet:

- Canon EOS USB Touch AF and remaining vendor settings beyond the implemented shooting-mode, exposure, color, aspect-ratio, power-zoom, image-quality, AF, drive, metering and movie mappings.
- CCAPI RTP live view.
- Optional EDSDK adapter.

## Development Rule

Do not mark a feature as supported unless both conditions are true:

1. The repo contains an executable code path for that feature.
2. The feature is covered by automated tests, simulator tests, or a recorded real-device validation note.

Otherwise expose it as `planned`, `research`, or `unsupported`.
