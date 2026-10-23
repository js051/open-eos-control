"""Shared launch configuration for supplemental Android JVM diagnostics.

This does not resolve/install dependencies or replace Gradle's app test task.
Compiler dependencies and product test-runtime dependencies are separate on purpose.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
from pathlib import Path

PROFILE_PATH = Path("android/gradle/unit-test-runtime.properties")
PROFILE_VALUES = {
    "schema": "1",
    "java.major": "17",
    "assertions": "enabled",
    "kotlinx.coroutines.debug": "on",
    "kotlinx.coroutines.stacktrace.recovery": "true",
}
# These are the existing, receipt-pinned compiler's own dependencies, not app libraries.
COMPILER_ARTIFACTS = (
    "kotlin-compiler-embeddable-2.2.21.jar",
    "kotlin-stdlib-2.2.21.jar",
    "kotlin-script-runtime-2.2.21.jar",
    "kotlin-reflect-1.6.10.jar",
    "kotlin-daemon-embeddable-2.2.21.jar",
    "kotlinx-coroutines-core-jvm-1.8.0.jar",
    "annotations-23.0.0.jar",
)
TEST_ARTIFACTS = (
    "kotlin-stdlib-2.2.21.jar",
    "kotlinx-coroutines-core-jvm-1.9.0.jar",
    "annotations-23.0.0.jar",
    "junit-4.13.2.jar",
    "hamcrest-core-1.3.jar",
)


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def load_profile(path: Path) -> dict[str, str]:
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not separator or key in values:
            raise ValueError("Invalid or duplicate unit-runtime property")
        values[key] = value
    if set(values) != set(PROFILE_VALUES):
        raise ValueError("Unit-runtime profile has missing or unknown properties")
    if values != PROFILE_VALUES:
        raise ValueError("Unsupported unit-runtime semantics; do not silently weaken the contract")
    return values


def resolve_java(explicit: Path | None, major: int) -> tuple[Path, dict[str, str]]:
    candidate = explicit
    if candidate is None:
        home = os.environ.get("JAVA_HOME")
        found = str(Path(home) / "bin/java") if home else shutil.which("java")
        if not found:
            raise ValueError("Java is unavailable; supply an already installed JDK with --java")
        candidate = Path(found)
    java = candidate.resolve(strict=True)
    if not java.is_file() or not os.access(java, os.X_OK):
        raise ValueError("The selected Java executable is not executable")
    release = java.parent.parent / "release"
    match = re.search(r'^JAVA_VERSION="([^"]+)"$', release.read_text(), re.MULTILINE)
    if not match or int(match.group(1).split(".")[0]) != major:
        raise ValueError(f"Unit-runtime requires Java {major}; select it explicitly with --java")
    return java, {"version": match.group(1), "release_sha256": digest(release), "executable_sha256": digest(java)}


def verify_artifacts(jars: Path, receipt: Path, names: tuple[str, ...]) -> dict[str, str]:
    data = json.loads(receipt.read_text())
    artifacts = data.get("artifacts") if isinstance(data, dict) else data
    if not isinstance(artifacts, list):
        raise ValueError("Tool receipt must contain an artifact list")
    entries = {}
    for artifact in artifacts:
        name = artifact.get("file")
        if not isinstance(name, str) or Path(name).name != name:
            raise ValueError("Artifact receipt must use flat filenames")
        if name in entries:
            raise ValueError("Duplicate artifact in tool receipt: " + name)
        entries[name] = artifact
    verified = {}
    for name in dict.fromkeys(names):
        artifact = entries.get(name)
        if artifact is None:
            raise ValueError("Required existing tool missing from receipt: " + name)
        path = jars / name
        actual = digest(path)
        if actual != artifact.get("sha256"):
            raise ValueError("Existing tool checksum mismatch: " + name)
        verified[name] = actual
    return verified


def classpath(jars: Path, names: tuple[str, ...], verified: dict[str, str]) -> str:
    # A compiler may need another coroutine version; a *single* runtime may not load both.
    families = [re.sub(r"-\d.*\.jar$", "", name) for name in names]
    if len(set(families)) != len(families):
        raise ValueError("Duplicate library/version in one classpath")
    if any(name not in verified for name in names):
        raise ValueError("Classpath contains an unverified artifact")
    return os.pathsep.join(str(jars / name) for name in names)


def compare_gradle_manifest(path: Path | None, verified: dict[str, str]) -> dict:
    if path is None:
        return {"state": "GRADLE_COMPARISON_NOT_RUN", "full_app_equivalence": False}
    data = json.loads(path.read_text())
    if data.get("schema") != 1 or data.get("entrypoint") != ":app:testDebugUnitTest":
        raise ValueError("Not a supported formal Gradle unit-test manifest")
    artifacts = data.get("runtime_artifacts")
    if not isinstance(artifacts, list):
        raise ValueError("Gradle manifest has no runtime artifacts")
    entries = {}
    for artifact in artifacts:
        name = artifact.get("file")
        if not isinstance(name, str):
            raise ValueError("Gradle manifest has invalid/duplicate artifacts")
        identity = (artifact.get("coordinate"), name, artifact.get("sha256"))
        if name in entries:
            # Gradle may report the identical resolved artifact more than once.
            # Only a complete, identical identity is idempotent; conflicts stay fatal.
            if (identity != entries[name] or not all(isinstance(value, str) and value for value in identity)
                    or re.fullmatch(r"[0-9a-f]{64}", identity[2]) is None):
                raise ValueError("Gradle manifest has invalid/duplicate artifacts")
            continue
        entries[name] = identity
    for name in TEST_ARTIFACTS:
        # Annotation packaging may legitimately differ between Gradle and the diagnostic compiler.
        if name.startswith("annotations-"):
            continue
        if entries.get(name, (None, None, None))[2] != verified[name]:
            raise ValueError("Gradle test artifact differs or is missing: " + name)
    return {
        "state": "SELECTED_ARTIFACT_HASHES_MATCH",
        "manifest_sha256": digest(path),
        "full_app_equivalence": False,
    }


class AndroidHostRuntime:
    """One verified launch recipe; individual diagnostics still own their test scenarios."""

    def __init__(
        self, repo: Path, jars: Path, *, java: Path | None = None,
        receipt: Path | None = None, gradle_manifest: Path | None = None,
    ):
        self.profile_path = repo / PROFILE_PATH
        self.profile = load_profile(self.profile_path)
        self.jars = jars.resolve(strict=True)
        self.java, self.java_receipt = resolve_java(java, int(self.profile["java.major"]))
        self.tool_receipt = receipt or self.jars / "installation-receipt.json"
        self.verified = verify_artifacts(self.jars, self.tool_receipt, COMPILER_ARTIFACTS + TEST_ARTIFACTS)
        self.compiler_classpath = classpath(self.jars, COMPILER_ARTIFACTS, self.verified)
        self.test_classpath = classpath(self.jars, TEST_ARTIFACTS, self.verified)
        self.gradle_comparison = compare_gradle_manifest(gradle_manifest, self.verified)

    def command(self, *, tests: bool, heap_mb: int = 256, processors: int = 1) -> list[str]:
        if heap_mb <= 0 or processors <= 0:
            raise ValueError("Positive explicit JVM resource limits are required")
        command = [str(self.java), f"-XX:ActiveProcessorCount={processors}", f"-Xmx{heap_mb}m"]
        if tests:
            command.extend([
                "-ea", f"-Dkotlinx.coroutines.debug={self.profile['kotlinx.coroutines.debug']}",
                f"-Dkotlinx.coroutines.stacktrace.recovery={self.profile['kotlinx.coroutines.stacktrace.recovery']}",
            ])
        return command

    def receipt(self) -> dict:
        return {
            "schema": 1, "entrypoint": "supplemental-jvm-diagnostic",
            "java_major": int(self.profile["java.major"]),
            "assertions_enabled": True,
            "system_properties": {
                key: self.profile[key] for key in (
                    "kotlinx.coroutines.debug", "kotlinx.coroutines.stacktrace.recovery",
                )
            },
            "runtime_artifacts": [
                {"file": name, "sha256": self.verified[name]} for name in TEST_ARTIFACTS
            ],
            "profile_sha256": digest(self.profile_path), "profile": self.profile,
            "java": self.java_receipt, "tool_receipt_sha256": digest(self.tool_receipt),
            "compiler_artifacts": {name: self.verified[name] for name in COMPILER_ARTIFACTS},
            "test_artifacts": {name: self.verified[name] for name in TEST_ARTIFACTS},
            "gradle_comparison": self.gradle_comparison,
            "verification_boundary": "SUPPLEMENTAL_DIAGNOSTIC_NOT_GRADLE_APP_ACCEPTANCE",
        }


def add_runtime_arguments(parser) -> None:
    parser.add_argument("--java", type=Path, help="Already installed Java 17 executable (no installation)")
    parser.add_argument(
        "--tool-receipt", type=Path, help="Existing checksum receipt; defaults to jars/installation-receipt.json",
    )
    parser.add_argument("--gradle-manifest", type=Path, help="Optional actual formal Gradle runtime manifest")
