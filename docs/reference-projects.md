# Reference Projects And Validity Map

This project should stay grounded in public specifications, official platform APIs, and working open-source camera-control projects. Features must be marked as implemented only when the code path exists in this repo and has passed local tests.

## Primary References

- Canon CCAPI: Canon documents CCAPI as an HTTP-based API for controlling Canon cameras over a network, usable from a smartphone, tablet, or computer. Reference: [Canon CCAPI product manual](https://cam.start.canon/en/C017/manual/html/UG-06_Network_0130.html).
- Android USB host: Android documents `UsbManager`, `UsbDevice`, `UsbInterface`, `UsbEndpoint`, explicit permission requests, and endpoint communication. Reference: [Android USB host overview](https://developer.android.com/develop/connectivity/usb/host).
- PTP: PTP is ISO 15740:2013 and is defined as a transport- and platform-independent protocol for digital still photography devices. Reference: [ISO 15740:2013](https://www.iso.org/standard/63602.html).
- USB still image class: USB-IF publishes the Still Image Capture Device Class specification used by USB PTP devices. Reference: [USB-IF Still Image Capture Device Definition](https://www.usb.org/document-library/still-image-capture-device-definition-10-and-errata-16-mar-2007).
- libgphoto2 / gPhoto: libgphoto2 is the main open-source reference for camera access/control and PTP-backed Canon EOS tethering. Reference: [libgphoto2 GitHub](https://github.com/gphoto/libgphoto2), [gPhoto remote control docs](https://gphoto.sourceforge.io/doc/remote/), [supported cameras](https://gphoto.sourceforge.io/proj/libgphoto2/support.php).

## Open-Source Behavior To Mirror

- Capability-driven UI: libgphoto2 exposes configuration and capture support per camera model, so Open EOS Control must expose backend capabilities instead of assuming every camera/transport supports every command.
- EOS USB control path: gPhoto lists many Canon EOS R bodies, including EOS R6 and R6 Mark II, with image capture, trigger capture, liveview, and configuration support. R6 Mark III still needs direct validation, so it remains this project's golden test target rather than a claimed upstream-supported body.
- Desktop bridge path: the PC bridge should follow the libgphoto2 model first. Canon EDSDK can be an optional local adapter, but proprietary SDK binaries must not be committed or redistributed here.

## Current Validity Map

Implemented and test-covered:

- CCAPI network backend shape, simulator contract, dynamic settings, recording, tap focus, JPEG live view polling.
- Android USB host diagnostics: enumerate devices, identify Canon vendor ID `0x04A9`, identify PTP still-image interfaces, show endpoints, and request Android USB permission.
- Multi-backend contract: unsupported operations throw explicit transport/feature errors.

Implemented but requires real-camera verification:

- Real Canon CCAPI endpoint variants for R6 Mark III live view and shooting settings.
- Android USB/PTP diagnostics on a physical Android device with the camera connected over OTG/USB-C.

Planned only, not product-valid yet:

- Android PTP session open, transaction container parsing, device info, object/media operations, still capture, setting writes, and USB live view.
- CCAPI RTP live view.
- Desktop bridge service, libgphoto2 adapter, and optional EDSDK adapter.
- iOS app/client.

## Development Rule

Do not mark a feature as supported unless both conditions are true:

1. The repo contains an executable code path for that feature.
2. The feature is covered by automated tests, simulator tests, or a recorded real-device validation note.

Otherwise expose it as `planned`, `research`, or `unsupported`.
