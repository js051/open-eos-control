# Android USB/PTP

The Android wired backend is split into a standards-based core, a small Android adapter, and a focused Canon EOS vendor layer. Vendor controls are enabled only when the camera identifies the Canon extension and advertises every operation required by that feature.

## Implemented Path

1. Android's Canon USB attach filter can launch the app with temporary permission; `UsbPtpDiagnosticScanner` also enumerates already-attached devices and requests permission explicitly.
2. `AndroidUsbPtpTransport` selects a USB Still Image interface with class/subclass/protocol `06/01/01`, requires bulk IN and OUT endpoints, opens the device, and claims the interface.
3. `PtpSession` sends `GetDeviceInfo`, opens session ID 1, and assigns monotonically increasing transaction IDs to subsequent operations.
4. The backend maps standard DeviceInfo, storage and object datasets into the shared camera models.
5. Advertised standard device properties are decoded from `GetDevicePropDesc (0x1014)`, refreshed with `GetDevicePropValue (0x1015)`, and written through the command/data/response form of `SetDevicePropValue (0x1016)`.
6. Media downloads use `GetObject` and stream each USB chunk directly to the caller's `OutputStream`.
7. Generic no-data, data-in, and data-out operation helpers preserve the same mutex, transaction-ID and response validation rules for vendor operations.
8. Canon remote control enters remote/event mode only when `SetRemoteMode`, `SetEventMode`, and `GetEvent` are all advertised.
9. Canon ISO, Tv, Av and white balance state comes from `PropValueChanged (0xC189)` and `AvailListChanged (0xC18A)` event blocks. Writes use the exact advertised raw choice with `SetDevicePropValueEx (0x9110)`.
10. Canon capture sends half/full press and balanced release operations, then waits for a captured-object event instead of treating command acceptance as a completed exposure. Manual half-press and Near/Far focus-drive commands use the same prepared session.
11. Canon Live View writes EVF mode/output, requests `GetViewFinderData`, retries documented busy/not-ready responses, validates each response block, and returns only JPEG block types 1 or 11 as in-memory frames.
12. Disconnect stops EVF output, restores Canon remote/event state on a best-effort basis, sends `CloseSession`, and always releases the USB interface and device connection.

The USB reader requests 16 KiB at a time and buffers bytes beyond the 12-byte PTP header. This supports Android 8.x transfer limits while preserving payload bytes that arrive in the same USB transfer as the header.

## Capability Rules

- Identity and USB diagnostics are available after a valid DeviceInfo response.
- Storage requires both `GetStorageIDs (0x1004)` and `GetStorageInfo (0x1005)`.
- Media browsing requires `GetStorageIDs`, `GetObjectHandles (0x1007)`, and `GetObjectInfo (0x1008)`.
- Media download requires `GetObject (0x1009)`.
- Standard still capture requires the camera to advertise `InitiateCapture (0x100E)`.
- Canon still capture and half-press require vendor extension ID `0x0000000B`, remote/event preparation, and both `RemoteReleaseOn (0x9128)` and `RemoteReleaseOff (0x9129)`.
- Canon still capture succeeds only after an object-added or object-transfer event is observed. A malformed event or 90-second timeout is an error, not a synthetic success.
- Canon manual focus requires `DriveLens (0x9155)` and exposes only the libgphoto2 Near/Far values 1-3.
- Canon JPEG Live View requires remote/event preparation, `SetDevicePropValueEx (0x9110)`, and `GetViewFinderData (0x9153)`. The backend exposes the feature only when the full set is advertised.
- Canon vendor exposure control requires remote/event preparation and `SetDevicePropValueEx`. Each individual control remains hidden until the camera returns a non-empty available-value list; a current-value event alone is not treated as write permission.
- A property is read only when DeviceInfo advertises its code and the descriptor operation. One broken descriptor does not disable the rest of the session.
- A property control is enabled only when the camera advertises the set operation, marks that descriptor writable, and supplies a bounded enumeration or range. UI labels map back to the exact advertised raw value.
- Standard battery, ISO/exposure index, exposure time, f-number, white balance, exposure compensation, focus mode, metering, flash, exposure program, drive mode and compression descriptors are recognized. Their actual availability remains camera-dependent.
- Canon Touch AF, movie control, and vendor settings beyond the implemented core exposure properties remain unavailable until their coordinate, state, type, and value semantics are adequately proven.

## Evidence

- [Android USB host APIs](https://developer.android.com/develop/connectivity/usb/host)
- [Android `UsbDeviceConnection`](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection)
- [USB-IF Still Image Capture Device Definition](https://www.usb.org/document-library/still-image-capture-device-definition-10-and-errata-16-mar-2007)
- [USB-IF class codes](https://www.usb.org/defined-class-codes)
- [Pinned libgphoto2 PTP engine](https://github.com/gphoto/libgphoto2/tree/ce6c5f7c7fdde404e9897f618df6168c01df70f5/camlibs/ptp2)
- [Pinned EOS R6 Mark III capability snapshot](https://github.com/gphoto/libgphoto2/blob/ce6c5f7c7fdde404e9897f618df6168c01df70f5/camlibs/ptp2/cameras/canon-eos-r6-markIII.txt)

The pinned open-source implementation provides reproducible operation codes, packet shapes, property-event layouts and value tables, release ordering, focus values and Live View parsing behavior. It is corroborating implementation evidence, not a substitute for an authoritative Canon specification or a recorded physical R6 Mark III validation.

## R6 Mark III Validation Checklist

- Record VID/PID, interface ID, bulk endpoint addresses and Android permission state.
- Save the redacted diagnostic report after session open.
- Confirm manufacturer, model, serial, PTP version and advertised operation codes.
- Save the advertised property codes and loaded descriptor diagnostics; compare battery, ISO, Tv, Av and WB against the camera display.
- Change only values advertised by writable descriptors and confirm both the camera state and the next property read.
- Confirm each storage ID and free-space response with and without a card.
- Compare a bounded media list against files visible on the camera.
- Download a JPEG, RAW file and movie; compare byte length and checksum.
- Run still capture only if `0x100E` is advertised and verify that a new object appears.
- Record whether the complete Canon remote/event operation set is advertised; run vendor capture and verify the object event and card result.
- Verify half-press always releases after success, focus failure, cancellation, and transport errors.
- Run each Near/Far focus step with an MF-compatible lens/camera state and record direction and distance.
- Record the Canon property events in multiple exposure modes, verify the displayed ISO/Tv/Av/WB choices exactly match the camera, write representative values, and confirm both camera state and returned events change.
- Start/stop USB Live View repeatedly, record frame type/size/FPS, and confirm the camera display and controls recover after disconnect.
- Record any PTP response code without converting it into a false success state.
