# Android USB/PTP

The Android wired backend is split into a standards-based core, a small Android adapter, and a focused Canon EOS vendor layer. Vendor controls are enabled only when the camera identifies the Canon extension and advertises every operation required by that feature.

## Implemented Path

1. Android's Canon USB attach filter can launch the app with temporary permission; `UsbPtpDiagnosticScanner` also enumerates already-attached devices and requests permission explicitly.
2. `AndroidUsbPtpTransport` selects a USB Still Image interface with class/subclass/protocol `06/01/01`, requires bulk IN and OUT endpoints, opens the device, and claims the interface.
3. `PtpSession` sends `GetDeviceInfo`, opens session ID 1, and assigns monotonically increasing transaction IDs to subsequent operations.
4. The backend maps standard DeviceInfo, storage and object datasets into the shared camera models, preserving per-session card count and total/free bytes. Remaining shots prefer Canon EOS `AvailableShots (0xD11B, UINT32)` when emitted by the camera and otherwise fall back to valid standard storage values; PTP and Canon unknown sentinels remain unset.
5. Advertised standard device properties are decoded from `GetDevicePropDesc (0x1014)`, refreshed with `GetDevicePropValue (0x1015)`, and written through the command/data/response form of `SetDevicePropValue (0x1016)`.
6. Media downloads use `GetObject` and stream each USB chunk directly to the caller's `OutputStream`; deletion uses the standard `DeleteObject` command only when DeviceInfo advertises it.
7. Generic no-data, data-in, and data-out operation helpers preserve the same mutex, transaction-ID and response validation rules for vendor operations.
8. Canon remote control enters remote/event mode only when `SetRemoteMode`, `SetEventMode`, and `GetEvent` are all advertised.
9. Canon ISO, Tv, Av and white balance state comes from `PropValueChanged (0xC189)` and `AvailListChanged (0xC18A)` event blocks. Writes use the exact advertised raw choice with `SetDevicePropValueEx (0x9110)`.
10. Canon shooting mode, exposure compensation, color temperature, signed white-balance shifts, color space, aspect ratio, power-zoom speed, Auto Power Off, High ISO noise reduction, AEB, AF operation, Continuous AF, AF method, drive, metering, Picture Style and Movie Servo AF use their pinned libgphoto2 data widths and value tables. The backend exposes only choices present in each camera-provided available-value event and, where required, a documented safe subset.
11. Canon generic, SD and CF/CFexpress ImageFormat events are decoded from bounded one/two-entry structures into RAW/cRAW/JPEG choices. Writes rebuild the camera's 28- or 44-byte `SetDevicePropValueEx` payload instead of treating the setting as a fixed UINT16 wire value.
12. Canon capture first checks `CaptureDestination (0xD11C)`. If the camera reports host RAM (`4`), the backend writes the camera-advertised non-host memory-card value through `SetDevicePropValueEx`; a missing card choice or rejected write aborts before any shutter command. It then sends half/full press and balanced release operations and waits for a captured-object event instead of treating command acceptance as a completed exposure. Independent AF-ON prefers the advertised no-argument `DoAf (0x9154)` plus `AfCancel (0x9160)` pair and guarantees cancel even after failure or coroutine cancellation; a balanced half-press is retained only as a fallback. Manual half-press and Near/Far focus-drive commands use the same prepared session.
13. Canon Live View writes EVF mode/output, requests `GetViewFinderData`, retries documented busy/not-ready responses, validates each response block, and returns only JPEG block types 1 or 11 as in-memory frames.
14. Canon movie control writes the camera-advertised `EVFRecordStatus (0xD1B8)` value through `SetDevicePropValueEx`: Card (`4`) starts recording, None (`0`) stops, and SDRAM (`3`) represents preview output.
15. Disconnect stops EVF output, restores Canon remote/event state on a best-effort basis, sends `CloseSession`, and always releases the USB interface and device connection.

The USB reader requests 16 KiB at a time and buffers bytes beyond the 12-byte PTP header. This supports Android 8.x transfer limits while preserving payload bytes that arrive in the same USB transfer as the header.

## Capability Rules

- Identity and USB diagnostics are available after a valid DeviceInfo response.
- Storage requires both `GetStorageIDs (0x1004)` and `GetStorageInfo (0x1005)`.
- Canon EOS `AvailableShots (0xD11B)` is read-only status evidence, not a writable setting. A valid event value takes precedence because the pinned R6 Mark III snapshot reports `-1` for standard per-card image counts while exposing the camera's remaining-shot count through this property.
- Media browsing requires `GetStorageIDs`, `GetObjectHandles (0x1007)`, and `GetObjectInfo (0x1008)`.
- Media download requires `GetObject (0x1009)`.
- Media deletion requires `DeleteObject (0x100B)` and a user confirmation; the list changes only after the exact object handle succeeds.
- Standard still capture requires the camera to advertise `InitiateCapture (0x100E)`.
- Canon still capture and half-press require vendor extension ID `0x0000000B`, remote/event preparation, and both `RemoteReleaseOn (0x9128)` and `RemoteReleaseOff (0x9129)`.
- Canon native autofocus requires remote/event preparation plus both `DoAf` and `AfCancel`. If that pair is absent but the complete remote-release pair exists, `AUTOFOCUS` uses the balanced half-press fallback.
- `CaptureDestination` is diagnostic-only and never offered as a user setting. Host-RAM capture remains unsupported because the app does not yet complete Canon's host object-transfer and cleanup lifecycle.
- Canon still capture succeeds only after an object-added or object-transfer event is observed. A malformed event or 90-second timeout is an error, not a synthetic success.
- Canon manual focus requires `DriveLens (0x9155)` and exposes only the libgphoto2 Near/Far values 1-3.
- Canon JPEG Live View requires remote/event preparation, `SetDevicePropValueEx (0x9110)`, and `GetViewFinderData (0x9153)`. The backend exposes the feature only when the full set is advertised.
- Canon vendor exposure control requires remote/event preparation and `SetDevicePropValueEx`. Each individual control remains hidden until the camera returns a non-empty available-value list; a current-value event alone is not treated as write permission.
- Canon advanced setting control follows the same rule for Focus Mode (`0xD108`, UINT32), Continuous AF (`0xD1C9`, UINT32), AF Method (`0xD1BA`, UINT32), Drive Mode (`0xD106`, UINT16), Metering Mode (`0xD107`, UINT8), Picture Style (`0xD110`, UINT8), and Movie Servo AF (`0xD179`, UINT32). Canon values take precedence over duplicate standard PTP controls, and standard controls remain the fallback when no Canon list is advertised.
- The R6 Mark III exposure/color set follows the same advertised-list rule for exposure compensation (`0xD104`, UINT8), color temperature (`0xD10A`, UINT32), white-balance shifts A/B (`0xD10B/0xD10C`, INT32), color space (`0xD10F`, UINT16), High ISO noise reduction (`0xD178`, UINT16), and AEB (`0xD1D9`, UINT16). Signed INT32 event values are sign-extended and written in little-endian two's-complement form.
- Canon Auto Exposure Mode (`0xD105`, UINT16) exposes the camera-advertised P/Tv/Av/M/Bulb/Fv/Movie and scene choices as the shared `shootingmode` setting. The normal Photo/Video switch writes an advertised Movie value and restores only a previously observed photo mode; it never guesses a fallback mode or writes when the setting is absent.
- Aspect ratio (`0xD194`, UINT32) and power-zoom speed (`0xD149`, UINT32) use the pinned Canon tables and the same camera-advertised list rule. Auto Power Off (`0xD114`, UINT32) exposes only the camera-advertised intersection of Canon's documented 15 sec., 30 sec., 1/3/5/10/30 min. and Disable values. The R6 Mark III snapshot's extra `0xFFFFFFFF` sentinel remains visible in raw diagnostics but is never presented as a writable choice. A one-value list remains visible in diagnostics but is omitted from the normal control sheet because it offers no actionable choice.
- Canon ImageFormat controls require an advertised non-empty list for each of `0xD120`, `0xD121` and `0xD122`. Malformed entry counts, truncated entries or entry lengths other than `0x10` abort parsing; the UI never receives those choices.
- Canon movie control requires the same remote/event and property-write operations plus an `EVFRecordStatus` available-value event containing both Card and None. A successful data-phase response updates state; a rejected response leaves recording unchanged and propagates the PTP error.
- A property is read only when DeviceInfo advertises its code and the descriptor operation. One broken descriptor does not disable the rest of the session.
- A property control is enabled only when the camera advertises the set operation, marks that descriptor writable, and supplies a bounded enumeration or range. UI labels map back to the exact advertised raw value.
- Standard battery, ISO/exposure index, exposure time, f-number, white balance, exposure compensation, focus mode, metering, flash, exposure program, drive mode and compression descriptors are recognized. Their actual availability remains camera-dependent.
- Other remaining Canon vendor properties stay unavailable until their packing, state, type and value semantics are adequately proven. Touch AF separately requires a verified writable coordinate command and R6 Mark III coordinate semantics; the pinned R6 Mark III snapshot exposes DoAf/AfCancel but no Touch AF control.

## Evidence

- [Android USB host APIs](https://developer.android.com/develop/connectivity/usb/host)
- [Android `UsbDeviceConnection`](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection)
- [USB-IF Still Image Capture Device Definition](https://www.usb.org/document-library/still-image-capture-device-definition-10-and-errata-16-mar-2007)
- [USB-IF class codes](https://www.usb.org/defined-class-codes)
- [Pinned libgphoto2 PTP engine](https://github.com/gphoto/libgphoto2/tree/ce6c5f7c7fdde404e9897f618df6168c01df70f5/camlibs/ptp2)
- [Pinned libgphoto2 Canon setting tables](https://github.com/gphoto/libgphoto2/blob/ce6c5f7c7fdde404e9897f618df6168c01df70f5/camlibs/ptp2/config.c)
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
- Delete a disposable test image only when `0x100B` is advertised; confirm the exact object disappears while adjacent handles remain.
- Run still capture only if `0x100E` is advertised and verify that a new object appears.
- Record whether the complete Canon remote/event operation set is advertised. If diagnostics report host RAM for `0xD11C`, run vendor capture and verify the destination write precedes remote release, the object event arrives, and the file exists on the selected card.
- Verify half-press always releases after success, focus failure, cancellation, and transport errors.
- Verify native AF-ON starts with `0x9154`, remains active for the bounded hold, and always sends `0x9160` after success, camera rejection, cancellation, and disconnect.
- Run each Near/Far focus step with an MF-compatible lens/camera state and record direction and distance.
- Record the Canon property events in multiple exposure modes, verify the displayed ISO/Tv/Av/WB choices exactly match the camera, write representative values, and confirm both camera state and returned events change.
- In photo and movie modes, record the advertised AF operation/method, Continuous AF, drive, metering, Picture Style and Movie Servo AF lists; write one supported value per property and confirm both the camera state and next event.
- Record the advertised exposure compensation, color temperature, both white-balance shifts, color space, High ISO noise reduction and AEB lists; write representative positive, negative and off values, then confirm the camera menu and next property event agree.
- Record the advertised Auto Exposure Mode list, switch P/Tv/Av/M/Fv and Movie through both the settings sheet and Photo/Video control, then confirm the camera mode, returned event, restored prior photo mode and failure behavior agree.
- Record aspect-ratio and power-zoom-speed lists in applicable photo/movie and lens states; change one advertised value only when at least two choices are available, then confirm the camera menu and next property event agree.
- Record the Auto Power Off list, select 30 sec. and Disable, and confirm the camera menu plus the next `0xC189` event agree. Confirm `0xFFFFFFFF` never appears as an interactive option.
- Record generic, SD and CF/CFexpress ImageFormat lists. Test JPEG, RAW, cRAW and one combined RAW+JPEG choice on the applicable card, then confirm the camera menu and next property event agree.
- Confirm `0xD1B8` advertises Card/None/SDRAM, start and stop a short card recording, verify the on-camera REC state and resulting movie file, then capture the redacted diagnostic report.
- Start/stop USB Live View repeatedly, record frame type/size/FPS, and confirm the camera display and controls recover after disconnect.
- Record any PTP response code without converting it into a false success state.
