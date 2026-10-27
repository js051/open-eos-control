"""Configuration controls only; these tests never start Java, Gradle or a network request."""

import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

MODULE = Path(__file__).resolve().parents[1] / "android_host_runtime.py"
SPEC = importlib.util.spec_from_file_location("android_host_runtime", MODULE)
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)

FOCUS_MODULE = MODULE.with_name("run_android_focus_ack_host.py")
FOCUS_SPEC = importlib.util.spec_from_file_location("run_android_focus_ack_host", FOCUS_MODULE)
focus = importlib.util.module_from_spec(FOCUS_SPEC)
with mock.patch.dict(sys.modules, {"android_host_runtime": runtime}):
    FOCUS_SPEC.loader.exec_module(focus)


class AndroidHostRuntimeTests(unittest.TestCase):
    def profile(self, directory, values):
        path = Path(directory) / "runtime.properties"
        path.write_text("\n".join(f"{key}={value}" for key, value in values.items()) + "\n")
        return path

    def test_profile_rejects_missing_required_value(self):
        with tempfile.TemporaryDirectory() as directory:
            values = dict(runtime.PROFILE_VALUES)
            del values["assertions"]
            with self.assertRaisesRegex(ValueError, "missing or unknown"):
                runtime.load_profile(self.profile(directory, values))

    def test_profile_rejects_unknown_value(self):
        with tempfile.TemporaryDirectory() as directory:
            for key, value in (("assertions", "disabled"), ("kotlinx.coroutines.debug", "off"),
                               ("kotlinx.coroutines.stacktrace.recovery", "false"), ("schema", "2")):
                with self.subTest(key=key):
                    values = dict(runtime.PROFILE_VALUES)
                    values[key] = value
                    with self.assertRaisesRegex(ValueError, "Unsupported unit-runtime"):
                        runtime.load_profile(self.profile(directory, values))
            values = dict(runtime.PROFILE_VALUES, unexpected="true")
            with self.assertRaisesRegex(ValueError, "missing or unknown"):
                runtime.load_profile(self.profile(directory, values))

    def test_java_rejects_wrong_major(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            (home / "bin").mkdir()
            java = home / "bin/java"
            java.write_text("synthetic executable is never launched\n")
            java.chmod(0o700)
            (home / "release").write_text('JAVA_VERSION="21.0.1"\n')
            with self.assertRaisesRegex(ValueError, "requires Java 17"):
                runtime.resolve_java(java, 17)

    def test_artifact_rejects_bad_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            jars = Path(directory)
            (jars / "synthetic-1.jar").write_bytes(b"synthetic")
            receipt = jars / "receipt.json"
            receipt.write_text(json.dumps({"artifacts": [{"file": "synthetic-1.jar", "sha256": "0" * 64}]}))
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                runtime.verify_artifacts(jars, receipt, ("synthetic-1.jar",))
            receipt.write_text(json.dumps({"artifacts": [{
                "file": "synthetic-1.jar", "sha256": hashlib.sha256(b"synthetic").hexdigest(),
            }]}))
            self.assertEqual({"synthetic-1.jar": hashlib.sha256(b"synthetic").hexdigest()},
                             runtime.verify_artifacts(jars, receipt, ("synthetic-1.jar",)))

    def test_artifact_rejects_duplicate_runtime_version(self):
        with self.assertRaisesRegex(ValueError, "Duplicate library/version"):
            runtime.classpath(Path("synthetic"), ("library-1.0.jar", "library-2.0.jar"),
                              {"library-1.0.jar": "first", "library-2.0.jar": "second"})
        with self.assertRaisesRegex(ValueError, "unverified artifact"):
            runtime.classpath(Path("synthetic"), ("library-1.0.jar",), {})

    def test_profile_produces_required_assertion_and_recovery_flags(self):
        host = object.__new__(runtime.AndroidHostRuntime)
        host.java = Path("synthetic-java")
        host.profile = runtime.load_profile(MODULE.parents[2] / runtime.PROFILE_PATH)
        command = host.command(tests=True, heap_mb=256, processors=1)
        self.assertIn("-ea", command)
        self.assertIn("-Dkotlinx.coroutines.debug=on", command)
        self.assertIn("-Dkotlinx.coroutines.stacktrace.recovery=true", command)
        self.assertIn("-Xmx256m", command)
        self.assertIn("-XX:ActiveProcessorCount=1", command)
        self.assertFalse(any(flag.startswith(("-Dkotlinx.coroutines", "-ea")) for flag in host.command(tests=False)))

    def test_receipt_does_not_claim_gradle_equivalence_without_manifest(self):
        result = runtime.compare_gradle_manifest(None, {})
        self.assertEqual("GRADLE_COMPARISON_NOT_RUN", result["state"])
        self.assertFalse(result["full_app_equivalence"])
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "gradle.json"
            manifest.write_text(json.dumps({
                "schema": 1, "entrypoint": ":app:testDebugUnitTest", "runtime_artifacts": [],
            }))
            with self.assertRaisesRegex(ValueError, "differs or is missing"):
                runtime.compare_gradle_manifest(manifest, {name: "synthetic" for name in runtime.TEST_ARTIFACTS})

    def test_compiler_and_test_classpaths_remain_separate(self):
        self.assertIn("kotlinx-coroutines-core-jvm-1.8.0.jar", runtime.COMPILER_ARTIFACTS)
        self.assertNotIn("kotlinx-coroutines-core-jvm-1.8.0.jar", runtime.TEST_ARTIFACTS)
        self.assertIn("kotlinx-coroutines-core-jvm-1.9.0.jar", runtime.TEST_ARTIFACTS)
        self.assertNotIn("kotlinx-coroutines-core-jvm-1.9.0.jar", runtime.COMPILER_ARTIFACTS)
        self.assertEqual(len(runtime.TEST_ARTIFACTS), len(set(runtime.TEST_ARTIFACTS)))

    def test_gradle_manifest_accepts_only_identical_resolved_duplicate(self):
        verified = {name: hashlib.sha256(name.encode()).hexdigest() for name in runtime.TEST_ARTIFACTS}
        artifacts = [{"coordinate": "synthetic:" + name, "file": name, "sha256": digest}
                     for name, digest in verified.items()]
        artifacts.append(dict(artifacts[0]))
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "gradle.json"
            manifest.write_text(json.dumps({
                "schema": 1, "entrypoint": ":app:testDebugUnitTest", "runtime_artifacts": artifacts,
            }))
            result = runtime.compare_gradle_manifest(manifest, verified)
            self.assertEqual("SELECTED_ARTIFACT_HASHES_MATCH", result["state"])
            self.assertFalse(result["full_app_equivalence"])

    def test_gradle_manifest_rejects_conflicting_duplicate_and_missing_selected(self):
        verified = {name: hashlib.sha256(name.encode()).hexdigest() for name in runtime.TEST_ARTIFACTS}
        artifacts = [{"coordinate": "synthetic:" + name, "file": name, "sha256": digest}
                     for name, digest in verified.items()]
        selected = next(item for item in artifacts if not item["file"].startswith("annotations-"))
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "gradle.json"
            for field, value in (("coordinate", "different:coordinate"), ("sha256", "0" * 64)):
                with self.subTest(conflict=field):
                    conflict = dict(selected, **{field: value})
                    manifest.write_text(json.dumps({
                        "schema": 1, "entrypoint": ":app:testDebugUnitTest",
                        "runtime_artifacts": artifacts + [conflict],
                    }))
                    with self.assertRaisesRegex(ValueError, "invalid/duplicate"):
                        runtime.compare_gradle_manifest(manifest, verified)
            manifest.write_text(json.dumps({
                "schema": 1, "entrypoint": ":app:testDebugUnitTest",
                "runtime_artifacts": [item for item in artifacts if item is not selected],
            }))
            with self.assertRaisesRegex(ValueError, "differs or is missing"):
                runtime.compare_gradle_manifest(manifest, verified)


class GradleManifestIdentityTests(unittest.TestCase):
    """Synthetic comparator inputs, never genuine Gradle execution receipts."""

    def setUp(self):
        self.verified = {name: hashlib.sha256(name.encode()).hexdigest() for name in runtime.TEST_ARTIFACTS}
        self.artifacts = [{"coordinate": "synthetic:" + name, "file": name, "sha256": digest}
                          for name, digest in self.verified.items()]

    def compare(self, artifacts):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic-comparator-unit-input.json"
            payload = json.dumps({"schema": 1, "entrypoint": ":app:testDebugUnitTest",
                                  "runtime_artifacts": artifacts})
            path.write_text(payload)
            result = runtime.compare_gradle_manifest(path, self.verified)
            self.assertEqual(payload, path.read_text(), "Comparator must not rewrite original evidence")
            self.assertEqual(hashlib.sha256(payload.encode()).hexdigest(), result["manifest_sha256"])
            self.assertFalse(result["full_app_equivalence"])
            return result

    def unselected(self):
        return {"coordinate": "synthetic:unloaded:1", "file": "unloaded.jar", "sha256": "a" * 64}

    def test_complete_singletons_and_identical_duplicates_keep_original_manifest(self):
        # Project coordinates can have an empty group; resolved artifacts need not be jars.
        project = {"coordinate": ":camera-import-contract:unspecified", "file": "project.aar", "sha256": "b" * 64}
        for rows in (self.artifacts, self.artifacts + [dict(self.artifacts[0]), project, dict(project)]):
            with self.subTest(rows=len(rows)):
                self.assertEqual("SELECTED_ARTIFACT_HASHES_MATCH", self.compare(rows)["state"])

    def test_selected_singleton_requires_a_complete_coordinate(self):
        selected = next(index for index, row in enumerate(self.artifacts)
                        if not row["file"].startswith("annotations-"))
        for value in (None, "", " ", 17, [], {}):
            with self.subTest(coordinate=value):
                rows = [dict(row) for row in self.artifacts]
                rows[selected]["coordinate"] = value
                with self.assertRaisesRegex(ValueError, "artifacts: coordinate must be a nonempty control-free string"):
                    self.compare(rows)
        rows = [dict(row) for row in self.artifacts]
        del rows[selected]["coordinate"]
        with self.assertRaisesRegex(ValueError, "artifacts: coordinate must be a nonempty control-free string"):
            self.compare(rows)

    def test_every_row_requires_complete_string_identity_fields(self):
        for field in ("coordinate", "file", "sha256"):
            for value in (None, "", " ", 17, [], {}, "control\nvalue", "control\u0085value"):
                with self.subTest(field=field, value=value):
                    row = dict(self.unselected(), **{field: value})
                    with self.assertRaisesRegex(
                        ValueError, f"artifacts: {field} must be a nonempty control-free string",
                    ):
                        self.compare(self.artifacts + [row])
            with self.subTest(missing=field):
                row = self.unselected()
                del row[field]
                with self.assertRaisesRegex(ValueError, f"artifacts: {field} must be a nonempty control-free string"):
                    self.compare(self.artifacts + [row])

    def test_non_object_artifact_rows_have_a_closed_validation_failure(self):
        for row in (None, "unloaded.jar", [], 17, False):
            with (
                self.subTest(row=row),
                self.assertRaisesRegex(ValueError, "artifacts: each artifact must be an object"),
            ):
                self.compare(self.artifacts + [row])

    def test_even_unselected_hashes_require_canonical_sha256(self):
        for digest in ("a" * 63, "a" * 65, "A" * 64, "g" * 64):
            with self.subTest(digest=digest):
                row = dict(self.unselected(), sha256=digest)
                with self.assertRaisesRegex(
                    ValueError, "artifacts: sha256 must be 64 lowercase hexadecimal characters",
                ):
                    self.compare(self.artifacts + [row])

    def test_file_identity_is_a_flat_filename_not_a_path(self):
        for name in ("folder/unloaded.jar", "/unloaded.jar", "folder\\unloaded.jar",
                     "C:\\unloaded.jar", "C:unloaded.jar", ".", ".."):
            with (
                self.subTest(file=name),
                self.assertRaisesRegex(ValueError, "artifacts: file must be a flat filename, not a path"),
            ):
                self.compare(self.artifacts + [dict(self.unselected(), file=name)])

    def test_same_filename_conflicts_are_rejected_in_both_orders(self):
        original = self.unselected()
        for field, value in (("coordinate", "synthetic:replacement:1"), ("sha256", "b" * 64)):
            conflict = dict(original, **{field: value})
            for extra in ([original, conflict], [conflict, original]):
                with (
                    self.subTest(field=field, first=extra[0]),
                    self.assertRaisesRegex(ValueError, "artifacts: one filename has conflicting identities"),
                ):
                    self.compare(self.artifacts + extra)

    def test_annotation_hash_comparison_exemption_does_not_require_its_presence(self):
        rows = [row for row in self.artifacts if not row["file"].startswith("annotations-")]
        self.assertLess(len(rows), len(self.artifacts))
        self.assertEqual("SELECTED_ARTIFACT_HASHES_MATCH", self.compare(rows)["state"])


class FocusReceiptGuardTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        repo = MODULE.parents[2]
        cls.receipt_path = repo / focus.FOCUS_RECEIPT_PATH
        client = (repo / "android/app/src/main/java/dev/openeos/control/data/CcapiClient.kt").read_bytes().decode()
        markers = {
            "DRIVE": ("    suspend fun driveFocus(", "    suspend fun setLiveViewMagnification("),
            "COORDINATES": ("    suspend fun tapFocus(", "    fun liveViewFrameUrl("),
            "COMMAND": ("    private suspend fun commandOk(", "    private suspend fun withRecoverableFocusRelease("),
            "REQUEST_OK": ("    private suspend fun requestOk(", "    private suspend fun requestLiveViewFrame("),
        }
        cls.methods = {
            name: begin + client.split(begin, 1)[1].split(end, 1)[0]
            for name, (begin, end) in markers.items()
        }
        begin = "            // Focus receipt integrity is independent of bounded-body/strict-client flags."
        end = "                // A partial representation cannot acknowledge the original physical control."
        request_ok = cls.methods["REQUEST_OK"]
        cls.restored = (
            request_ok[: request_ok.index(begin)]
            + "            if (stillCapture || liveViewControl || releaseControl) {\n"
            + request_ok[request_ok.index(end) :]
        )

    def test_focus_guard_accepts_original_committed_receipt(self):
        self.assertIsNone(focus.verify_current_focus_guard(self.receipt_path, self.methods, self.restored))

    def test_focus_guard_rejects_missing_receipt(self):
        with tempfile.TemporaryDirectory() as directory, self.assertRaisesRegex(ValueError, "missing or unreadable"):
            focus.verify_current_focus_guard(Path(directory) / "missing.json", self.methods, self.restored)

    def test_focus_guard_rejects_altered_receipt_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            receipt = Path(directory) / "altered.json"
            receipt.write_bytes(self.receipt_path.read_bytes() + b"\n")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                focus.verify_current_focus_guard(receipt, self.methods, self.restored)

    def test_focus_guard_rejects_changed_current_method(self):
        for name in self.methods:
            with self.subTest(method=name):
                methods = dict(self.methods)
                methods[name] += "\n"
                with self.assertRaisesRegex(ValueError, "differs from pinned green receipt: " + name):
                    focus.verify_current_focus_guard(self.receipt_path, methods, self.restored)

    def test_focus_guard_rejects_changed_inverse_predicate(self):
        restored = self.restored.replace(
            "if (stillCapture || liveViewControl || releaseControl) {",
            "if (stillCapture || liveViewControl) {",
            1,
        )
        self.assertNotEqual(self.restored, restored)
        with self.assertRaisesRegex(ValueError, "does not restore original requestOk"):
            focus.verify_current_focus_guard(self.receipt_path, self.methods, restored)


if __name__ == "__main__":
    unittest.main()
