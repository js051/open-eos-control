# Open EOS Control Desktop Bridge

This package provides the PC camera backend and browser control surface for Open EOS Control 0.1.1.

It can connect to Canon cameras through native HTTP CCAPI or through a system-installed `gphoto2`/libgphoto2 USB stack. Camera commands and settings are exposed only when the selected backend advertises support.

## Install

```bash
python -m pip install open_eos_control_bridge-0.1.1-py3-none-any.whl
```

USB control requires `gphoto2` to be available on `PATH`. CCAPI control does not require `gphoto2`.

## Run

```bash
open-eos-bridge
```

The service listens on `127.0.0.1:18181` by default. See the main [Open EOS Control repository](https://github.com/js051/open-eos-control) for LAN binding, Bearer authentication, platform clients, source code, documentation, and issue tracking.

This is an unofficial Canon EOS project and is licensed under Apache-2.0.
