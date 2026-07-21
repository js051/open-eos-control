# Android USB/PTP

The Android wired backend is intentionally split into a standards-based core and a small Android adapter. This keeps proven PTP behavior reusable and prevents unverified Canon vendor commands from appearing as supported controls.

## Implemented Path

1. Android's Canon USB attach filter can launch the app with temporary permission; `UsbPtpDiagnosticScanner` also enumerates already-attached devices and requests permission explicitly.
2. `AndroidUsbPtpTransport` selects a USB Still Image interface with class/subclass/protocol `06/01/01`, requires bulk IN and OUT endpoints, opens the device, and claims the interface.
3. `PtpSession` sends `GetDeviceInfo`, opens session ID 1, and assigns monotonically increasing transaction IDs to subsequent operations.
4. The backend maps standard DeviceInfo, storage and object datasets into the shared camera models.
5. Media downloads use `GetObject` and stream each USB chunk directly to the caller's `OutputStream`.
6. Disconnect sends `CloseSession` on a best-effort basis and always releases the USB interface and device connection.

The USB reader requests 16 KiB at a time and buffers bytes beyond the 12-byte PTP header. This supports Android 8.x transfer limits while preserving payload bytes that arrive in the same USB transfer as the header.

## Capability Rules

- Identity and USB diagnostics are available after a valid DeviceInfo response.
- Storage requires both `GetStorageIDs (0x1004)` and `GetStorageInfo (0x1005)`.
- Media browsing requires `GetStorageIDs`, `GetObjectHandles (0x1007)`, and `GetObjectInfo (0x1008)`.
- Media download requires `GetObject (0x1009)`.
- Still capture requires the camera to advertise standard `InitiateCapture (0x100E)`.
- Exposure, white balance, focus, movie control, half-press and Live View remain unavailable until the required standard or Canon EOS vendor operations are validated on the target camera.

## Evidence

- [Android USB host APIs](https://developer.android.com/develop/connectivity/usb/host)
- [Android `UsbDeviceConnection`](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection)
- [USB-IF Still Image Capture Device Definition](https://www.usb.org/document-library/still-image-capture-device-definition-10-and-errata-16-mar-2007)
- [USB-IF class codes](https://www.usb.org/defined-class-codes)
- [libgphoto2 PTP engine](https://github.com/gphoto/libgphoto2/tree/master/camlibs/ptp2)

These references establish the transport and standard operation shape. They do not prove Canon EOS R6 Mark III vendor behavior; that evidence must come from an authoritative Canon specification or a recorded physical-device validation.

## R6 Mark III Validation Checklist

- Record VID/PID, interface ID, bulk endpoint addresses and Android permission state.
- Save the redacted diagnostic report after session open.
- Confirm manufacturer, model, serial, PTP version and advertised operation codes.
- Confirm each storage ID and free-space response with and without a card.
- Compare a bounded media list against files visible on the camera.
- Download a JPEG, RAW file and movie; compare byte length and checksum.
- Run still capture only if `0x100E` is advertised and verify that a new object appears.
- Record any PTP response code without converting it into a false success state.
