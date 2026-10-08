#!/usr/bin/env python3
"""Check APK diagnostic identity/exclusion only. This does NOT verify DEX hook completeness."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("mode", choices=("on-app", "on-test", "off"))
parser.add_argument("apk", type=Path)
args = parser.parse_args()
with zipfile.ZipFile(args.apk) as apk:
    dex = b"\n".join(apk.read(name) for name in apk.namelist() if name.endswith(".dex"))
markers = {name: name.encode() in dex for name in (
    "Ldev/openeos/control/diagnostics/MeasureWriterRecorder;",
    "Ldev/openeos/control/diagnostics/MeasureWriterProbeRunner;",
    "compose-1.9.3;entries=3;classWrites=17;guards=4;probe=v2",
)}
if args.mode == "off":
    if any(markers.values()): parser.error("Diagnostic marker leaked into off/release APK")
elif args.mode == "on-app":
    if not markers["Ldev/openeos/control/diagnostics/MeasureWriterRecorder;"] or not markers["compose-1.9.3;entries=3;classWrites=17;guards=4;probe=v2"]:
        parser.error("Opted-in app lacks recorder/transform identity")
else:
    if not markers["Ldev/openeos/control/diagnostics/MeasureWriterProbeRunner;"]:
        parser.error("Opted-in test APK lacks diagnostic runner")
print(json.dumps({"mode": args.mode, "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(), "markers": markers,
                  "dex_hook_completeness": "NOT_CHECKED"}, indent=2))
