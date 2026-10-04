"""Offline integration tests for the real Git hook entry points on Linux x64."""

import hashlib
import os
from pathlib import Path
import platform
import random
import string
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
ARCHIVE_NAME = "gitleaks_8.30.1_linux_x64.tar.gz"
ARCHIVE_SHA256 = "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb"
ZERO = "0" * 40
# Deliberately synthetic; assemble at runtime so test source is not a secret fixture.
SYNTHETIC_TOKEN = "ghp_" + "".join(random.Random(20261003).choices(string.ascii_letters + string.digits, k=36))
SECRET_CONTENT = 'api_key = "' + SYNTHETIC_TOKEN + '"\n'


class SecretHookTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if platform.system() != "Linux" or platform.machine() not in ("x86_64", "AMD64"):
            raise RuntimeError("These integration tests require Linux x64.")
        cls.pwsh = shutil.which("pwsh")
        if not cls.pwsh:
            raise RuntimeError("Put PowerShell 7 (pwsh) on PATH before running these tests.")
        archive = os.environ.get("OPEN_EOS_TEST_GITLEAKS_ARCHIVE")
        if not archive:
            raise RuntimeError("Set OPEN_EOS_TEST_GITLEAKS_ARCHIVE to the official Linux x64 archive.")
        cls.archive = Path(archive).resolve()
        if hashlib.sha256(cls.archive.read_bytes()).hexdigest() != ARCHIVE_SHA256:
            raise RuntimeError("The test Gitleaks archive failed official checksum verification.")
        cls.scratch = ROOT / ".codex" / "toolchain" / "security-tests"
        cls.scratch.mkdir(parents=True, exist_ok=True)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=self.scratch)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.env = os.environ.copy()
        self.env.update({
            "HOME": str(self.root / "home"),
            "XDG_CACHE_HOME": str(self.root / "cache"),
            "XDG_CONFIG_HOME": str(self.root / "config"),
            "XDG_DATA_HOME": str(self.root / "data"),
            "POWERSHELL_TELEMETRY_OPTOUT": "1",
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_AUTHOR_NAME": "Synthetic Hook Test",
            "GIT_AUTHOR_EMAIL": "hook-tests@example.invalid",
            "GIT_COMMITTER_NAME": "Synthetic Hook Test",
            "GIT_COMMITTER_EMAIL": "hook-tests@example.invalid",
        })
        self.git("init", "--quiet", "--initial-branch=main", "--template=")
        shutil.copytree(ROOT / ".githooks", self.repo / ".githooks")
        scripts = self.repo / "scripts" / "security"
        scripts.mkdir(parents=True)
        shutil.copy2(ROOT / "scripts/security/scan-secrets.ps1", scripts)
        shutil.copy2(ROOT / ".gitleaks.toml", self.repo)
        (self.repo / ".gitignore").write_text(".codex/\n.githooks/\nscripts/\n")
        self.git("add", ".gitleaks.toml", ".gitignore")
        self.base = self.commit("Synthetic baseline.\n")
        self.git("update-ref", "refs/remotes/origin/main", self.base)
        self.cache = self.repo / ".codex/toolchain/gitleaks/8.30.1/linux_x64"
        self.cache.mkdir(parents=True)
        shutil.copy2(self.archive, self.cache / ARCHIVE_NAME)
        # Downloads are always mocked, including unexpected ones, so tests stay offline.
        self.shims = self.root / "bin"
        self.shims.mkdir()
        driver = self.root / "driver.ps1"
        driver.write_text('''param([string]$ScriptPath, [string]$Mode, [string]$RemoteName = "origin")
$ErrorActionPreference = "Stop"
function global:Invoke-WebRequest {
    param([string]$Uri, [string]$OutFile)
    if ($env:OPEN_EOS_TEST_DOWNLOAD -eq "bad-hash") {
        [IO.File]::WriteAllText($OutFile, "synthetic invalid archive")
    } else {
        throw "Synthetic offline download failure."
    }
}
& $ScriptPath -Mode $Mode -RemoteName $RemoteName
exit $LASTEXITCODE
''')
        wrapper = self.shims / "pwsh"
        # Preserve the actual hook's script/arguments while injecting download faults only.
        wrapper.write_text(
            "#!/usr/bin/env python3\nimport os, sys\n"
            f"shell = {self.pwsh!r}\ndriver = {str(driver)!r}\n"
            "args = sys.argv[sys.argv.index('-File') + 1:]\n"
            "os.execv(shell, [shell, '-NoProfile', '-File', driver, *args])\n"
        )
        wrapper.chmod(0o755)
        self.env["PATH"] = str(self.shims) + os.pathsep + self.env["PATH"]

    def git(self, *args, input=None):
        result = subprocess.run(
            ["git", *args], cwd=self.repo, env=self.env, input=input,
            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True,
        )
        return result.stdout.strip()

    def commit(self, content, parent=None):
        """Construct synthetic local history with Git plumbing; never commit or push user data."""
        (self.repo / "payload.txt").write_text(content)
        self.git("add", "payload.txt")
        tree = self.git("write-tree")
        args = ["commit-tree", tree]
        if parent is None:
            head = subprocess.run(
                ["git", "rev-parse", "--verify", "HEAD"], cwd=self.repo,
                env=self.env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            )
            parent = head.stdout.strip() if head.returncode == 0 else None
        if parent:
            args.extend(["-p", parent])
        commit = self.git(*args, input="Synthetic hook-test commit.\n")
        self.git("update-ref", "refs/heads/main", commit)
        return commit

    def hook(self, name="pre-commit", updates=""):
        result = subprocess.run(
            [str(self.repo / ".githooks" / name), "origin", "https://example.invalid/synthetic.git"],
            cwd=self.repo, env=self.env, input=updates, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60,
        )
        self.assertNotIn(SYNTHETIC_TOKEN, result.stdout, "Scanner output must stay redacted.")
        return result

    def update(self, head, remote=None, branch="main"):
        return f"refs/heads/{branch} {head} refs/heads/{branch} {remote or self.base}\n"

    def assert_blocked(self, result, message):
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(message, result.stdout)

    def old_scanner(self):
        marker = self.root / "old-scanner-ran"
        cached = self.cache / "gitleaks"
        cached.write_text(f"#!/bin/sh\ntouch '{marker}'\nexit 0\n")
        cached.chmod(0o755)
        return marker

    def test_clean_staged_changes_pass(self):
        (self.repo / "payload.txt").write_text("Synthetic clean staged change.\n")
        self.git("add", "payload.txt")
        result = self.hook()
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_staged_secret_is_blocked_even_when_worktree_is_clean(self):
        (self.repo / "payload.txt").write_text(SECRET_CONTENT)
        self.git("add", "payload.txt")
        (self.repo / "payload.txt").write_text("The staged index still contains the synthetic secret.\n")
        self.assert_blocked(self.hook(), "leaks found: 1")

    def test_clean_outgoing_commits_pass(self):
        for index in range(3):
            head = self.commit(f"Synthetic clean outgoing change {index}.\n")
        result = self.hook("pre-push", self.update(head))
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("3 commits scanned", result.stdout)

    def test_each_outgoing_commit_is_scanned_including_removed_secrets(self):
        for secret_index in range(3):
            with self.subTest(secret_index=secret_index):
                parent = self.base
                for index in range(4):
                    content = SECRET_CONTENT if index == secret_index else f"Synthetic clean change {index}.\n"
                    parent = self.commit(content, parent=parent)
                result = self.hook("pre-push", self.update(parent))
                self.assert_blocked(result, "leaks found: 1")
                self.assertIn("4 commits scanned", result.stdout)

    def test_every_updated_ref_is_scanned(self):
        clean = self.commit("Synthetic clean branch.\n")
        self.commit(SECRET_CONTENT)
        head = self.commit("Synthetic secret removed from the tip.\n")
        updates = self.update(clean, branch="clean") + self.update(head, remote=clean, branch="second")
        self.assert_blocked(self.hook("pre-push", updates), "leaks found: 1")

    def test_new_branch_scans_all_commits_not_on_remote(self):
        self.commit(SECRET_CONTENT)
        head = self.commit("Synthetic clean new-branch tip.\n")
        self.assert_blocked(self.hook("pre-push", self.update(head, remote=ZERO)), "leaks found: 1")

    def test_unknown_remote_commit_scans_all_locally_outgoing_commits(self):
        self.commit(SECRET_CONTENT)
        head = self.commit("Synthetic clean tip.\n")
        self.assert_blocked(self.hook("pre-push", self.update(head, remote="1" * 40)), "leaks found: 1")

    def test_missing_scanner_and_failed_download_block(self):
        (self.cache / ARCHIVE_NAME).unlink()
        self.assert_blocked(self.hook(), "Synthetic offline download failure")

    def test_bad_download_hash_cannot_fall_back_to_old_scanner(self):
        (self.cache / ARCHIVE_NAME).unlink()
        marker = self.old_scanner()
        self.env["OPEN_EOS_TEST_DOWNLOAD"] = "bad-hash"
        self.assert_blocked(self.hook(), "Gitleaks checksum verification failed")
        self.assertFalse(marker.exists())
        self.assertFalse((self.cache / ARCHIVE_NAME).exists())

    def test_bad_cached_archive_cannot_fall_back_to_old_scanner(self):
        (self.cache / ARCHIVE_NAME).write_bytes(b"synthetic corrupt cached archive")
        marker = self.old_scanner()
        self.assert_blocked(self.hook(), "Gitleaks checksum verification failed")
        self.assertFalse(marker.exists())

    def test_failed_download_cannot_fall_back_to_old_scanner(self):
        (self.cache / ARCHIVE_NAME).unlink()
        marker = self.old_scanner()
        self.assert_blocked(self.hook(), "Synthetic offline download failure")
        self.assertFalse(marker.exists())

    def test_extraction_error_cannot_fall_back_to_old_scanner(self):
        marker = self.old_scanner()
        tar = self.shims / "tar"
        tar.write_text("#!/bin/sh\nexit 42\n")
        tar.chmod(0o755)
        self.assert_blocked(self.hook(), "Unable to extract the verified Gitleaks archive")
        self.assertFalse(marker.exists())

    def test_chmod_error_cannot_fall_back_to_old_scanner(self):
        marker = self.old_scanner()
        chmod = self.shims / "chmod"
        chmod.write_text("#!/bin/sh\nexit 43\n")
        chmod.chmod(0o755)
        self.assert_blocked(self.hook(), "Unable to make Gitleaks executable")
        self.assertFalse(marker.exists())

    def test_missing_powershell_blocks_both_hooks(self):
        isolated = self.root / "no-powershell"
        isolated.mkdir()
        for tool in ("sh", "git"):
            (isolated / tool).symlink_to(shutil.which(tool))
        self.env["PATH"] = str(isolated)
        for hook in ("pre-commit", "pre-push"):
            with self.subTest(hook=hook):
                self.assert_blocked(self.hook(hook), "PowerShell is required")

    def test_scanner_execution_failure_blocks(self):
        (self.repo / ".gitleaks.toml").write_text("[synthetic invalid configuration\n")
        result = self.hook()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("unable to load gitleaks config", result.stdout)

    def test_malformed_prepush_input_blocks(self):
        self.assert_blocked(self.hook("pre-push", "invalid synthetic input\n"), "Unexpected pre-push input")


if __name__ == "__main__":
    unittest.main()
