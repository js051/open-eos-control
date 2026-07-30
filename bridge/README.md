# Open EOS Control Desktop Bridge

This package provides the PC camera backend and browser control surface for Open EOS Control 0.1.3.

It can connect to Canon cameras through native HTTP CCAPI or through a system-installed `gphoto2`/libgphoto2 USB stack. Camera commands and settings are exposed only when the selected backend advertises support.

USB camera events are exposed only after a bounded `gphoto2 --wait-event` probe succeeds. Body-property and file events refresh PC, Android, and iOS Bridge clients. Event waits pause while the exclusive persistent USB Live View process or a Bulb exposure is active, so synchronization never interrupts capture merely to simulate notifications.

The built-in browser control surface includes decoded-frame monitoring assists for CCAPI JPEG, decoded RTP, libgphoto2 USB preview, and browser-owned local UVC/HDMI input: a mutually exclusive bounded luminance histogram or 64x64 luma waveform, configurable zebra, false color, focus peaking, frame guides, action/title safe areas, and anamorphic desqueeze. Local video replaces only the viewfinder while the active Bridge session keeps camera control; device identifiers remain in page memory and all media tracks are stopped during source, device, session, or page teardown. Coordinate Canon controls stay unavailable because local video does not carry Canon Live View geometry. These overlays are local display tools and never imply that the camera accepted a control command.

Camera media downloads are consumed as cancellable Fetch streams with visible byte progress. Files below 64 MiB use a cross-browser Blob download after the streamed transfer completes. Unknown-size and larger files prefer the browser File System Access writer, whose temporary file is committed only after the camera stream completes; unsupported browsers retain the Blob fallback. Disconnecting, closing the page, or pressing cancel aborts the request and does not report a successful download.

## Install

Windows x64 release assets include a single-file executable:

```powershell
.\open-eos-control-bridge-windows-x64-X.Y.Z.exe
```

It bundles the Python runtime, PyAV/FFmpeg, and the browser UI. The loopback control page opens only after the service is ready. Keep the console window open while using the Bridge; `--no-browser` suppresses automatic browser launch. The executable is a development-preview artifact and is not code-signed.

The wheel is the cross-platform installation path:

```bash
python -m pip install open_eos_control_bridge-0.1.3-py3-none-any.whl
```

USB control requires `gphoto2` to be available on `PATH`. CCAPI control does not require `gphoto2`.

## Run

```bash
open-eos-bridge
```

The service listens on `127.0.0.1:18181` by default. LAN binding requires `OPEN_EOS_BRIDGE_TOKEN`; the standalone executable intentionally accepts the token only through the environment and never through a process-list-visible command argument. See the main [Open EOS Control repository](https://github.com/js051/open-eos-control) for platform clients, source code, documentation, and issue tracking.

This is an unofficial Canon EOS project and is licensed under Apache-2.0.
