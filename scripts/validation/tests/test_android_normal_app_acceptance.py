"""Deterministic host-only checks; never launch an APK, ADB, or a peer server."""

from __future__ import annotations

import json
import struct
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zlib
from copy import deepcopy
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch
from urllib.parse import quote

from scripts.validation import android_normal_app_acceptance as driver

DATA = bytes(range(256)) * 2
FILENAME = "SIM_0003.JPG"
GATE = "11111111-1111-4111-8111-111111111111"
SCREEN = driver.Rect(0, 0, 1080, 1920)
IME = driver.Rect(0, 1200, 1080, 1920)


def node(text="", **attrs):
    values = {
        "package": driver.APP,
        "enabled": "true",
        "visible-to-user": "true",
        "clickable": "true",
        "class": "android.widget.Button",
        "text": text,
        "bounds": "[20,20][220,120]",
    }
    values.update(attrs)
    return ET.Element("node", values)


def tree(*nodes, window=SCREEN, occlusion=None):
    root = ET.Element("hierarchy")
    root.extend(nodes)
    return driver.Tree(ET.tostring(root, encoding="unicode"), driver.APP, window, occlusion)


def window_dump(*, ime=False, focus=driver.APP, frame="mFrame=", visible=True):
    shown = "true" if visible else "false"
    value = (
        f"mCurrentFocus=Window{{abc u0 {focus}/.MainActivity}}\n"
        f"  Window #0 Window{{abc u0 {focus}/.MainActivity}}:\n"
        f"    mHasSurface={shown} isVisible={shown} mViewVisibility=0x0\n"
        f"    {frame}[0,0][1080,1920]\n"
    )
    if ime:
        value += (
            "  Window #1 Window{def u0 InputMethod}:\n"
            "    ty=INPUT_METHOD mHasSurface=true isVisible=true mViewVisibility=0x0\n"
            f"    {frame}[0,1200][1080,1920]\n"
        )
    return value


def state(*, finished=False):
    timestamps = dict.fromkeys(
        (
            "armed",
            "request_arrived",
            "headers_asgi_accepted",
            "prefix_asgi_accepted",
            "last_drip_asgi_accepted",
            "release_requested",
            "cancel_requested",
            "disconnect_observed",
            "expired",
            "response_finished",
            "response_ended",
            "stream_error",
        )
    )
    for index, key in enumerate(("armed", "request_arrived", "headers_asgi_accepted", "prefix_asgi_accepted")):
        timestamps[key] = {"elapsed_ms": index, "utc": "2026-01-01T00:00:00Z"}
    if finished:
        for index, key in enumerate(("release_requested", "response_finished", "response_ended"), start=4):
            timestamps[key] = {"elapsed_ms": index, "utc": "2026-01-01T00:00:01Z"}
    size = len(DATA) if finished else 64
    gate = {
        "gate_id": GATE,
        "request_id": GATE + "/1",
        "request_query": "",
        "item_id": FILENAME,
        "target_path": f"/ccapi/ver100/contents/card1/100CANON/{FILENAME}",
        "phase": "terminal" if finished else "holding",
        "outcome": "finished" if finished else None,
        "stream_active": not finished,
        "expected_byte_count": len(DATA),
        "expected_sha256": driver.sha256(DATA),
        "prefix_bytes": 64,
        "drip_interval_ms": 1000,
        "max_hold_ms": 180000,
        "body_bytes_asgi_accepted": size,
        "body_sha256_asgi_accepted": driver.sha256(DATA[:size]),
        "rejected_original_count": 0,
        "timestamps": timestamps,
    }
    return {
        "capture_delivery": {
            "original_hold": gate,
            "captured_ids": [FILENAME],
            "representation_get_counts": {"original": 1},
            "representations": {"original": {"byte_count": len(DATA), "sha256": driver.sha256(DATA)}},
        }
    }


def manifest():
    return {
        "app_commit": driver.ACCEPTED_APP_COMMIT,
        "app_tree": driver.ACCEPTED_APP_TREE,
        "dependency_inputs_sha256": "1" * 64,
        "fixture_sha256": "2" * 64,
        "apk_sha256": "3" * 64,
        "apk_signer_sha256": "4" * 64,
        "version_code": 29,
        "version_name": "0.13.0",
        "build_command": "./gradlew :app:assembleDebug -PlocalDebugApplicationIdSuffix=true",
        "simulator_dependencies": {"uvicorn": "0.30.0"},
        "build_tools": {"gradle": "9.0"},
        "action_pins": {"actions/checkout": "3d3c42e5aac5ba805825da76410c181273ba90b1"},
        "avd_hw_keyboard": "no",
        "fresh_ephemeral_emulator": True,
    }


def png_bytes(width=1080, height=1920):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress((b"\x00" * (width + 1)) * height))
        + chunk(b"IEND", b"")
    )


class FakeClock:
    def __init__(self):
        self.now = 10.0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class TreeTests(unittest.TestCase):
    def test_unique_clickable_and_labeled_ancestor(self):
        parent = node("", **{"class": "android.widget.LinearLayout"})
        parent.append(node("Apply", clickable="false"))
        live = tree(parent)
        self.assertIs(live.target("Apply"), live.root[0])
        self.assertEqual(live.bounds(live.target("Apply")).center, (120, 70))

    def test_duplicate_labels_are_rejected(self):
        with self.assertRaisesRegex(driver.NotExercised, "Ambiguous live label"):
            tree(node("Apply"), node("Apply")).target("Apply")

    def test_hidden_disabled_and_wrong_package_are_not_targets(self):
        for attrs in ({"visible-to-user": "false"}, {"enabled": "false"}, {"package": "other.app"}):
            with self.subTest(attrs=attrs):
                self.assertIsNone(tree(node("Apply", **attrs)).target("Apply"))

    def test_hidden_or_disabled_ancestor_hides_target(self):
        for attrs in ({"visible-to-user": "false"}, {"enabled": "false"}):
            with self.subTest(attrs=attrs):
                parent = node("", **attrs)
                parent.append(node("Apply"))
                self.assertIsNone(tree(parent).target("Apply"))

    def test_unsupported_clickable_role_is_rejected(self):
        with self.assertRaisesRegex(driver.NotExercised, "Unsupported clickable role"):
            tree(node("Apply", **{"class": "android.webkit.WebView"})).target("Apply")

    def test_unique_labeled_editable_field(self):
        parent = node("", clickable="false", bounds="[0,0][1080,1920]")
        parent.extend(
            [node("From (inclusive)", clickable="false"), node("2000-01-01", **{"class": "android.widget.EditText"})]
        )
        live = tree(parent)
        self.assertEqual(live.text(live.target("From (inclusive)", editable=True)), "2000-01-01")

    def test_ambiguous_editable_association_is_rejected(self):
        parent = node("From (inclusive)")
        parent.extend([node("", **{"class": "android.widget.EditText"}) for _ in range(2)])
        with self.assertRaisesRegex(driver.NotExercised, "Ambiguous editable"):
            tree(parent).target("From (inclusive)", editable=True)

    def test_missing_editable_association_is_rejected(self):
        with self.assertRaisesRegex(driver.NotExercised, "No uniquely associated editable"):
            tree(node("From (inclusive)")).target("From (inclusive)", editable=True)

    def test_ime_clips_partial_target_above_keyboard(self):
        target = node("Apply", bounds="[20,1100][220,1250]")
        live = tree(target, occlusion=IME)
        self.assertEqual(live.bounds(live.target("Apply")), driver.Rect(20, 1100, 220, 1200))

    def test_ime_hidden_offscreen_and_zero_size_targets_are_rejected(self):
        for bounds in ("[20,1300][220,1400]", "[1100,20][1200,120]", "[20,20][20,20]"):
            with self.subTest(bounds=bounds):
                target = node("Apply", bounds=bounds)
                live = tree(target, occlusion=IME)
                with self.assertRaises(driver.NotExercised):
                    live.bounds(live.target("Apply"))

    def test_scroll_parent_clips_hidden_descendant(self):
        parent = node("", bounds="[0,0][100,100]")
        target = node("Apply", bounds="[0,200][100,300]")
        parent.append(target)
        live = tree(parent)
        with self.assertRaises(driver.NotExercised):
            live.bounds(live.target("Apply"))

    def test_stale_and_malformed_bounds_are_rejected(self):
        target = node("Apply")
        live = tree(target)
        with (
            patch.object(driver.time, "monotonic", return_value=live.observed_at + 5.01),
            self.assertRaisesRegex(driver.NotExercised, "stale"),
        ):
            live.bounds(live.target("Apply"))
        for value in ("20,20,220,120", "[a,0][1,2]", ""):
            with self.subTest(value=value), self.assertRaises(driver.NotExercised):
                driver.Rect.parse(value)

    def test_detached_target_cannot_be_tapped(self):
        with self.assertRaisesRegex(driver.NotExercised, "live accessibility tree"):
            tree(node("Apply")).bounds(node("Apply"))

    def test_xml_errors_are_not_exercised(self):
        for xml in ("<hierarchy>", "<other />"):
            with self.subTest(xml=xml), self.assertRaises(driver.NotExercised):
                driver.Tree(xml, driver.APP, SCREEN)


class WindowTests(unittest.TestCase):
    def test_supported_frame_forms_focus_and_ime(self):
        for frame in ("mFrame=", "frame="):
            with self.subTest(frame=frame):
                windows = window_dump(ime=True, frame=frame)
                self.assertEqual(driver.focused_window(windows, driver.APP), SCREEN)
                self.assertEqual(driver.ime_region("mInputShown=true", windows, SCREEN, shown=True), IME)

    def test_focus_missing_wrong_hidden_or_ambiguous_is_rejected(self):
        examples = (
            "",
            window_dump(focus="other.app"),
            window_dump(visible=False),
            window_dump() + "  Window #1" + window_dump().split("  Window #0", 1)[1],
        )
        for windows in examples:
            with self.subTest(windows=windows), self.assertRaises(driver.NotExercised):
                driver.focused_window(windows, driver.APP)

    def test_missing_or_conflicting_frame_is_rejected(self):
        for value in ("parent=[0,0][1080,1920]", "mFrame=[0,0][10,10] frame=[0,0][20,20]"):
            with self.subTest(value=value), self.assertRaises(driver.NotExercised):
                driver.window_frame(value)

    def test_show_request_and_process_presence_are_insufficient(self):
        for ime in ("mShowRequested=true", "mInputShown=false", "mInputShown=true mInputShown=true"):
            with self.subTest(ime=ime), self.assertRaises(driver.NotExercised):
                driver.ime_region(ime, window_dump(ime=True), SCREEN, shown=True)
        with self.assertRaises(driver.NotExercised):
            driver.ime_region("mInputShown=true", window_dump(), SCREEN, shown=True)

    def test_hidden_offscreen_or_multiple_ime_windows_are_rejected(self):
        base = window_dump(ime=True)
        samples = (
            base.replace("ty=INPUT_METHOD mHasSurface=true", "ty=INPUT_METHOD mHasSurface=false"),
            base.replace("[0,1200][1080,1920]", "[0,1920][1080,2200]"),
            base + "  Window #2" + base.split("  Window #1", 1)[1].replace("def", "xyz"),
        )
        for windows in samples:
            with self.subTest(windows=windows), self.assertRaises(driver.NotExercised):
                driver.ime_region("mInputShown=true", windows, SCREEN, shown=True)

    def test_dismissal_requires_both_flag_and_no_visible_ime(self):
        self.assertIsNone(driver.ime_region("mInputShown=false", window_dump(), SCREEN, shown=False))
        for ime, windows in (("mInputShown=true", window_dump()), ("mInputShown=false", window_dump(ime=True))):
            with self.subTest(ime=ime), self.assertRaises(driver.Failure):
                driver.ime_region(ime, windows, SCREEN, shown=False)


class HoldTests(unittest.TestCase):
    def test_held_and_finished_valid_records(self):
        for finished in (False, True):
            with self.subTest(finished=finished):
                sample = state(finished=finished)
                self.assertEqual(
                    driver.validate_hold(sample, GATE, DATA, 0, finished=finished, item_id=FILENAME),
                    sample["capture_delivery"]["original_hold"],
                )

    def test_wrong_identity_path_item_constants_or_prefix_is_rejected(self):
        variants = {
            "gate_id": "another-gate",
            "request_id": GATE + "/2",
            "request_query": "kind=display",
            "item_id": "SIM_0099.JPG",
            "target_path": "/other/" + FILENAME,
            "expected_byte_count": len(DATA) + 1,
            "expected_sha256": "0" * 64,
            "prefix_bytes": 63,
            "drip_interval_ms": 2000,
            "max_hold_ms": 190000,
            "body_bytes_asgi_accepted": 63,
            "body_sha256_asgi_accepted": "0" * 64,
            "rejected_original_count": 1,
        }
        for key, value in variants.items():
            with self.subTest(key=key):
                sample = state()
                sample["capture_delivery"]["original_hold"][key] = value
                with self.assertRaises(driver.Failure):
                    driver.validate_hold(sample, GATE, DATA, 0, item_id=FILENAME)

    def test_retry_and_changed_reference_are_rejected(self):
        for mutate in (
            lambda f: f["representation_get_counts"].update(original=2),
            lambda f: f["representations"]["original"].update(sha256="0" * 64),
            lambda f: f.update(captured_ids=[]),
        ):
            sample = state()
            mutate(sample["capture_delivery"])
            with self.assertRaises(driver.Failure):
                driver.validate_hold(sample, GATE, DATA, 0)

    def test_any_terminal_failure_or_early_release_fails_held_state(self):
        for key in (
            "cancel_requested",
            "disconnect_observed",
            "expired",
            "stream_error",
            "release_requested",
            "response_finished",
            "response_ended",
        ):
            with self.subTest(key=key):
                sample = state()
                sample["capture_delivery"]["original_hold"]["timestamps"][key] = {"elapsed_ms": 4}
                with self.assertRaises(driver.Failure):
                    driver.validate_hold(sample, GATE, DATA, 0)
        with self.assertRaises(driver.Failure):
            driver.validate_hold(state(finished=True), GATE, DATA, 0)

    def test_missing_prefix_or_headers_cannot_pass(self):
        for key in ("request_arrived", "headers_asgi_accepted", "prefix_asgi_accepted"):
            with self.subTest(key=key):
                sample = state()
                sample["capture_delivery"]["original_hold"]["timestamps"][key] = None
                with self.assertRaises(driver.Failure):
                    driver.validate_hold(sample, GATE, DATA, 0)

    def test_completion_requires_release_exact_body_and_ended_response(self):
        for key, value in (
            ("phase", "releasing"),
            ("outcome", "expired"),
            ("stream_active", True),
            ("body_bytes_asgi_accepted", len(DATA) - 1),
            ("body_sha256_asgi_accepted", "0" * 64),
        ):
            with self.subTest(key=key):
                sample = state(finished=True)
                sample["capture_delivery"]["original_hold"][key] = value
                with self.assertRaises(driver.Failure):
                    driver.validate_hold(sample, GATE, DATA, 0, finished=True)
        for key in ("release_requested", "response_finished", "response_ended"):
            with self.subTest(key=key):
                sample = state(finished=True)
                sample["capture_delivery"]["original_hold"]["timestamps"][key] = None
                with self.assertRaises(driver.Failure):
                    driver.validate_hold(sample, GATE, DATA, 0, finished=True)


class UriTests(unittest.TestCase):
    def test_three_supported_exact_document_bindings(self):
        uris = (
            f"content://com.android.externalstorage.documents/document/primary:Download/{FILENAME}",
            f"content://com.android.providers.downloads.documents/document/raw:/storage/emulated/0/Download/{FILENAME}",
            "content://com.android.providers.downloads.documents/document/msf:17",
        )
        for uri in uris:
            with self.subTest(uri=uri):
                encoded = uri.rsplit("/document/", 1)[0] + "/document/" + quote(uri.rsplit("/document/", 1)[1], safe="")
                permissions = f"  * UID 10123 holds:\n    UriPermission{{{encoded}}}\n  * UID 10999 holds:\n"
                self.assertEqual(driver.selected_document_uri(permissions, "10123", FILENAME, "17"), encoded)

    def test_wrong_uid_file_media_id_provider_or_tree_uri_is_rejected(self):
        base = f"content://com.android.externalstorage.documents/document/primary:Download/{FILENAME}"
        variants = (
            ("10999", base),
            ("10123", base.replace(FILENAME, "SIM_9999.JPG")),
            ("10123", "content://com.android.providers.downloads.documents/document/msf:18"),
            ("10123", base.replace("externalstorage", "other")),
            ("10123", base.replace("/document/", "/tree/")),
        )
        for uid, uri in variants:
            with self.subTest(uid=uid, uri=uri), self.assertRaises(driver.NotExercised):
                driver.selected_document_uri(f"UID {uid} holds:\n{uri}\n", "10123", FILENAME, "17")

    def test_multiple_matching_grants_or_uid_blocks_are_rejected(self):
        uri = f"content://com.android.externalstorage.documents/document/primary:Download/{FILENAME}"
        for permissions in (
            f"UID 10123 holds:\n{uri}\ncontent://com.android.providers.downloads.documents/document/msf:17\n",
            f"UID 10123 holds:\n{uri}\nUID 10123 holds:\n{uri}\n",
        ):
            with self.subTest(permissions=permissions), self.assertRaises(driver.NotExercised):
                driver.selected_document_uri(permissions, "10123", FILENAME, "17")


class BudgetTests(unittest.TestCase):
    def test_poll_deadline_restores_global_budget(self):
        clock = FakeClock()
        budget = driver.Budget(30, clock=clock, pause=clock.sleep)
        with self.assertRaisesRegex(driver.NotExercised, "Timed out waiting"):
            budget.poll("a label", lambda: None, seconds=1)
        self.assertEqual(clock.now, 11)
        self.assertEqual(budget.deadline, 40)

    def test_probe_success_after_deadline_cannot_pass(self):
        clock = FakeClock()
        budget = driver.Budget(30, clock=clock, pause=clock.sleep)

        def late_probe():
            clock.sleep(2)
            return True

        with self.assertRaises(driver.Failure):
            budget.poll("completion", late_probe, seconds=1, error=driver.Failure)
        self.assertEqual(budget.deadline, 40)

    def test_probe_exception_restores_global_deadline(self):
        budget = driver.Budget(30, clock=FakeClock())
        with self.assertRaisesRegex(driver.Failure, "broken"):
            budget.poll("label", Mock(side_effect=driver.Failure("broken")), seconds=1)
        self.assertEqual(budget.deadline, 40)

    def test_overall_timeout_is_failure(self):
        clock = FakeClock()
        budget = driver.Budget(1, clock=clock)
        clock.sleep(1)
        with self.assertRaises(driver.Failure):
            budget.remaining()


class DriverTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.reference = root / "original.jpg"
        self.reference.write_bytes(DATA)
        self.identity_path = root / "identity.json"
        self.identity_path.write_text(json.dumps(manifest()), encoding="utf-8")
        args = SimpleNamespace(
            output_dir=root / "evidence",
            expected_original=self.reference,
            identity=self.identity_path,
            apk=root / "input.apk",
            adb="never-run-adb",
            aapt="never-run-aapt",
            apksigner="never-run-apksigner",
        )
        self.subject = driver.Driver(args)
        self.subject.filename = FILENAME
        self.subject.uid = "10123"
        self.subject.original_baseline = 0
        for name in ("subprocess.run", "subprocess.Popen", "urlopen"):
            self.addCleanup(patch.stopall)
            patch(
                "scripts.validation.android_normal_app_acceptance." + name,
                side_effect=AssertionError("Unit test attempted external execution"),
            ).start()

    def test_frozen_manifest_accepts_only_exact_tree_and_version(self):
        driver.validate_identity_manifest(manifest())
        for key, value in (
            ("app_commit", "f" * 40),
            ("app_tree", "f" * 40),
            ("version_code", 30),
            ("version_name", "0.13.1"),
            ("fresh_ephemeral_emulator", False),
            ("apk_sha256", "bad"),
            ("simulator_dependencies", {}),
            ("build_tools", {"gradle": ""}),
            ("action_pins", {"checkout": None}),
            ("avd_hw_keyboard", "unknown"),
            ("build_command", ""),
        ):
            with self.subTest(key=key):
                value_manifest = manifest()
                value_manifest[key] = value
                with self.assertRaises(driver.NotExercised):
                    driver.validate_identity_manifest(value_manifest)

    def test_wrong_tree_fails_before_adb_or_build_tools(self):
        value = manifest()
        value["app_tree"] = "f" * 40
        self.identity_path.write_text(json.dumps(value), encoding="utf-8")
        self.subject.adb, self.subject.command = Mock(), Mock()
        with self.assertRaisesRegex(driver.NotExercised, "Wrong accepted app tree"):
            self.subject.identity()
        self.subject.adb.assert_not_called()
        self.subject.command.assert_not_called()

    def test_command_timeout_failed_exit_and_oversize_are_not_exercised(self):
        examples = (
            subprocess.TimeoutExpired(["adb"], 1),
            OSError("missing"),
            SimpleNamespace(returncode=1, stdout=b"", stderr=b""),
            SimpleNamespace(returncode=0, stdout=b"12345", stderr=b""),
        )
        for example in examples:
            with self.subTest(example=example):
                kwargs = {"side_effect": example} if isinstance(example, Exception) else {"return_value": example}
                with patch.object(driver.subprocess, "run", **kwargs), self.assertRaises(driver.NotExercised):
                    self.subject.command(["adb"], cap=4)

    def test_held_deadline_expiry_fails_even_with_global_budget(self):
        self.subject.hold_deadline = 20
        with patch.object(driver.time, "monotonic", return_value=20), self.assertRaises(driver.Failure):
            self.subject.check_deadline()

    def test_hold_deadline_exists_before_picker_save_can_start_original(self):
        initial = {
            "capture_delivery": {"captured_ids": [], "original_hold": None},
            "canonical": {"shutter_af_requests": []},
        }
        captured = state()
        captured["canonical"] = {"shutter_af_requests": ["full_press"]}
        fixture = deepcopy(captured["capture_delivery"])
        fixture.update(original_delay_ms=0, original_failures=0, representation_get_counts={"original": 0})
        observations = iter((initial, captured))

        def peer(path="/ccapi/test/state", *args, **kwargs):
            if path == "/ccapi/test/state":
                return next(observations)
            if path == "/ccapi/test/capture-delivery":
                return fixture
            if path.endswith("?kind=info"):
                return {"lastmodifieddate": "2026-01-01T00:00:00Z"}
            if path.endswith("/original-hold"):
                return captured["capture_delivery"]["original_hold"]
            self.fail(f"Unexpected peer request: {path}")

        def tap(label, *args, **kwargs):
            if label == "Save":
                self.assertIsNotNone(self.subject.hold_deadline)
                self.assertGreater(self.subject.hold_deadline, driver.time.monotonic())
                raise driver.Failure("Stopped at first picker Save")

        self.subject.peer, self.subject.tap = Mock(side_effect=peer), Mock(side_effect=tap)
        self.subject.identity = Mock()
        self.subject.downloads = Mock(return_value=[])
        self.subject.wait_label, self.subject.fill = Mock(), Mock()
        self.subject.adb = Mock(
            return_value="android.intent.action.CREATE_DOCUMENT com.android.documentsui/.picker.PickActivity"
        )
        self.subject.snapshot = Mock(return_value=(tree(node("Save")), "", ""))
        self.subject.milestone = Mock(
            side_effect=[
                tree(node("Close media preview"), node(f"Preview of {FILENAME}")),
                tree(node("Downloads"), node(FILENAME, **{"class": "android.widget.EditText"})),
            ]
        )
        with self.assertRaisesRegex(driver.Failure, "Stopped at first picker Save"):
            self.subject.journey()
        self.assertEqual([call.args[0] for call in self.subject.tap.call_args_list].count("Save"), 1)

    def test_tap_cannot_ignore_unknown_ime_state(self):
        self.subject.screen = SCREEN
        self.subject.snapshot = Mock(return_value=(tree(node("Apply")), "<hierarchy/>", window_dump()))
        self.subject.adb = Mock(return_value="mShowRequested=true")
        with self.assertRaisesRegex(driver.NotExercised, "Unsupported input-method visibility"):
            self.subject.tap("Apply")
        self.subject.adb.assert_called_once_with("shell", "dumpsys", "input_method")

    def test_apply_does_not_touch_when_required_ime_disappears_or_is_unknown(self):
        self.subject.screen = SCREEN
        for ime in ("mInputShown=false", "mShowRequested=true"):
            with self.subTest(ime=ime):
                self.subject.snapshot = Mock(return_value=(tree(node("Apply")), "", window_dump()))
                self.subject.adb = Mock(return_value=ime)
                with self.assertRaises(driver.NotExercised):
                    self.subject.tap("Apply", require_ime_shown=True)
                self.subject.adb.assert_called_once_with("shell", "dumpsys", "input_method")

    def test_apply_touches_only_with_current_visible_ime_and_unoccluded_target(self):
        self.subject.screen = SCREEN
        self.subject.snapshot = Mock(return_value=(tree(node("Apply")), "", window_dump(ime=True)))
        self.subject.adb = Mock(return_value="mInputShown=true")
        self.subject.tap("Apply", require_ime_shown=True)
        self.assertEqual(self.subject.adb.call_count, 2)
        self.subject.adb.assert_called_with("shell", "input", "tap", "120", "70")

    def test_date_entry_waits_for_delayed_ime_show_within_its_existing_transition(self):
        clock = FakeClock()
        self.subject.budget = driver.Budget(30, clock=clock, pause=clock.sleep)
        self.subject.screen = SCREEN
        self.subject.tap = Mock()
        field = node(driver.DATE, **{"class": "android.widget.EditText", "hint": "From (inclusive)"})
        self.subject.snapshot = Mock(
            side_effect=[
                (tree(field), "", window_dump()),
                (tree(field), "", window_dump()),
                (tree(field), "", window_dump(ime=True)),
            ]
        )
        ime_states = iter(("mInputShown=false", "mInputShown=true", "mInputShown=true"))
        self.subject.adb = Mock(side_effect=lambda *args: next(ime_states) if args[-1] == "input_method" else "")
        self.subject.fill("From (inclusive)", driver.DATE)
        self.assertEqual(self.subject.snapshot.call_count, 3)
        self.assertEqual(clock.now, 10.5)
        self.assertEqual(self.subject.budget.deadline, 40)

    def test_range_summary_and_delayed_ime_hide_share_one_transition(self):
        clock = FakeClock()
        self.subject.budget = driver.Budget(30, clock=clock, pause=clock.sleep)
        self.subject.screen = SCREEN
        summary = f"Media date: {driver.DATE} to {driver.DATE}"
        self.subject.snapshot = Mock(
            side_effect=[
                (tree(node("Apply")), "", window_dump(ime=True)),
                (tree(node(summary)), "", window_dump(ime=True)),
                (tree(node(summary)), "", window_dump()),
            ]
        )
        self.subject.adb = Mock(side_effect=["mInputShown=true", "mInputShown=false", "mInputShown=false"])
        self.subject.wait_ime(shown=False, label=summary)
        self.assertEqual(self.subject.snapshot.call_count, 3)
        self.assertEqual(clock.now, 10.5)
        self.assertEqual(self.subject.budget.deadline, 40)

    def test_never_settling_ime_transition_fails_at_existing_twenty_second_limit(self):
        for shown in (False, True):
            with self.subTest(shown=shown):
                clock = FakeClock()
                self.subject.budget = driver.Budget(30, clock=clock, pause=clock.sleep)
                self.subject.screen = SCREEN
                # The flag and visible window disagree throughout the transition.
                self.subject.adb = Mock(
                    side_effect=lambda *args: (
                        "mInputShown=false" if args[-1] == "input_method" else window_dump(ime=True)
                    )
                )
                with self.assertRaisesRegex(driver.Failure, "Timed out waiting for IME"):
                    self.subject.wait_ime(shown=shown)
                self.assertEqual(clock.now, 30)
                self.assertEqual(self.subject.budget.deadline, 40)

    def test_unsupported_ime_transition_evidence_remains_not_exercised(self):
        for ime, windows in (
            ("mShowRequested=true", window_dump()),
            ("mInputShown=false", "unknown windows"),
            ("mInputShown=false", window_dump(ime=True).replace("isVisible=true", "visibility=unknown")),
        ):
            with self.subTest(ime=ime, windows=windows):
                clock = FakeClock()
                self.subject.budget = driver.Budget(30, clock=clock, pause=clock.sleep)
                self.subject.screen = SCREEN
                self.subject.adb = Mock(side_effect=[windows, ime])
                with self.assertRaises(driver.NotExercised):
                    self.subject.wait_ime(shown=False)
                self.assertEqual(clock.now, 10)
                self.assertEqual(self.subject.budget.deadline, 40)

    def test_milestone_saves_pixels_but_visual_review_stays_pending(self):
        self.subject.screen = SCREEN
        self.subject.snapshot = Mock(return_value=(tree(node("Apply")), "<hierarchy/>", window_dump(ime=True)))
        self.subject.adb = Mock(return_value="mInputShown=true")
        self.subject.command = Mock(return_value=png_bytes())
        self.subject.milestone("before-apply", ime_shown=True)
        self.assertTrue((self.subject.output / "before-apply.png").is_file())
        self.assertEqual(self.subject.result["visual_review"]["status"], "PENDING")
        self.assertFalse(self.subject.result["final_acceptance"])

    def test_corrupt_or_wrong_geometry_screenshot_is_not_exercised(self):
        self.subject.screen = SCREEN
        self.subject.snapshot = Mock(return_value=(tree(node("Apply")), "<hierarchy/>", window_dump()))
        self.subject.adb = Mock(return_value="mInputShown=false")
        for data in (b"\x89PNG\r\n\x1a\n" + b"x" * 200, png_bytes()[:-12], png_bytes(640, 480)):
            with self.subTest(size=len(data)):
                self.subject.command = Mock(return_value=data)
                with self.assertRaises(driver.NotExercised):
                    self.subject.milestone("after-apply", ime_shown=False)

    def destination_stubs(self, *, data=DATA, row=None, permissions=None, downloads=None):
        self.subject.downloads = Mock(return_value=[FILENAME] if downloads is None else downloads)
        row = (
            row or f"Row: 0 _id=17, _display_name={FILENAME}, relative_path=Download/, _size={len(DATA)}, is_pending=0"
        )
        permissions = (
            permissions or "UID 10123 holds:\ncontent://com.android.providers.downloads.documents/document/msf:17\n"
        )
        self.subject.adb = Mock(side_effect=[row, permissions])
        self.subject.command = Mock(return_value=data)

    def test_destination_reads_selected_file_and_compares_exact_bytes(self):
        self.destination_stubs()
        self.subject.verify_destination()
        self.subject.command.assert_called_once_with(
            ["never-run-adb", "exec-out", "cat", f"/sdcard/Download/{FILENAME}"]
        )
        self.assertTrue(self.subject.result["destination"]["byte_equal"])

    def test_destination_wrong_bytes_duplicate_partial_and_pending_fail(self):
        variants = (
            {"data": DATA[:-1] + b"x"},
            {"downloads": [FILENAME, FILENAME + ".partial"]},
            {"row": f"Row: 0 _id=17, _display_name={FILENAME}, relative_path=Download/, _size=512, is_pending=1"},
            {"row": f"Row: 0 _id=17, _display_name={FILENAME}, relative_path=Pictures/, _size=512, is_pending=0"},
        )
        for kwargs in variants:
            with self.subTest(kwargs=kwargs):
                self.destination_stubs(**kwargs)
                with self.assertRaises(driver.Failure):
                    self.subject.verify_destination()
                self.assertNotIn("destination", self.subject.result)

    def test_missing_or_duplicate_provider_row_is_not_exercised(self):
        for row in ("No result found.", "Row: 0 _id=17\nRow: 1 _id=18"):
            with self.subTest(row=row):
                self.destination_stubs(row=row)
                with self.assertRaises(driver.NotExercised):
                    self.subject.verify_destination()

    def test_system_server_anr_is_failure_without_app_pid_error(self):
        self.assertTrue(driver.has_crash_evidence("", f"E ActivityManager: ANR in {driver.APP}\n"))
        self.assertTrue(driver.has_crash_evidence("", f"E AndroidRuntime: Process: {driver.APP}, PID: 123\n"))
        self.assertTrue(driver.has_crash_evidence("E AndroidRuntime: FATAL EXCEPTION: main", ""))
        self.assertFalse(driver.has_crash_evidence("", f"ANR in {driver.APP}.unrelated\n"))

    def test_cleanup_collects_bounded_app_and_system_log_slices(self):
        self.subject.pid, self.subject.log_start = "123", "01-01 00:00:00.000"
        self.subject.adb = Mock(side_effect=["app slice", f"ANR in {driver.APP}"])
        self.subject.cleanup()
        self.assertEqual(self.subject.result["outcome"], "FAIL")
        calls = self.subject.adb.call_args_list
        self.assertIn("--pid", calls[0].args)
        self.assertNotIn("--pid", calls[1].args)
        self.assertIn("-T", calls[1].args)
        self.assertIn("ActivityManager:I", calls[1].args)
        self.assertIn("ActivityTaskManager:I", calls[1].args)
        self.assertIn("AndroidRuntime:E", calls[1].args)

    def test_log_boundary_uses_one_shell_argument_and_records_device_time(self):
        self.subject.adb = Mock(return_value="01-01T00:00:00.000\n")
        self.subject.start_log_window()
        self.subject.adb.assert_called_once_with("shell", "date", "+%m-%dT%H:%M:%S.000")
        self.assertEqual(self.subject.log_start, "01-01 00:00:00.000")
        self.assertEqual(self.subject.result["log_slice"]["device_start"], self.subject.log_start)

    def test_malformed_device_time_cannot_start_log_slice(self):
        self.subject.adb = Mock(return_value="unknown")
        with self.assertRaises(driver.NotExercised):
            self.subject.start_log_window()
        self.assertIsNone(self.subject.log_start)

    def test_system_crash_is_collected_even_if_launch_never_supplied_app_pid(self):
        self.subject.log_start = "01-01 00:00:00.000"
        self.subject.adb = Mock(return_value=f"E AndroidRuntime: Process: {driver.APP}, PID: 123")
        self.subject.cleanup()
        self.assertEqual(self.subject.result["outcome"], "FAIL")
        self.assertIn("App PID unavailable", self.subject.result["cleanup"]["app_logcat"])
        self.assertNotIn("--pid", self.subject.adb.call_args.args)

    def test_cleanup_cancels_held_gate_without_resetting_retry_latch(self):
        self.subject.gate_id = GATE
        sample = state()

        def peer(path="/ccapi/test/state", **kwargs):
            if path.endswith("/cancel"):
                sample["capture_delivery"]["original_hold"].update(
                    phase="terminal", outcome="operator_cancelled", stream_active=False
                )
            return deepcopy(sample)

        self.subject.peer = Mock(side_effect=peer)
        self.subject.snapshot = Mock(return_value=(tree(node("Download cancelled.")), "<hierarchy/>", ""))
        self.subject.downloads = Mock(return_value=[])
        self.subject.cleanup()
        paths = [call.args[0] for call in self.subject.peer.call_args_list if call.args]
        self.assertEqual(paths, [f"/ccapi/test/capture-delivery/original-hold/{GATE}/cancel"])
        self.assertTrue(self.subject.result["cleanup"]["apk_failure_observed"])

    def test_cleanup_error_is_recorded_without_reset(self):
        self.subject.gate_id = GATE
        self.subject.peer = Mock(side_effect=driver.NotExercised("peer unavailable"))
        self.subject.cleanup()
        self.assertIn("peer unavailable", self.subject.result["cleanup"]["peer"])

    def test_cleanup_revalidates_finished_gate_and_rejects_retry(self):
        self.subject.gate_id = GATE
        sample = state(finished=True)
        sample["capture_delivery"]["representation_get_counts"]["original"] = 2
        self.subject.peer = Mock(return_value=sample)
        self.subject.cleanup()
        self.assertIn("Extra or missing original request", self.subject.result["cleanup"]["peer"])

    def test_recording_ownership_mismatch_does_not_kill_unverified_device_pid(self):
        self.subject.recorder = Mock()
        self.subject.recorder.poll.return_value = None
        self.subject.remote_pid = "999"
        self.subject.remote_video = "/sdcard/eos-normal-app-acceptance.mp4"
        self.subject.adb = Mock(return_value="another-command")
        self.subject.cleanup()
        self.assertIn("ownership", self.subject.result["cleanup"]["recording"])
        self.subject.adb.assert_called_once_with("shell", "cat", "/proc/999/cmdline")
        self.subject.recorder.terminate.assert_called_once()

    def completed_cleanup(self):
        self.subject.result["cleanup"] = {
            "recording": "stopped and pulled",
            "logcat": "bounded app and system slices saved",
        }

    def automated_evidence(self):
        for name in (
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
        ):
            self.subject.write(name, b"unit fixture")

    def test_result_cannot_pass_with_missing_evidence(self):
        self.subject.journey = Mock()
        self.subject.cleanup = Mock(side_effect=self.completed_cleanup)
        self.assertEqual(self.subject.run(), 2)
        self.assertIsNone(self.subject.result["claim"])
        self.assertTrue((self.subject.output / "result.json").is_file())

    def test_result_cannot_pass_with_missing_system_logs_or_cleanup_failure(self):
        self.subject.journey = Mock(side_effect=self.automated_evidence)
        self.subject.cleanup = Mock(
            side_effect=lambda: self.subject.result["cleanup"].update(recording="stopped and pulled")
        )
        self.assertEqual(self.subject.run(), 2)
        self.assertFalse(self.subject.result["final_acceptance"])

    def test_automated_pass_preserves_owner_review_unknown_cause_and_hold(self):
        self.subject.journey = Mock(side_effect=self.automated_evidence)
        self.subject.cleanup = Mock(side_effect=self.completed_cleanup)
        self.assertEqual(self.subject.run(), 0)
        result = json.loads((self.subject.output / "result.json").read_text())
        self.assertEqual(result["visual_review"]["status"], "PENDING")
        self.assertFalse(result["final_acceptance"])
        self.assertTrue(result["release_hold"])
        self.assertEqual(result["original_root_cause"], "UNKNOWN")
        self.assertFalse(result["physical_camera"])
        self.assertIn("machine-observed", result["claim"])

    def test_journey_failure_cannot_become_pass_during_cleanup(self):
        self.subject.journey = Mock(side_effect=driver.Failure("transfer expired"))
        self.subject.cleanup = Mock(side_effect=self.completed_cleanup)
        self.assertEqual(self.subject.run(), 1)
        self.assertIsNone(self.subject.result["claim"])
        self.assertEqual(self.subject.result["reason"], "transfer expired")

    def test_unavailable_observation_and_cleanup_exception_cannot_pass(self):
        self.subject.journey = Mock(side_effect=driver.NotExercised("IME unavailable"))
        self.subject.cleanup = Mock(side_effect=OSError("cleanup unavailable"))
        self.assertEqual(self.subject.run(), 2)
        self.assertIn("IME unavailable", self.subject.result["reason"])
        self.assertIn("cleanup unavailable", self.subject.result["cleanup"]["driver"])


if __name__ == "__main__":
    unittest.main()
