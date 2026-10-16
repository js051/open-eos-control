"""Synthetic CLI peer, never opens a camera or network connection."""

from __future__ import annotations

import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

mode, directory, count = sys.argv[1:4]
root = Path(directory)
if mode == "pipe-child":
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    (root / "child.pid").write_text(str(os.getpid()))
    time.sleep(30)
    raise SystemExit(0)
assert sys.argv[4:] == ["--port", "usb:001,007", "--recurse", "--list-files"]
(root / "arguments.json").write_text(json.dumps(sys.argv[4:]))
(root / "pid").write_text(str(os.getpid()))
if mode == "empty":
    print("There are no files in folder '/'.", flush=True)
    raise SystemExit(0)
if mode == "invalid":
    print("#invalid row", flush=True)
    raise SystemExit(0)
if mode == "unrecognized":
    print("not a listing", flush=True)
    raise SystemExit(0)
if mode == "long-line":
    sys.stdout.write("x" * 70_000)
    sys.stdout.flush()
    time.sleep(30)
if mode == "invalid-utf8":
    os.write(1, b"\xff\n")
    time.sleep(30)
if mode == "ignore-term":
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
print("There are " + count + " files in folder '/store_00010001/DCIM/100CANON'.", flush=True)
first = "#1 測試_0001.JPG rd 6 B 6000x4000 image/jpeg 1784600000\n".encode()
# Split a multibyte filename across actual writes.
os.write(1, first[:4])
time.sleep(0.01)
os.write(1, first[4:])
(root / "first").touch()
if mode == "inherited-pipe":
    subprocess.Popen([sys.executable, __file__, "pipe-child", directory, "0"])
    while not (root / "child.pid").exists():
        time.sleep(0.01)
    raise SystemExit(0)
if mode == "stdout-eof":
    os.close(1)
if mode == "stderr-flood":
    os.write(2, b"synthetic-private-" * 100_000)
while not (root / "release").exists():
    time.sleep(0.01)
if mode == "error":
    sys.stderr.write("synthetic-private-address-and-camera-detail")
    raise SystemExit(3)
if mode == "duplicate":
    os.write(1, first)
else:
    for index in range(2, int(count) + 1):
        print(f"#{index} IMG_{index:04d}.JPG rd 6 B 6000x4000 image/jpeg {1784600000 + index}")
