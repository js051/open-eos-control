import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

spec = importlib.util.spec_from_file_location("verify_run", Path(__file__).with_name("verify-run.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class VerifyRunTest(unittest.TestCase):
    def setUp(self):
        self.original = [ET.Element("testcase", classname="synthetic.Class", name=f"case{i}") for i in range(100)]
        self.actual = self.original + [ET.Element("testcase", classname="synthetic.Class", name=f"case{i}") for i in range(100, 356)]
        self.log = f"EXPECTED {probe.MANIFEST}\nAPPLIED {probe.MANIFEST}\nPROOF_VALID manifest={probe.MANIFEST} entries=4 pre=8 post=8 broken=false\nBOUNDARY finish\nREPORT_COMPLETE"
        self.expected = {"invocation": "fresh-nonce", "app_apk_sha256": "a" * 64, "test_apk_sha256": "b" * 64}
        self.header = "INVOCATION fresh-nonce\nSESSION token=1 pid=2\nAPK app=" + "a" * 64 + " test=" + "b" * 64 + "\n"
        self.start = self.header + f"EXPECTED {probe.MANIFEST}\nOUTPUT_ROUTE collectorArgument=true\nSTART_COMPLETE\n"
        self.finish = self.header + self.log

    def read(self, files):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "result.zip"
            with zipfile.ZipFile(path, "w") as archive:
                for name, text in files.items():
                    archive.writestr("connected_android_test_additional_output/device/" + name, text)
            return probe.read_probe_reports(path, self.expected)

    def files(self):
        return {"eos-measure-writer-fresh-nonce-1-2-start.txt": self.start,
                "eos-measure-writer-fresh-nonce-1-2-finish.txt": self.finish}

    def test_xml_count_is_not_full_matrix_or_causal_resolution(self):
        result = probe.validate(self.original, self.actual, self.log)
        self.assertTrue(result["xml_356_cases_zero_failures_and_skips"])
        self.assertFalse(result["authoritative_full_matrix_identity_checked"])
        self.assertFalse(result["causal_resolution"])

    def test_original_first_100_is_required(self):
        self.actual[0], self.actual[1] = self.actual[1], self.actual[0]
        with self.assertRaises(ValueError): probe.validate(self.original, self.actual, self.log)

    def test_missing_or_broken_hooks_rejected(self):
        for text in ("", self.log.replace("APPLIED", "ABSENT"), self.log.replace("pre=8", "pre=0"), self.log + " broken=true"):
            with self.subTest(text=text), self.assertRaises(ValueError): probe.validate(self.original, self.actual, text)

    def test_crash_proof_is_useful_but_never_counted_as_success(self):
        ET.SubElement(self.original[-1], "failure")
        text = self.log.replace("BOUNDARY finish", "BOUNDARY fatal")
        result = probe.validate(self.original, self.original, text)
        self.assertFalse(result["xml_356_cases_zero_failures_and_skips"])
        self.assertEqual(result["failures"], 1)

    def test_committed_reports_require_current_session_complete_bytes_and_hashes(self):
        self.assertEqual(set(self.read(self.files())), {"start", "finish"})
        for replacement in (self.finish.replace("pid=2", "pid=3"), self.finish.replace("REPORT_COMPLETE", "truncated"),
                            self.finish.replace("test=" + "b" * 64, "test=" + "c" * 64),
                            self.finish.replace("INVOCATION fresh-nonce", "INVOCATION old-nonce")):
            files = self.files(); files["eos-measure-writer-fresh-nonce-1-2-finish.txt"] = replacement
            with self.subTest(replacement=replacement), self.assertRaises(ValueError): self.read(files)

    def test_pending_after_complete_bytes_is_not_a_commit(self):
        files = self.files()
        files["eos-measure-writer-fresh-nonce-1-2-finish.pending"] = files.pop("eos-measure-writer-fresh-nonce-1-2-finish.txt")
        with self.assertRaises(ValueError): self.read(files)

    def test_old_complete_run_cannot_replace_failed_current_run(self):
        old = {name.replace("fresh-nonce", "old-nonce"): text.replace("fresh-nonce", "old-nonce") for name, text in self.files().items()}
        with self.assertRaises(ValueError): self.read(old)
        current_failed = dict(old, **{"eos-measure-writer-fresh-nonce-3-4-start.pending": "partial"})
        with self.assertRaises(ValueError): self.read(current_failed)
        # A distinct successful current invocation need not delete unrelated older valid files.
        self.assertEqual(set(self.read(old | self.files())), {"start", "finish"})

    def host_files(self, root):
        files = {name: root / name for name in ("pre-run-manifest.json", "app-debug.apk",
                 "app-debug-androidTest.apk", "source-snapshot.json", "build.log", "run-manifest.json")}
        for name, path in files.items(): path.write_bytes(name.encode())
        pre = {"schema": "eos-measure-writer-pre-run-v1", "invocation": "fresh-nonce",
               "created_at_utc": "2026-10-08T12:00:00+00:00", "working_directory": "android",
               "command": probe.expected_command("fresh-nonce"),
               "ci": {"run_id": "123", "run_attempt": "2", "job": "android-ui", "sha": "a" * 40,
                      "repository": "synthetic/project", "ref": "refs/heads/diagnostic", "workflow": "Synthetic Android"},
               "checkout": {"commit": "a" * 40, "tree": "b" * 40},
               "source_snapshot_sha256": probe.sha256(files["source-snapshot.json"])}
        files["pre-run-manifest.json"].write_text(json.dumps(pre))
        fields = {"pre_run_manifest_sha256": "pre-run-manifest.json", "app_apk_sha256": "app-debug.apk",
                  "test_apk_sha256": "app-debug-androidTest.apk", "source_snapshot_sha256": "source-snapshot.json",
                  "build_log_sha256": "build.log"}
        receipt = {"schema": "eos-measure-writer-run-v1", "invocation": "fresh-nonce", "gradle_exit": 0}
        receipt.update({field: probe.sha256(files[name]) for field, name in fields.items()})
        files["run-manifest.json"].write_text(json.dumps(receipt))
        return pre, receipt, files

    def check_host(self, receipt, files):
        return probe.check_run_manifest(receipt, *(files[name] for name in
                                       ("pre-run-manifest.json", "app-debug.apk", "app-debug-androidTest.apk", "source-snapshot.json")))

    def test_external_build_manifest_must_match_pre_run_apk_and_source_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            _, receipt, files = self.host_files(Path(temp))
            self.assertEqual(self.check_host(receipt, files), receipt)
            for name in ("pre-run-manifest.json", "app-debug.apk", "app-debug-androidTest.apk", "source-snapshot.json"):
                with self.subTest(name=name):
                    original = files[name].read_bytes()
                    files[name].write_bytes(original + b"different-current-bytes")
                    with self.assertRaises(ValueError): self.check_host(receipt, files)
                    files[name].write_bytes(original)

    def test_post_run_hashes_cannot_replace_the_pre_run_identity(self):
        with tempfile.TemporaryDirectory() as temp:
            pre, receipt, files = self.host_files(Path(temp))
            for field, changed in (("invocation", "stale-nonce"), ("source_snapshot_sha256", "c" * 64)):
                with self.subTest(field=field):
                    replacement = dict(pre, **{field: changed})
                    if field == "invocation": replacement["command"] = probe.expected_command(changed)
                    files["pre-run-manifest.json"].write_text(json.dumps(replacement))
                    receipt["pre_run_manifest_sha256"] = probe.sha256(files["pre-run-manifest.json"])
                    with self.assertRaises(ValueError): self.check_host(receipt, files)

    def test_complete_old_bundle_cannot_replace_the_current_external_pre_run_hash(self):
        with tempfile.TemporaryDirectory() as temp:
            _, receipt, files = self.host_files(Path(temp))
            self.check_host(receipt, files)  # internally consistent old bundle is insufficient
            probe.check_pre_run_hash(files["pre-run-manifest.json"], receipt["pre_run_manifest_sha256"])
            current_job_hash = hashlib.sha256(b"independent current job pre-run bytes").hexdigest()
            with self.assertRaises(ValueError):
                probe.check_pre_run_hash(files["pre-run-manifest.json"], current_job_hash)

    def test_full_command_and_exact_ci_checkout_are_required(self):
        with tempfile.TemporaryDirectory() as temp:
            pre, _, _ = self.host_files(Path(temp))
            for command in (pre["command"][:-1], pre["command"] + ["-Pandroid.testInstrumentationRunnerArguments.class=synthetic.Class"]):
                with self.subTest(command=command), self.assertRaises(ValueError):
                    probe.check_pre_run_manifest(dict(pre, command=command))
            with self.assertRaises(ValueError): probe.check_pre_run_manifest(dict(pre, ci=dict(pre["ci"], sha="c" * 40)))
            with self.assertRaises(ValueError): probe.check_pre_run_manifest(dict(pre, ci=dict(pre["ci"], run_attempt="")))

    def test_current_host_package_must_be_complete_and_match_external_artifacts(self):
        with tempfile.TemporaryDirectory() as temp:
            _, receipt, files = self.host_files(Path(temp))
            archive_path = Path(temp) / "results.zip"
            stem = "connected_android_test_additional_output/eos-measure-writer-host-fresh-nonce/"
            external = {name: path for name, path in files.items() if name != "build.log"}
            for problem in (None, "run-manifest.json", "run-manifest.pending", "app-debug.apk", "build.log", "source-snapshot.json"):
                with self.subTest(problem=problem):
                    with zipfile.ZipFile(archive_path, "w") as archive:
                        for name, path in files.items():
                            if name == problem == "run-manifest.json": continue
                            archive.writestr(stem + name, b"changed" if name == problem else path.read_bytes())
                        if problem == "run-manifest.pending": archive.writestr(stem + problem, b"complete but not committed")
                    if problem:
                        with self.assertRaises(ValueError): probe.check_retained_bundle(archive_path, receipt, external)
                    else:
                        probe.check_retained_bundle(archive_path, receipt, external)


if __name__ == "__main__": unittest.main()
