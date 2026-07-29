# Open EOS Control Desktop Bridge

This package provides the PC camera backend and browser control surface for Open EOS Control 0.1.2.

It can connect to Canon cameras through native HTTP CCAPI or through a system-installed `gphoto2`/libgphoto2 USB stack. Camera commands and settings are exposed only when the selected backend advertises support.

The built-in browser control surface includes decoded-frame monitoring assists for CCAPI JPEG, decoded RTP, and libgphoto2 USB preview: a bounded luminance histogram, configurable zebra, false color, focus peaking, frame guides, action/title safe areas, and anamorphic desqueeze. These overlays are local display tools and never imply that the camera accepted a control command.

Camera media downloads are consumed as cancellable Fetch streams with visible byte progress. Files below 64 MiB use a cross-browser Blob download after the streamed transfer completes. Unknown-size and larger files prefer the browser File System Access writer, whose temporary file is committed only after the camera stream completes; unsupported browsers retain the Blob fallback. Disconnecting, closing the page, or pressing cancel aborts the request and does not report a successful download.

## Install

```bash
python -m pip install open_eos_control_bridge-0.1.2-py3-none-any.whl
```

USB control requires `gphoto2` to be available on `PATH`. CCAPI control does not require `gphoto2`.

## Run

```bash
open-eos-bridge
```

The service listens on `127.0.0.1:18181` by default. See the main [Open EOS Control repository](https://github.com/js051/open-eos-control) for LAN binding, Bearer authentication, platform clients, source code, documentation, and issue tracking.

This is an unofficial Canon EOS project and is licensed under Apache-2.0.
