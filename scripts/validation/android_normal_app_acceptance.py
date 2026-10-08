"""One external, ordinary-APK/API-36 synthetic-peer acceptance journey.

The caller owns build, install, emulator, and the single-worker localhost:18080
fixture. This program neither installs nor instruments an app. Unrecognized
accessibility/window/provider output is NOT EXERCISED, never inferred success.
All selectors are ordinary exported English labels or platform widget roles.
No app test tags, private app files, OCR, fixed tap coordinates, or UI retries.

Required identity JSON (produced and verified by the build job): app_commit,
app_tree, dependency_inputs_sha256, apk_sha256, apk_signer_sha256, version_code,
version_name, build_command, fixture_sha256, simulator_dependencies, build_tools,
action_pins, avd_hw_keyboard, fresh_ephemeral_emulator. Source provenance is the
job's manifest, not independent proof of remote acceptance. The accepted app
commit is fixed below and remains separate from the driver's source hash.

Exit codes: 0 automated PASS, 1 FAIL, 2 NOT EXERCISED. Automated PASS still
requires owner review of keyboard pixels in the saved before/after evidence.
It is not final acceptance and never clears the release HOLD. The only proposed
claim is transfer survival across IME/dialog dismissal, followed by completion
after Release. Original crash root cause remains UNKNOWN; no camera claim.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import select
import shlex
import struct
import subprocess
import time
import xml.etree.ElementTree as ET
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from urllib.parse import unquote
from urllib.request import Request, urlopen

APP = "dev.openeos.control.debug"
ACTIVITY = "dev.openeos.control.MainActivity"
COMPONENT = f"{APP}/{ACTIVITY}"
ACCEPTED_APP_COMMIT = "65fbabf00c78924228ec7cbfe22d733ef806094f"
ACCEPTED_APP_TREE = "c1e296b9c90bac0cda001cb0bf9bc4d91ef90eb2"
PEER = "http://127.0.0.1:18080"
CAMERA_URL = "http://10.0.2.2:18080"
DATE = "2000-01-01"
MAX_SECONDS = 300.0
COMMAND_SECONDS = 10.0
TRANSITION_SECONDS = 20.0
PROVIDER_PACKAGES = {"com.android.documentsui", "com.google.android.documentsui"}


class NotExercised(Exception):
    """A required prerequisite or observation cannot be established."""


class Failure(Exception):
    """An observed journey contradicts the acceptance requirements."""


OBSERVATION_ERRORS = (
    NotExercised,
    Failure,
    KeyError,
    IndexError,
    TypeError,
    ValueError,
    OSError,
    subprocess.SubprocessError,
)


def require(condition: object, reason: str, error=NotExercised) -> None:
    if not condition:
        raise error(reason)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class Budget:
    def __init__(self, seconds=MAX_SECONDS, clock=time.monotonic, pause=time.sleep):
        self.clock, self.pause = clock, pause
        self.started = clock()
        self.deadline = self.started + seconds

    def remaining(self, limit=COMMAND_SECONDS) -> float:
        remaining = min(limit, self.deadline - self.clock())
        require(remaining > 0, "Overall driver deadline exhausted", Failure)
        return remaining

    def poll(self, description: str, probe: Callable, seconds=TRANSITION_SECONDS, error=NotExercised):
        original_deadline = self.deadline
        until = min(original_deadline, self.clock() + seconds)
        self.deadline = until  # Commands inside the probe share the transition ceiling.
        try:
            while self.clock() < until:
                result = probe()
                if result:
                    require(self.clock() <= until, f"Timed out waiting for {description}", error)
                    return result
                left = until - self.clock()
                if left > 0:
                    # Backoff only between observed false conditions, never click timing.
                    self.pause(min(0.25, left))
        finally:
            self.deadline = original_deadline
        raise error(f"Timed out waiting for {description}")


@dataclass(frozen=True)
class Rect:
    left: int
    top: int
    right: int
    bottom: int

    @classmethod
    def parse(cls, text: str) -> Rect:
        match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", text)
        require(match, f"Unsupported bounds: {text!r}")
        return cls(*map(int, match.groups()))

    def intersect(self, other: Rect) -> Rect:
        return Rect(
            max(self.left, other.left),
            max(self.top, other.top),
            min(self.right, other.right),
            min(self.bottom, other.bottom),
        )

    @property
    def valid(self):
        return self.right > self.left and self.bottom > self.top

    @property
    def center(self):
        require(self.valid, "Empty visible target bounds")
        return ((self.left + self.right) // 2, (self.top + self.bottom) // 2)


def window_blocks(dump: str) -> list[str]:
    return re.findall(r"(?ms)^[ \t]*Window #\d+ Window\{.*?(?=^[ \t]*Window #\d+ Window\{|\Z)", dump)


def window_frame(block: str) -> Rect:
    # Deliberately support only explicit API-36 frame forms, never parent/display.
    matches = re.findall(r"(?:\bmFrame=|\bframe=)(\[-?\d+,-?\d+\]\[-?\d+,-?\d+\])", block)
    require(len(set(matches)) == 1, "Missing or ambiguous API-36 window frame")
    return Rect.parse(matches[0])


def visible_window(block: str) -> bool:
    return "mHasSurface=true" in block and "isVisible=true" in block and "mViewVisibility=0x0" in block


def focused_window(dump: str, package: str) -> Rect:
    match = re.search(r"mCurrentFocus=Window\{([^ }]+) u\d+ ([^}\s]+)\}", dump)
    require(match and match.group(2).split("/")[0] == package, f"Foreground window is not verified for {package}")
    blocks = [b for b in window_blocks(dump) if f"Window{{{match.group(1)} " in b.splitlines()[0]]
    require(len(blocks) == 1 and visible_window(blocks[0]), "Focused window visibility is unavailable")
    return window_frame(blocks[0])


def ime_observation(ime: str, windows: str, screen: Rect) -> tuple[bool, Rect | None]:
    values = re.findall(r"\bmInputShown=(true|false)\b", ime)
    require(len(values) == 1, "Unsupported API-36 input-method visibility dump")
    all_windows = window_blocks(windows)
    require(all_windows, "Unsupported API-36 window dump")
    blocks = [b for b in all_windows if "ty=INPUT_METHOD " in b or re.search(r"Window\{[^\n]* InputMethod\}", b)]
    for block in blocks:
        require(
            re.search(r"\bmHasSurface=(true|false)\b", block)
            and re.search(r"\bisVisible=(true|false)\b", block)
            and re.search(r"\bmViewVisibility=0x[0-9a-f]+\b", block),
            "Unsupported API-36 IME window visibility dump",
        )
    visible = [window_frame(b).intersect(screen) for b in blocks if visible_window(b)]
    visible = [r for r in visible if r.valid]
    require(len(visible) <= 1, "Ambiguous visible IME windows")
    return values[0] == "true", visible[0] if visible else None


def ime_region(ime: str, windows: str, screen: Rect, *, shown: bool) -> Rect | None:
    input_shown, region = ime_observation(ime, windows, screen)
    if shown:
        require(input_shown and region is not None, "Actual on-screen platform IME is not established")
        return region
    require(not input_shown and region is None, "Platform IME remains visible after Apply", Failure)
    return None


class Tree:
    def __init__(self, xml: str, package: str, window: Rect, occlusion: Rect | None = None):
        try:
            self.root = ET.fromstring(xml)
        except ET.ParseError as exc:
            raise NotExercised("Malformed accessibility XML") from exc
        require(self.root.tag == "hierarchy", "Unsupported accessibility tree root")
        self.package, self.window, self.occlusion = package, window, occlusion
        self.observed_at = time.monotonic()
        self.parents = {child: parent for parent in self.root.iter() for child in parent}

    def eligible(self, node) -> bool:
        if node.get("package") != self.package:
            return False
        while node is not self.root:
            if node.get("enabled") != "true" or node.get("visible-to-user", "true") != "true":
                return False
            node = self.parents.get(node, self.root)
        return True

    def nodes(self, label: str):
        return [
            n
            for n in self.root.iter("node")
            if self.eligible(n) and label in {n.get("text"), n.get("content-desc"), n.get("hint")}
        ]

    def has(self, label: str) -> bool:
        return bool(self.nodes(label))

    def target(self, label: str, *, editable=False):
        labels = self.nodes(label)
        require(len(labels) <= 1, f"Ambiguous live label: {label}")
        if not labels:
            return None
        node = labels[0]
        while node is not self.root:
            candidates = (
                [n for n in node.iter("node") if self.eligible(n) and n.get("class") == "android.widget.EditText"]
                if editable
                else []
            )
            if editable and candidates:
                require(len(candidates) == 1, f"Ambiguous editable field for {label}")
                return candidates[0]
            if not editable and node.get("clickable") == "true" and self.eligible(node):
                require(
                    node.get("class")
                    in {
                        "android.view.View",
                        "android.widget.Button",
                        "android.widget.ImageButton",
                        "android.widget.RadioButton",
                        "android.widget.TextView",
                        "android.widget.LinearLayout",
                        "android.widget.FrameLayout",
                    },
                    f"Unsupported clickable role for {label}",
                )
                return node
            node = self.parents.get(node, self.root)
        raise NotExercised(f"No uniquely associated {'editable' if editable else 'clickable'} role for {label}")

    def bounds(self, node) -> Rect:
        require(time.monotonic() - self.observed_at <= 5, "Accessibility bounds are stale")
        require(node in self.parents, "Target is not part of the live accessibility tree")
        require(self.eligible(node), "Hidden or disabled target")
        rect = Rect.parse(node.get("bounds", "")).intersect(self.window)
        parent = self.parents.get(node, self.root)
        while parent is not self.root:
            rect = rect.intersect(Rect.parse(parent.get("bounds", "")))
            parent = self.parents.get(parent, self.root)
        if self.occlusion and rect.intersect(self.occlusion).valid:
            # Restrict interaction to the observed region above the keyboard.
            rect = rect.intersect(Rect(self.window.left, self.window.top, self.window.right, self.occlusion.top))
        require(rect.valid, "Target is outside the visible window or hidden by the IME")
        return rect

    def text(self, node):
        return node.get("text", "")


def validate_hold(
    state: dict, gate_id: str, expected: bytes, original_baseline: int, *, finished=False, item_id=None
) -> dict:
    fixture = state["capture_delivery"]
    gate = fixture["original_hold"]
    require(isinstance(gate, dict) and gate["gate_id"] == gate_id, "Controlled request identity changed", Failure)
    require(
        gate["request_id"] == gate_id + "/1" and gate["request_query"] in {"", "kind=main", "type=main"},
        "Missing canonical one-request identity",
        Failure,
    )
    require(
        fixture["representation_get_counts"]["original"] == original_baseline + 1
        and gate["rejected_original_count"] == 0,
        "Extra or missing original request",
        Failure,
    )
    require(
        gate["expected_byte_count"] == len(expected) and gate["expected_sha256"] == sha256(expected),
        "Frozen original changed",
        Failure,
    )
    require(
        gate["prefix_bytes"] == 64 and gate["drip_interval_ms"] == 1000 and gate["max_hold_ms"] == 180000,
        "Controlled original hold constants changed",
        Failure,
    )
    require(
        fixture["representations"]["original"]["byte_count"] == len(expected)
        and fixture["representations"]["original"]["sha256"] == sha256(expected),
        "Fixture representation differs from frozen original",
        Failure,
    )
    stamps = gate["timestamps"]
    require(
        all(stamps.get(key) is None for key in ("cancel_requested", "disconnect_observed", "expired", "stream_error")),
        "Original request cancelled, expired, disconnected, or failed",
        Failure,
    )
    require(
        stamps["request_arrived"] and stamps["headers_asgi_accepted"] and stamps["prefix_asgi_accepted"],
        "Original prefix has no ASGI acceptance evidence",
        Failure,
    )
    require(
        gate["target_path"] == f"/ccapi/ver100/contents/card1/100CANON/{gate['item_id']}"
        and gate["item_id"] in fixture["captured_ids"]
        and (item_id is None or gate["item_id"] == item_id),
        "Held request is not the captured original",
        Failure,
    )
    count = gate["body_bytes_asgi_accepted"]
    require(
        type(count) is int
        and 0 <= count <= len(expected)
        and gate["body_sha256_asgi_accepted"] == sha256(expected[:count]),
        "Accepted prefix differs from frozen original",
        Failure,
    )
    if finished:
        require(
            gate["phase"] == "terminal"
            and gate["outcome"] == "finished"
            and not gate["stream_active"]
            and stamps["release_requested"]
            and stamps["response_finished"]
            and stamps["response_ended"]
            and gate["body_bytes_asgi_accepted"] == len(expected)
            and gate["body_sha256_asgi_accepted"] == sha256(expected),
            "Peer did not finish the exact original after Release",
            Failure,
        )
    else:
        require(
            gate["phase"] == "holding"
            and gate["outcome"] is None
            and gate["stream_active"]
            and 64 <= gate["body_bytes_asgi_accepted"] < len(expected)
            and stamps["release_requested"] is None
            and stamps["response_finished"] is None
            and stamps["response_ended"] is None,
            "Same transfer is no longer held incomplete",
            Failure,
        )
    return gate


def selected_document_uri(permissions: str, uid: str, filename: str, media_id: str) -> str:
    # Require a grant to this APK, not a URI mentioned for another app/provider.
    blocks = re.split(r"(?m)^\s*\*?\s*UID (\d+) holds:\s*$", permissions)
    matches = [blocks[i + 1] for i in range(1, len(blocks) - 1, 2) if blocks[i] == uid]
    require(len(matches) == 1, "Selected-document URI grant for this APK is unavailable")
    uris = set(re.findall(r"content://[^\s}\]]+", matches[0]))
    allowed = {
        f"content://com.android.externalstorage.documents/document/primary:Download/{filename}",
        f"content://com.android.providers.downloads.documents/document/raw:/storage/emulated/0/Download/{filename}",
        f"content://com.android.providers.downloads.documents/document/msf:{media_id}",
    }
    matched = [uri for uri in uris if unquote(uri) in allowed]
    require(len(matched) == 1, "Selected provider URI cannot be bound to the exact Downloads file")
    return matched[0]


def validate_identity_manifest(identity: dict) -> None:
    require(identity["app_commit"] == ACCEPTED_APP_COMMIT, "Wrong accepted app source")
    require(identity["app_tree"] == ACCEPTED_APP_TREE, "Wrong accepted app tree")
    for key in ("dependency_inputs_sha256", "fixture_sha256", "apk_sha256", "apk_signer_sha256"):
        require(
            isinstance(identity[key], str) and re.fullmatch(r"[0-9a-f]{64}", identity[key]), f"Missing identity {key}"
        )
    for key in ("simulator_dependencies", "build_tools", "action_pins"):
        require(
            isinstance(identity[key], dict)
            and identity[key]
            and all(isinstance(k, str) and k and isinstance(v, str) and v.strip() for k, v in identity[key].items()),
            f"Missing identity {key}",
        )
    require(
        identity["fresh_ephemeral_emulator"] is True and identity["avd_hw_keyboard"] in {"yes", "no"},
        "Fresh emulator and actual AVD keyboard configuration are required",
    )
    require(isinstance(identity["build_command"], str) and identity["build_command"].strip(), "Missing build command")
    require(
        identity["version_name"] == "0.13.0" and str(identity["version_code"]) == "29",
        "Version differs from frozen accepted source",
    )


def has_crash_evidence(app_logs: str, system_logs: str) -> bool:
    # ANRs are normally reported by system_server, outside the original app PID.
    return bool(
        re.search(r"FATAL EXCEPTION|ANR in " + re.escape(APP) + r"\b", app_logs)
        or re.search(r"(?:ANR in |Process:\s*)" + re.escape(APP) + r"(?=[,\s/}]|$)", system_logs)
    )


class Driver:
    def __init__(self, args, budget=None):
        self.args, self.budget = args, budget or Budget(MAX_SECONDS - 25)
        self.output = args.output_dir
        self.output.mkdir(parents=True, exist_ok=False)
        self.stage, self.gate_id, self.recorder, self.remote_pid = "identity", None, None, None
        self.screen = None
        self.pid = None
        self.uid = None
        self.log_start = None
        self.hold_deadline = None
        self.expected = args.expected_original.read_bytes()
        require(245 <= len(self.expected) <= 8 * 1024 * 1024, "Unexpected reference original size")
        self.result = {
            "schema": 1,
            "outcome": "NOT EXERCISED",
            "stage": self.stage,
            "reason": "Journey not completed",
            "claim": None,
            "original_root_cause": "UNKNOWN",
            "physical_camera": False,
            "final_acceptance": False,
            "release_hold": True,
            "visual_review": {
                "status": "PENDING",
                "reviewer": "owner",
                "required": "Interpret keyboard pixels before Apply and dismissal after Apply in PNG/video evidence",
            },
            "app_component": COMPONENT,
            "api": 36,
            "milestones": {},
            "cleanup": {},
        }

    def write(self, name, data):
        path = self.output / name
        if isinstance(data, bytes):
            path.write_bytes(data)
        elif isinstance(data, str):
            path.write_text(data, encoding="utf-8")
        else:
            path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    def command(self, args: list[str], *, cap=8 * 1024 * 1024) -> bytes:
        try:
            completed = subprocess.run(args, capture_output=True, timeout=self.budget.remaining(), check=False)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise NotExercised(f"Command unavailable or exceeded its deadline: {args[:3]}") from exc
        require(completed.returncode == 0, f"Command failed: {args[:3]}; exit={completed.returncode}")
        require(len(completed.stdout) <= cap, "Command evidence exceeded bounded size")
        return completed.stdout

    def adb(self, *args) -> str:
        return self.command([self.args.adb, *args]).decode("utf-8", errors="strict")

    def peer(self, path="/ccapi/test/state", body=None, *, post=False):
        request = Request(
            PEER + path,
            data=json.dumps(body).encode() if body is not None else None,
            method="POST" if post else "GET",
            headers={"Content-Type": "application/json"},
        )
        try:
            with urlopen(request, timeout=self.budget.remaining(5)) as response:
                data = response.read(1024 * 1024 + 1)
            require(len(data) <= 1024 * 1024, "Peer evidence exceeded bounded size")
            return json.loads(data)
        except (OSError, ValueError) as exc:
            raise NotExercised(f"Peer observation unavailable: {path}") from exc

    def snapshot(self, package=APP):
        self.check_deadline()
        raw = self.adb("exec-out", "uiautomator", "dump", "/dev/tty")
        start, end = raw.find("<?xml"), raw.rfind("</hierarchy>")
        require(start >= 0 and end >= start, "Accessibility dump has no complete hierarchy")
        xml = raw[start : end + len("</hierarchy>")]
        windows = self.adb("shell", "dumpsys", "window", "windows")
        window = focused_window(windows, package).intersect(self.screen)
        tree = Tree(xml, package, window)
        require(any(tree.eligible(n) for n in tree.root.iter("node")), "Foreground package has no exported nodes")
        return tree, xml, windows

    def check_deadline(self):
        self.budget.remaining()
        if self.hold_deadline is not None:
            require(
                time.monotonic() < self.hold_deadline, "120-second held-transfer interaction budget exhausted", Failure
            )

    def wait_label(self, label, package=APP, *, error=NotExercised):
        return self.budget.poll(
            label, lambda: snap if (snap := self.snapshot(package))[0].has(label) else None, error=error
        )

    def tap(self, label, package=APP, *, editable=False, scroll=False, require_ime_shown=False):
        last_xml = None
        for attempt in range(4 if scroll else 1):
            tree, xml, windows = self.snapshot(package)
            ime = self.adb("shell", "dumpsys", "input_method")
            visibility = re.findall(r"\bmInputShown=(true|false)\b", ime)
            require(len(visibility) == 1, "Unsupported input-method visibility before tap")
            require(not require_ime_shown or visibility[0] == "true", "Required visible IME disappeared before tap")
            tree.occlusion = ime_region(ime, windows, self.screen, shown=visibility[0] == "true")
            node = tree.target(label, editable=editable)
            if node is not None:
                try:
                    bounds = tree.bounds(node)
                except NotExercised:
                    if not scroll:
                        raise
                else:
                    x, y = bounds.center
                    self.adb("shell", "input", "tap", str(x), str(y))
                    return
            require(scroll and attempt < 3, f"No visible unique target: {label}")
            require(xml != last_xml, "Dialog scroll did not change the live tree")
            last_xml = xml
            # Only the date dialog's one exported vertical scroll container.
            require(tree.has("Media date range"), "Date dialog scope unavailable for scroll")
            containers = [n for n in tree.root.iter("node") if tree.eligible(n) and n.get("scrollable") == "true"]
            require(len(containers) == 1, "Ambiguous date-dialog scroll container")
            area = tree.bounds(containers[0])
            x, _ = area.center
            span = area.bottom - area.top
            require(span >= 80, "No visible dialog scroll space above IME")
            self.adb(
                "shell",
                "input",
                "swipe",
                str(x),
                str(area.bottom - span // 5),
                str(x),
                str(area.top + span // 5),
                "300",
            )

    def fill(self, label, value, *, package=APP):
        require(re.fullmatch(r"[A-Za-z0-9:/._-]+", value), "Unsafe ordinary input text")
        self.tap(label, package, editable=True, scroll=label in {"From (inclusive)", "To (inclusive)"})
        self.adb("shell", "input", "keycombination", "113", "29")  # Ctrl+A, normal platform input.
        self.adb("shell", "input", "text", value)

        def matches():
            tree, _, windows = self.snapshot(package)
            node = tree.target(label, editable=True)
            entered = node is not None and tree.text(node) == value
            if label in {"From (inclusive)", "To (inclusive)"}:
                # Text and the real platform IME settle within the same 20s transition.
                shown, region = ime_observation(self.adb("shell", "dumpsys", "input_method"), windows, self.screen)
                return entered and shown and region is not None
            return entered

        self.budget.poll(
            f"entered {label}",
            matches,
            error=Failure if label in {"From (inclusive)", "To (inclusive)"} else NotExercised,
        )

    def wait_ime(self, *, shown: bool, label=None):
        def ready():
            self.check_deadline()
            if label is not None:
                tree, _, windows = self.snapshot()
                label_ready = tree.has(label)
            else:
                windows = self.adb("shell", "dumpsys", "window", "windows")
                label_ready = True
            input_shown, region = ime_observation(self.adb("shell", "dumpsys", "input_method"), windows, self.screen)
            return label_ready and input_shown == shown and (region is not None) == shown

        # A recognized in-flight frame is not a verdict; malformed evidence still raises.
        self.budget.poll(f"IME {'shown' if shown else 'hidden'}", ready, error=Failure)

    def milestone(self, name, *, package=APP, ime_shown=None):
        tree, xml, windows = self.snapshot(package)
        ime = self.adb("shell", "dumpsys", "input_method")
        region = ime_region(ime, windows, self.screen, shown=ime_shown) if ime_shown is not None else None
        png = self.command([self.args.adb, "exec-out", "screencap", "-p"])
        require(
            png.startswith(b"\x89PNG\r\n\x1a\n")
            and len(png) > 100
            and png[12:16] == b"IHDR"
            and png[-12:] == b"\x00\x00\x00\x00IEND\xaeB`\x82",
            "Screenshot evidence is missing or corrupt",
        )
        width, height = struct.unpack(">II", png[16:24])
        require((width, height) == (self.screen.right, self.screen.bottom), "Screenshot geometry changed")
        self.write(name + ".xml", xml)
        self.write(name + "-windows.txt", windows)
        self.write(name + "-ime.txt", ime)
        self.write(name + ".png", png)
        self.result["milestones"][name] = {
            "host_elapsed_ms": round((time.monotonic() - self.budget.started) * 1000),
            "ime_region": list(region.__dict__.values()) if region else None,
        }
        return tree

    def identity(self):
        identity = json.loads(self.args.identity.read_text(encoding="utf-8"))
        validate_identity_manifest(identity)
        fixture_path = Path(__file__).resolve().parents[2] / "simulator" / "main.py"
        require(sha256(fixture_path.read_bytes()) == identity["fixture_sha256"], "Fixture source differs from manifest")
        apk_hash = sha256(self.args.apk.read_bytes())
        require(apk_hash == identity["apk_sha256"], "Input APK differs from manifest")
        badging = self.command([self.args.aapt, "dump", "badging", str(self.args.apk)]).decode()
        require(
            f"package: name='{APP}'" in badging
            and f"launchable-activity: name='{ACTIVITY}'" in badging
            and "application-debuggable" in badging,
            "APK is not the ordinary isolated debug launcher",
        )
        signer = self.command([self.args.apksigner, "verify", "--print-certs", str(self.args.apk)]).decode()
        digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", signer)
        require(digests == [identity["apk_signer_sha256"]], "APK signer differs from manifest")
        require(self.adb("shell", "getprop", "ro.build.version.sdk").strip() == "36", "Requires API 36")
        require(self.adb("shell", "getprop", "ro.kernel.qemu").strip() == "1", "Requires an ephemeral emulator")
        sizes = re.findall(r"(?:Physical|Override) size: (\d+)x(\d+)", self.adb("shell", "wm", "size"))
        require(sizes, "Display size unavailable")
        self.screen = Rect(0, 0, *map(int, sizes[-1]))
        paths = self.adb("shell", "pm", "path", APP).strip().splitlines()
        require(
            len(paths) == 1 and re.fullmatch(r"package:/data/app/[A-Za-z0-9_=/+.-]+/base\.apk", paths[0]),
            "Installed APK is split or unreadable",
        )
        installed_hash = self.adb("shell", "sha256sum", paths[0][8:]).split()[0]
        require(installed_hash == apk_hash, "Installed base APK differs from input APK")
        launcher = self.adb(
            "shell",
            "cmd",
            "package",
            "resolve-activity",
            "--brief",
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.LAUNCHER",
            APP,
        )
        require(COMPONENT in launcher.splitlines(), "Installed launcher differs from expected full component")
        package = self.adb("shell", "dumpsys", "package", APP)
        require(
            APP not in self.adb("shell", "pm", "list", "instrumentation"),
            "An instrumentation target is installed for this APK",
        )
        require(
            f"versionCode={identity['version_code']} " in package
            and f"versionName={identity['version_name']}" in package,
            "Installed version mismatch",
        )
        uid = re.findall(r"\buserId=(\d+)\b", package)
        require(len(set(uid)) == 1, "APK UID unavailable")
        self.uid = uid[0]
        settings = {}
        for key in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            value = self.adb("shell", "settings", "get", "global", key).strip()
            require(value == "1.0" or value == "1", f"Normal animation scale required: {key}={value}")
            settings[key] = value
        settings["show_ime_with_hard_keyboard"] = self.adb(
            "shell", "settings", "get", "secure", "show_ime_with_hard_keyboard"
        ).strip()
        settings["default_input_method"] = self.adb(
            "shell", "settings", "get", "secure", "default_input_method"
        ).strip()
        require(settings["default_input_method"] not in {"", "null"}, "Platform IME unavailable")
        identity.update(
            {
                "driver_sha256": sha256(Path(__file__).read_bytes()),
                "source_provenance": "build-job manifest",
                "installed_apk_sha256": installed_hash,
                "display": sizes[-1],
                "density": self.adb("shell", "wm", "density").strip(),
                "fingerprint": self.adb("shell", "getprop", "ro.build.fingerprint").strip(),
                "locale": self.adb("shell", "getprop", "persist.sys.locale").strip(),
                "timezone": self.adb("shell", "getprop", "persist.sys.timezone").strip(),
                "adb_version": self.adb("version").strip(),
                "settings": settings,
            }
        )
        require(identity["locale"].startswith("en"), "Only the ordinary English UI is supported")
        self.write("identity.json", identity)
        self.start_log_window()
        self.adb(
            "shell",
            "am",
            "start",
            "-W",
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.LAUNCHER",
            "-n",
            COMPONENT,
        )
        self.wait_label("HTTP")
        self.pid = self.adb("shell", "pidof", APP).strip()
        require(re.fullmatch(r"\d+", self.pid), "Ordinary app PID unavailable")

    def start_log_window(self):
        # adb shell joins arguments remotely; keep this date format space-free.
        raw = self.adb("shell", "date", "+%m-%dT%H:%M:%S.000").strip()
        require(re.fullmatch(r"\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.000", raw), "Device log time boundary unavailable")
        self.log_start = raw.replace("T", " ")
        self.result["log_slice"] = {
            "device_start": self.log_start,
            "system_tags": ["ActivityManager", "ActivityTaskManager", "AndroidRuntime"],
        }

    def downloads(self):
        text = self.adb("shell", "ls", "-1A", "/sdcard/Download")
        return text.splitlines()

    def start_recording(self):
        self.remote_video = "/sdcard/eos-normal-app-acceptance.mp4"
        require(
            not self.adb(
                "shell", "find", "/sdcard", "-maxdepth", "1", "-name", "eos-normal-app-acceptance.mp4"
            ).strip(),
            "Recording destination already exists",
        )
        command = f"echo $$; exec screenrecord --time-limit 180 {self.remote_video}"
        self.recorder = subprocess.Popen(
            [self.args.adb, "shell", "sh", "-c", shlex.quote(command)],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        ready, _, _ = select.select([self.recorder.stdout], [], [], self.budget.remaining(5))
        require(ready, "Recording process identity unavailable")
        pid = self.recorder.stdout.readline().decode().strip()
        require(re.fullmatch(r"\d+", pid), "Recording PID could not be verified")
        self.remote_pid = pid
        self.write(
            "before-date-logcat.txt",
            self.adb("logcat", "-d", "-T", self.log_start, "--pid", self.pid, "-v", "threadtime"),
        )

    def held(self):
        return validate_hold(self.peer(), self.gate_id, self.expected, self.original_baseline, item_id=self.filename)

    def journey(self):
        self.identity()
        self.stage = "connect-and-capture"
        initial = self.peer()
        require(
            not initial["capture_delivery"]["captured_ids"]
            and initial["capture_delivery"].get("original_hold") is None,
            "Fixture is not fresh; refusing reset or reuse",
        )
        require(not self.downloads(), "Ephemeral Downloads destination is not empty")
        fixture = self.peer("/ccapi/test/capture-delivery", {"enabled": True}, post=True)
        require(
            fixture["original_delay_ms"] == 0 and fixture["original_failures"] == 0,
            "Fixture original has another delay/failure configured",
        )
        self.original_baseline = fixture["representation_get_counts"]["original"]
        require(self.original_baseline == 0, "Fixture already observed an original request")
        self.tap("Recent downloads")
        self.wait_label("No download records to show.")
        self.tap("Close")
        self.tap("HTTP")
        self.fill("Camera URL", CAMERA_URL)
        self.adb("shell", "input", "keyevent", "KEYCODE_BACK")
        self.tap("Connect")
        self.wait_label("Capture photo")
        self.tap("Capture photo")

        def captured():
            state = self.peer()
            ids = state["capture_delivery"]["captured_ids"]
            require(len(ids) <= 1, "More than one capture occurred", Failure)
            return state if len(ids) == 1 else None

        capture = self.budget.poll("one Canon capture", captured)
        self.filename = capture["capture_delivery"]["captured_ids"][0]
        require(re.fullmatch(r"SIM_\d{4}\.JPG", self.filename), "Unexpected synthetic capture filename")
        require(
            len(capture["canonical"]["shutter_af_requests"]) == len(initial["canonical"]["shutter_af_requests"]) + 1,
            "Ordinary Canon shutter route was not observed",
            Failure,
        )
        info = self.peer(f"/ccapi/ver100/contents/card1/100CANON/{self.filename}?kind=info")
        stamp = datetime.fromisoformat(info["lastmodifieddate"])
        require(stamp.year > 2001, "Capture date cannot be proven outside the fixed filter")
        self.result["capture"] = {
            "filename": self.filename,
            "camera_timestamp": stamp.isoformat(),
            "applied_start": DATE,
            "applied_end": DATE,
        }
        self.tap("More actions")
        self.wait_label("Camera media")
        self.tap("Camera media")
        self.wait_label(f"Preview {self.filename}")
        self.tap(f"Preview {self.filename}")
        self.wait_label(f"Actions for {self.filename}")
        preview = self.milestone("preview")
        require(
            preview.has("Close media preview") and preview.has(f"Preview of {self.filename}"),
            "The exact captured JPEG preview has not been observed",
        )
        self.tap(f"Actions for {self.filename}")
        self.wait_label("Save to another folder")
        self.tap("Save to another folder")
        self.stage = "create-document"
        activity = self.adb("shell", "dumpsys", "activity", "top")
        require(
            "android.intent.action.CREATE_DOCUMENT" in activity
            and any(name in activity for name in (".picker.PickActivity", ".DocumentsActivity")),
            "Platform CreateDocument activity cannot be established",
        )
        packages = [p for p in PROVIDER_PACKAGES if p + "/" in activity]
        require(len(packages) == 1, "Platform document picker package is ambiguous")
        picker = packages[0]
        self.write("create-document-activity.txt", activity)
        self.tap("Show roots", picker)
        self.wait_label("Downloads", picker)
        self.tap("Downloads", picker)
        tree = self.milestone("selected-downloads", package=picker)
        require(tree.has("Downloads"), "Selected Downloads directory has no live breadcrumb")
        edits = [n for n in tree.root.iter("node") if tree.eligible(n) and n.get("class") == "android.widget.EditText"]
        require(
            len(edits) == 1 and edits[0].get("text") == self.filename,
            "CreateDocument filename differs from captured JPEG",
        )
        gate = self.peer("/ccapi/test/capture-delivery/original-hold", {"item_id": self.filename}, post=True)
        self.gate_id = gate["gate_id"]
        require(re.fullmatch(r"[0-9a-f-]{36}", self.gate_id), "Invalid gate identity")
        require(
            gate["expected_byte_count"] == len(self.expected)
            and gate["expected_sha256"] == sha256(self.expected)
            and fixture["representations"]["original"]["byte_count"] == len(self.expected)
            and fixture["representations"]["original"]["sha256"] == sha256(self.expected),
            "Frozen reference bytes do not match the armed original",
        )
        self.write("armed-original.json", gate)
        picker_tree = self.snapshot(picker)[0]
        save_labels = [label for label in ("Save", "SAVE") if picker_tree.has(label)]
        require(len(save_labels) == 1, "Platform picker has no unique Save control")
        # Start conservatively before picker confirmation, never after observing an old prefix.
        self.hold_deadline = time.monotonic() + 120
        self.tap(save_labels[0], picker)
        self.stage = "held-transfer"

        def holding():
            state = self.peer()
            gate = state["capture_delivery"]["original_hold"]
            if gate["phase"] in {"armed", "starting"}:
                return None
            return validate_hold(state, self.gate_id, self.expected, self.original_baseline, item_id=self.filename)

        gate = self.budget.poll("one held original after prefix", holding, error=Failure)
        self.wait_label(f"Downloading {self.filename}")
        self.milestone("held-before-date")
        self.start_recording()
        self.tap("Media date range")
        self.wait_label("From (inclusive)")
        self.fill("From (inclusive)", DATE)
        self.fill("To (inclusive)", DATE)
        self.stage = "visible-ime-before-apply"
        # Scroll only to reveal Apply; do not activate it until all witnesses exist.
        self.reveal_apply()
        tree = self.milestone("before-apply", ime_shown=True)
        require(tree.has("Media date range"), "Date dialog is not observed before Apply")
        tree.occlusion = ime_region(
            self.adb("shell", "dumpsys", "input_method"),
            self.adb("shell", "dumpsys", "window", "windows"),
            self.screen,
            shown=True,
        )
        apply = tree.target("Apply")
        require(apply is not None, "Unique Apply target is unavailable immediately before Apply")
        tree.bounds(apply)
        for label in ("From (inclusive)", "To (inclusive)"):
            node = tree.target(label, editable=True)
            require(
                node is not None and tree.text(node) == DATE,
                "Both date fields must be observed immediately before Apply",
            )
        self.write("held-before-apply.json", self.held())
        self.tap("Apply", require_ime_shown=True)
        self.stage = "dismissal-while-held"
        self.wait_ime(shown=False, label=f"Media date: {DATE} to {DATE}")
        tree = self.milestone("after-apply", ime_shown=False)
        require(
            not tree.has("From (inclusive)") and not tree.has("To (inclusive)") and not tree.has("Apply"),
            "Date dialog did not dismiss",
            Failure,
        )
        require(
            tree.has(f"Downloading {self.filename}")
            and not tree.has(f"Preview {self.filename}")
            and not tree.has(f"Actions for {self.filename}"),
            "Active file remains in the filtered gallery or saving state vanished",
            Failure,
        )
        summaries = [n.get("text", "") for n in tree.root.iter("node") if tree.eligible(n)]
        require(
            any(
                re.fullmatch(r"0 of [1-9]\d* loaded items · \d+ missing or invalid dates excluded", t)
                for t in summaries
            ),
            "Zero matching loaded items cannot be established",
        )
        self.write("held-after-apply.json", self.held())
        require(self.adb("shell", "pidof", APP).strip() == self.pid, "Ordinary app process restarted", Failure)
        self.check_deadline()
        self.peer(f"/ccapi/test/capture-delivery/original-hold/{self.gate_id}/release", post=True)
        self.hold_deadline = None
        self.stage = "release-and-destination"
        # This deadline applies to every command, history check, and byte verification.
        self.budget.deadline = min(self.budget.deadline, time.monotonic() + 30)

        def finished():
            state = self.peer()
            gate = state["capture_delivery"]["original_hold"]
            if gate["phase"] in {"holding", "releasing"}:
                return None
            return validate_hold(
                state, self.gate_id, self.expected, self.original_baseline, finished=True, item_id=self.filename
            )

        self.write("finished-original.json", self.budget.poll("peer response finished", finished, error=Failure))
        self.wait_label(f"Saved {self.filename}", error=Failure)
        self.milestone("saved")
        self.tap("Recent downloads")
        self.wait_label("Completed", error=Failure)
        tree = self.milestone("completed-history")
        for label in (self.filename, "Completed", "Destination: Selected file"):
            require(
                len(tree.nodes(label)) == 1,
                "Exactly one matching completed document-history entry is required",
                Failure,
            )
        require(
            not any(
                tree.has(t)
                for t in (
                    "Failed",
                    "Cancelled",
                    "In progress",
                    "Unconfirmed after app restart",
                    "Destination: Gallery",
                    "Destination: Selected folder",
                )
            ),
            "History has an unexpected result or destination",
            Failure,
        )
        texts = "\n".join(n.get("text", "") for n in tree.root.iter("node"))
        require(
            not any(
                t in texts
                for t in (
                    "An incomplete download may remain",
                    "could not be read",
                    "could not be saved",
                    "unavailable in this app session",
                )
            ),
            "History or cleanup warning present",
            Failure,
        )
        self.verify_destination()
        require(self.adb("shell", "pidof", APP).strip() == self.pid, "Ordinary app process restarted", Failure)
        self.write(
            "final-original.json",
            validate_hold(
                self.peer(), self.gate_id, self.expected, self.original_baseline, finished=True, item_id=self.filename
            ),
        )

    def reveal_apply(self):
        # Same bounded scrolling as tap, but never issue the Apply tap here.
        previous = None
        for _ in range(4):
            tree, xml, windows = self.snapshot()
            require(tree.has("Media date range"), "Date dialog no longer present")
            tree.occlusion = ime_region(self.adb("shell", "dumpsys", "input_method"), windows, self.screen, shown=True)
            target = tree.target("Apply")
            if target is not None:
                try:
                    tree.bounds(target)
                    return
                except NotExercised:
                    pass
            require(xml != previous, "Date-dialog scroll made no observed progress")
            previous = xml
            nodes = [n for n in tree.root.iter("node") if tree.eligible(n) and n.get("scrollable") == "true"]
            require(len(nodes) == 1, "Cannot identify one live date-dialog scroll region")
            area = tree.bounds(nodes[0])
            span = area.bottom - area.top
            require(span >= 80, "No usable scroll region above IME")
            self.adb(
                "shell",
                "input",
                "swipe",
                str(area.center[0]),
                str(area.bottom - span // 5),
                str(area.center[0]),
                str(area.top + span // 5),
                "300",
            )
        raise NotExercised("Apply cannot be revealed above the visible IME")

    def verify_destination(self):
        require(self.downloads() == [self.filename], "Downloads contains duplicate or partial scenario output", Failure)
        query = self.adb(
            "shell",
            "content",
            "query",
            "--uri",
            "content://media/external/file",
            "--projection",
            "_id:_display_name:relative_path:_size:is_pending",
            "--where",
            shlex.quote(f"_display_name='{self.filename}'"),
        )
        self.write("destination-provider-row.txt", query)
        rows = [line for line in query.splitlines() if line.startswith("Row:")]
        require(len(rows) == 1, "Selected destination has no unique externally readable provider row")
        fields = dict(re.findall(r"\b(_id|_display_name|relative_path|_size|is_pending)=([^,\n]+)", rows[0]))
        require(
            re.fullmatch(r"\d+", fields.get("_id", ""))
            and fields.get("_display_name") == self.filename
            and fields.get("relative_path") == "Download/"
            and fields.get("_size") == str(len(self.expected))
            and fields.get("is_pending") == "0",
            "Provider destination differs, is partial, or is still pending",
            Failure,
        )
        permissions = self.adb("shell", "dumpsys", "activity", "permissions")
        uri = selected_document_uri(permissions, self.uid, self.filename, fields["_id"])
        self.write("destination-uri-grants.txt", permissions)
        data = self.command([self.args.adb, "exec-out", "cat", f"/sdcard/Download/{self.filename}"])
        require(data == self.expected, "Selected destination bytes differ from frozen original", Failure)
        self.write("selected-document.jpg", data)
        self.result["destination"] = {
            "uri": uri,
            "path": f"/sdcard/Download/{self.filename}",
            "byte_count": len(data),
            "sha256": sha256(data),
            "byte_equal": True,
        }

    def cleanup(self):
        # Independent short reserve. Cleanup cannot turn a failed journey into PASS.
        self.budget = Budget(25)
        self.hold_deadline = None
        if self.gate_id:
            try:
                snapshot = self.peer()
                state = snapshot["capture_delivery"]["original_hold"]
                require(state and state["gate_id"] == self.gate_id, "Cleanup gate identity changed", Failure)
                if state["outcome"] == "finished":
                    validate_hold(
                        snapshot,
                        self.gate_id,
                        self.expected,
                        self.original_baseline,
                        finished=True,
                        item_id=self.filename,
                    )
                if state and state["outcome"] != "finished" and state["phase"] != "terminal":
                    self.peer(f"/ccapi/test/capture-delivery/original-hold/{self.gate_id}/cancel", post=True)
                    self.budget.poll(
                        "controlled response cleanup",
                        lambda: not self.peer()["capture_delivery"]["original_hold"]["stream_active"],
                        seconds=5,
                    )
                self.write("cleanup-original.json", self.peer()["capture_delivery"]["original_hold"])
                if state and state["outcome"] != "finished":
                    tree, xml, _ = self.snapshot()
                    self.write("cleanup-app.xml", xml)
                    self.result["cleanup"]["apk_failure_observed"] = any(
                        "Could not save the original:" in n.get("text", "") or n.get("text") == "Download cancelled."
                        for n in tree.root.iter("node")
                    )
                    self.result["cleanup"]["remaining_downloads"] = self.downloads()
            except OBSERVATION_ERRORS as exc:
                self.result["cleanup"]["peer"] = str(exc)
        if self.recorder:
            try:
                if self.remote_pid:
                    command = self.adb("shell", "cat", f"/proc/{self.remote_pid}/cmdline")
                    require(
                        "screenrecord" in command and self.remote_video in command,
                        "Recording process ownership no longer matches",
                    )
                    self.adb("shell", "kill", "-INT", self.remote_pid)
                self.recorder.wait(timeout=self.budget.remaining(5))
                self.adb("pull", self.remote_video, str(self.output / "ime-journey.mp4"))
                require((self.output / "ime-journey.mp4").stat().st_size > 100, "Recording file is empty")
                self.result["cleanup"]["recording"] = "stopped and pulled"
            except OBSERVATION_ERRORS as exc:
                self.result["cleanup"]["recording"] = str(exc)
            finally:
                if self.recorder.poll() is None:
                    self.recorder.terminate()
                    try:
                        self.recorder.wait(timeout=2)
                    except subprocess.TimeoutExpired:
                        self.recorder.kill()
                        self.recorder.wait(timeout=2)
        if self.pid or self.log_start:
            try:
                require(self.log_start, "Bounded device log start is unavailable")
                logs = ""
                if self.pid:
                    logs = self.adb("logcat", "-d", "-T", self.log_start, "--pid", self.pid, "-v", "threadtime")
                    self.write("app-logcat.txt", logs)
                else:
                    self.result["cleanup"]["app_logcat"] = "App PID unavailable"
                system_logs = self.adb(
                    "logcat",
                    "-d",
                    "-T",
                    self.log_start,
                    "-v",
                    "threadtime",
                    "ActivityManager:I",
                    "ActivityTaskManager:I",
                    "AndroidRuntime:E",
                    "*:S",
                )
                self.write("system-logcat.txt", system_logs)
                self.result["cleanup"]["logcat"] = "bounded app and system slices saved"
                if has_crash_evidence(logs, system_logs):
                    self.result.update(outcome="FAIL", reason="Crash or ANR in app/system log evidence")
            except OBSERVATION_ERRORS as exc:
                self.result["cleanup"]["logcat"] = str(exc)

    def run(self):
        try:
            self.journey()
            self.result.update(
                outcome="PASS",
                reason="Automated journey observations passed; owner visual review pending",
                claim=("Same held transfer survived machine-observed IME/dialog dismissal "
                       "and completed after explicit Release"),
            )
        except Failure as exc:
            self.result.update(outcome="FAIL", reason=str(exc))
        except OBSERVATION_ERRORS as exc:
            self.result.update(outcome="NOT EXERCISED", reason=f"{type(exc).__name__}: {exc}")
        finally:
            self.result["stage"] = self.stage
            if self.screen and self.result["outcome"] != "PASS":
                try:
                    self.milestone("last-observed")
                except OBSERVATION_ERRORS as exc:
                    self.result["last_observation_gap"] = str(exc)
            try:
                self.cleanup()
            except OBSERVATION_ERRORS as exc:
                self.result["cleanup"]["driver"] = f"{type(exc).__name__}: {exc}"
            expected_cleanup = {"recording": "stopped and pulled", "logcat": "bounded app and system slices saved"}
            required = {
                "identity.json",
                "armed-original.json",
                "held-before-apply.json",
                "held-after-apply.json",
                "final-original.json",
                "before-apply.png",
                "after-apply.png",
                "ime-journey.mp4",
                "app-logcat.txt",
                "system-logcat.txt",
                "selected-document.jpg",
                "destination-uri-grants.txt",
            }
            available = {p.name for p in self.output.iterdir() if p.is_file()}
            if self.result["outcome"] == "PASS" and (
                self.result["cleanup"] != expected_cleanup or not required <= available
            ):
                self.result.update(
                    outcome="NOT EXERCISED",
                    reason="Required final evidence or owned-process cleanup unavailable",
                    claim=None,
                )
            if self.result["outcome"] != "PASS":
                self.result["claim"] = None
            files = {
                p.name: {"bytes": p.stat().st_size, "sha256": sha256(p.read_bytes())}
                for p in sorted(self.output.iterdir())
                if p.is_file() and p.name != "result.json"
            }
            self.result["evidence"] = files
            self.result["evidence_manifest_sha256"] = sha256(json.dumps(files, sort_keys=True).encode())
            self.write("result.json", self.result)
        return {"PASS": 0, "FAIL": 1, "NOT EXERCISED": 2}[self.result["outcome"]]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--identity", required=True, type=Path)
    parser.add_argument("--expected-original", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--aapt", default="aapt")
    parser.add_argument("--apksigner", default="apksigner")
    args = parser.parse_args(argv)
    try:
        return Driver(args).run()
    except (NotExercised, OSError, ValueError) as exc:
        parser.exit(2, f"NOT EXERCISED: {exc}\n")


if __name__ == "__main__":
    raise SystemExit(main())
