#!/usr/bin/env python3
"""REVIEW CANDIDATE: bounded preparation/ownership glue for one API-36 job.

Proposed destination: scripts/validation/android_normal_app_job.py.
This file is not installed, executed, or referenced by a live workflow yet.
"""
import hashlib
import json
import os
import re
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path
from urllib.request import urlopen

ACCEPTED_COMMIT = "65fbabf00c78924228ec7cbfe22d733ef806094f"
ACCEPTED_TREE = "c1e296b9c90bac0cda001cb0bf9bc4d91ef90eb2"
APP = "dev.openeos.control.debug"
DEFAULT_APP = "dev.openeos.control"
WORKSPACE = Path(os.environ["GITHUB_WORKSPACE"])
HARNESS = WORKSPACE / "harness"
SOURCE = WORKSPACE / "accepted-app"
OUT = Path(os.environ["EOS_ACCEPTANCE_DIR"])
AVD_NAME = os.environ["EOS_AVD_NAME"]
AVD_CONFIG = Path.home() / ".android" / "avd" / f"{AVD_NAME}.avd" / "config.ini"
VENV = OUT.with_name(OUT.name + "-venv")  # Keep environment files out of evidence uploads.
PYTHON = VENV / "bin" / "python"
DRIVER = HARNESS / "scripts/validation/android_normal_app_acceptance.py"
APK = SOURCE / "android/app/build/outputs/apk/debug/app-debug.apk"
PINS = {
    "actions/checkout": "3d3c42e5aac5ba805825da76410c181273ba90b1",
    "actions/setup-java": "b6effb05e454b25005698d916606bdc6ffcbf961",
    "actions/setup-python": "5fda3b95a4ea91299a34e894583c3862153e4b97",
    "gradle/actions/setup-gradle": "9c971963bec38e04b3d30dcc455b5382be2fdbfb",
    "ReactiveCircus/android-emulator-runner": "a421e43855164a8197daf9d8d40fe71c6996bb0d",
    "actions/upload-artifact": "043fb46d1a93c77aae656e7c1c64a875d1fc6a0a",
}


def require(value, reason):
    if not value:
        raise RuntimeError(reason)


def write(name, value):
    (OUT / name).write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def read(name):
    return json.loads((OUT / name).read_text())


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def command(args, *, cwd=None, seconds=10):
    result = subprocess.run([str(x) for x in args], cwd=cwd, check=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=seconds)
    return result.stdout.decode().strip()


def git(root, *args):
    return command(["git", "-C", root, *args])


def bounded_logged(args, log, *, cwd=None, seconds):
    # Each subprocess group belongs to this invocation; never pkill/killall.
    with (OUT / log).open("wb") as stream:
        proc = subprocess.Popen([str(x) for x in args], cwd=cwd, stdout=stream,
                                stderr=subprocess.STDOUT, start_new_session=True)
        try:
            write(log + "-owner.json", {"pid": proc.pid, "start_ticks": process_identity(proc.pid)})
            rc = proc.wait(timeout=seconds)
        except BaseException:
            if proc.poll() is None:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.wait(timeout=3)
            raise
    require(rc == 0, f"{log}: exit {rc}; see bounded log")


def kvm():
    require(Path("/dev/kvm").is_char_device() and os.access("/dev/kvm", os.R_OK | os.W_OK),
            "BLOCKED: /dev/kvm does not have existing read/write access")


def prepare():
    kvm()
    require(os.environ["GITHUB_REPOSITORY"] == "js051/open-eos-control"
            and os.environ["EOS_HEAD_REPOSITORY"] == "js051/open-eos-control"
            and os.environ["EOS_HEAD_REF"] == "test/android-normal-app-media-acceptance"
            and re.fullmatch(r"refs/pull/\d+/merge", os.environ["GITHUB_REF"]), "Wrong PR scope")
    require(git(HARNESS, "rev-parse", "HEAD") == os.environ["EOS_HARNESS_SHA"], "Wrong merge checkout")
    require(git(SOURCE, "rev-parse", "HEAD") == ACCEPTED_COMMIT
            and git(SOURCE, "rev-parse", "HEAD^{tree}") == ACCEPTED_TREE, "Wrong accepted app checkout")
    for root in (SOURCE, HARNESS):
        require(not git(root, "diff", "HEAD", "--"), "Tracked checkout differs from its commit")
    require(not any(value for key, value in os.environ.items() if key.startswith("OEC_ANDROID_SIGNING_")),
            "Release/development signing environment must not be supplied")
    require(not AVD_CONFIG.parent.exists() and not AVD_CONFIG.parent.with_suffix(".ini").exists(),
            "AVD name already exists; no reuse permitted")
    dependency_inputs = {}
    for name in git(SOURCE, "ls-files", "android").splitlines():
        if (name.endswith((".gradle", ".gradle.kts", ".properties", ".toml", ".lockfile"))
                or name in {"android/gradlew", "android/gradlew.bat",
                            "android/gradle/wrapper/gradle-wrapper.jar", "android/gradle/verification-metadata.xml"}):
            dependency_inputs[name] = digest(SOURCE / name)
    require(dependency_inputs, "No build/dependency inputs")
    write("dependency-inputs.json", dependency_inputs)
    write("source-inputs.json", {
        "app_commit": ACCEPTED_COMMIT, "app_tree": ACCEPTED_TREE,
        "harness_commit": git(HARNESS, "rev-parse", "HEAD"),
        "harness_tree": git(HARNESS, "rev-parse", "HEAD^{tree}"),
        "pr_head_commit": os.environ["EOS_HEAD_SHA"],
        "fixture_sha256": digest(HARNESS / "simulator/main.py"),
        "fixture_dependency_input_sha256": digest(HARNESS / "simulator/pyproject.toml"),
        "driver_sha256": digest(DRIVER), "launcher_sha256": digest(Path(__file__)),
        "workflow_sha256": digest(HARNESS / ".github/workflows/android.yml"),
        "avd_name": AVD_NAME, "prepared_at": time.time(),
    })
    require(not VENV.exists(), "Fixture environment already exists")
    bounded_logged([sys.executable, "-m", "venv", VENV], "venv.log", seconds=30)
    bounded_logged([PYTHON, "-m", "pip", "install", "--disable-pip-version-check", "-e", HARNESS / "simulator"],
                   "fixture-install.log", seconds=180)
    dependencies = command([PYTHON, "-c",
        "import importlib.metadata,json; print(json.dumps({d.metadata['Name']:d.version "
        "for d in importlib.metadata.distributions()}))"])
    write("simulator-dependencies.json", json.loads(dependencies))
    # Import the exact fixture in this exact environment; never GET its original.
    command([PYTHON, "-c",
             "import main,pathlib; pathlib.Path(__import__('sys').argv[1])"
             ".write_bytes(main.capture_delivery_jpeg('original'))",
             OUT / "expected-original.jpg"], cwd=HARNESS / "simulator")
    bounded_logged(["bash", "./gradlew", "--no-daemon", ":app:assembleDebug", "-PlocalDebugApplicationIdSuffix=true"],
                   "app-build.log", cwd=SOURCE / "android", seconds=600)
    require(APK.is_file(), "Ordinary debug APK is missing")
    require(not git(SOURCE, "diff", "HEAD", "--"), "Build modified accepted tracked source")
    write("build-input.json", {
        **read("source-inputs.json"), "dependency_inputs_sha256": digest(OUT / "dependency-inputs.json"),
        "apk_sha256": digest(APK), "expected_original_sha256": digest(OUT / "expected-original.jpg"),
        "build_command": "bash ./gradlew --no-daemon :app:assembleDebug -PlocalDebugApplicationIdSuffix=true",
        "action_pins": PINS, "simulator_dependencies": read("simulator-dependencies.json"),
        "build_tools": {"python": command([PYTHON, "--version"]), "java": command(["java", "-version"]),
                        "gradle_wrapper": (
                            SOURCE / "android/gradle/wrapper/gradle-wrapper.properties").read_text().strip(),
                        "gradle_plugins_source_sha256": digest(SOURCE / "android/build.gradle.kts")},
    })


def configure_avd():
    kvm()
    prepared = read("source-inputs.json")
    require(os.environ.get("ANDROID_AVD_HOME") == str(AVD_CONFIG.parent.parent), "Unexpected action AVD location")
    require(AVD_CONFIG.is_file() and not AVD_CONFIG.is_symlink()
            and AVD_CONFIG.stat().st_mtime >= prepared["prepared_at"], "New AVD config is unavailable")
    # Only this newly created AVD's presentation setting is changed.
    lines = [line for line in AVD_CONFIG.read_text().splitlines() if not re.match(r"\s*hw\.keyboard\s*=", line)]
    AVD_CONFIG.write_text("\n".join([*lines, "hw.keyboard=no", ""]))
    require(re.findall(r"(?m)^hw\.keyboard=(.*)$", AVD_CONFIG.read_text()) == ["no"],
            "Hardware keyboard config mismatch")
    write("avd-prelaunch.json", {"avd_name": AVD_NAME, "hw.keyboard": "no",
                                "config_sha256": digest(AVD_CONFIG), "configured_at": time.time()})


def adb(*args, seconds=10):
    return command([Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb", "-s", "emulator-5554", *args],
                   seconds=seconds)


def no_default_app():
    packages = adb("shell", "pm", "list", "packages").splitlines()
    require(f"package:{DEFAULT_APP}" not in packages, "Default application exists; refusing to touch it")
    return packages


def process_identity(pid):
    # Start time survives exec and prevents killing a reused PID.
    text = Path(f"/proc/{pid}/stat").read_text()
    return text.rsplit(")", 1)[1].split()[19]


def stop_peer(record):
    pid = record["pid"]
    try:
        require(process_identity(pid) == record["start_ticks"], "Peer PID was reused; no signal sent")
        if Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[0] == "Z":
            return "already exited"
    except FileNotFoundError:
        return "already exited"
    require(os.getpgid(pid) == pid, "Peer process-group ownership changed")
    os.killpg(pid, signal.SIGTERM)
    until = time.monotonic() + 8
    while time.monotonic() < until:
        try:
            state = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[0]
            if state == "Z":
                return "terminated"
        except FileNotFoundError:
            return "terminated"
        time.sleep(0.1)
    require(process_identity(pid) == record["start_ticks"], "Peer identity changed during cleanup")
    os.killpg(pid, signal.SIGKILL)
    return "killed after bounded shutdown"


def owns_peer_listener(pid):
    inodes = {line.split()[9] for line in Path("/proc/net/tcp").read_text().splitlines()[1:]
              if line.split()[1] == "0100007F:46A0" and line.split()[3] == "0A"}
    for fd in Path(f"/proc/{pid}/fd").iterdir():
        try:
            if os.readlink(fd) in {f"socket:[{inode}]" for inode in inodes}:
                return True
        except FileNotFoundError:
            pass
    return False


def run():
    kvm()
    require(os.environ.get("ANDROID_SERIAL") == "emulator-5554", "Driver target is not the owned emulator")
    require(read("avd-prelaunch.json")["hw.keyboard"] == "no", "Pre-launch hook did not finish")
    require(re.findall(r"(?m)^hw\.keyboard=(.*)$", AVD_CONFIG.read_text()) == ["no"],
            "Actual hardware keyboard differs")
    require(adb("emu", "avd", "name").splitlines()[0] == AVD_NAME, "Emulator is not the owned fresh AVD")
    identity = read("build-input.json")
    require(digest(APK) == identity["apk_sha256"] and digest(DRIVER) == identity["driver_sha256"]
            and digest(HARNESS / "simulator/main.py") == identity["fixture_sha256"],
            "Source/APK changed after preparation")
    actual_dependencies = command([PYTHON, "-c",
        "import importlib.metadata,json; print(json.dumps({d.metadata['Name']:d.version "
        "for d in importlib.metadata.distributions()}))"])
    require(json.loads(actual_dependencies) == identity["simulator_dependencies"], "Simulator environment changed")
    build_tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0"
    aapt, apksigner = build_tools / "aapt", build_tools / "apksigner"
    badging = command([aapt, "dump", "badging", APK])
    package = re.search(r"(?m)^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    require(package and package.groups() == (APP, "29", "0.13.0"), "Wrong package/version APK")
    require("application-debuggable" in badging, "APK is not ordinary debug")
    certs = command([apksigner, "verify", "--print-certs", APK])
    signer = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})", certs)
    require(len(signer) == 1, "APK signer unavailable/ambiguous")
    (OUT / "apk-badging.txt").write_text(badging + "\n")
    (OUT / "apk-signer.txt").write_text(certs + "\n")
    identity.update(version_code=29, version_name="0.13.0", apk_signer_sha256=signer[0],
                    avd_hw_keyboard="no", fresh_ephemeral_emulator=True)
    identity["build_tools"].update({"aapt": command([aapt, "version"]),
        "apksigner": command([apksigner, "version"]), "adb": adb("version"),
        "emulator": command([Path(os.environ["ANDROID_HOME"]) / "emulator/emulator", "-version"]),
        "emulator_binary_sha256": digest(Path(os.environ["ANDROID_HOME"]) / "emulator/emulator"),
        "system_image": (Path(os.environ["ANDROID_HOME"])
                         / "system-images/android-36/default/x86_64/source.properties").read_text().strip(),
        "system_image_properties_sha256": digest(Path(os.environ["ANDROID_HOME"])
                                                 / "system-images/android-36/default/x86_64/source.properties")})
    write("identity-input.json", identity)
    before = no_default_app()
    require(f"package:{APP}" not in before, "Isolated application already exists")
    write("packages-before.json", before)
    for key in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        adb("shell", "settings", "put", "global", key, "1.0")
        require(adb("shell", "settings", "get", "global", key) == "1.0", "Animation scale is not normal")
    require(adb("shell", "getprop", "ro.build.version.sdk") == "36", "Wrong API")
    require(adb("shell", "getprop", "ro.kernel.qemu") == "1", "Not an emulator")
    install = adb("install", APK, seconds=90)  # No -r, -t, instrumentation, default-app install, or uninstall.
    (OUT / "apk-install.txt").write_text(install + "\n")
    write("packages-after-install.json", no_default_app())
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 18080))  # Fail closed if any listener already owns the port.
    peer = None
    record = None
    try:
        with (OUT / "peer.log").open("wb") as log:
            peer = subprocess.Popen([str(PYTHON), "-m", "uvicorn", "main:app", "--host", "127.0.0.1",
                                     "--port", "18080", "--workers", "1", "--no-access-log"],
                                    cwd=HARNESS / "simulator", stdout=log, stderr=subprocess.STDOUT,
                                    start_new_session=True)
            record = {"pid": peer.pid, "start_ticks": process_identity(peer.pid)}
            write("peer-owner.json", record)
            until = time.monotonic() + 15
            while True:
                require(peer.poll() is None, "Owned peer exited before readiness")
                if owns_peer_listener(peer.pid):
                    with urlopen("http://127.0.0.1:18080/health", timeout=2) as response:
                        require(response.status == 200, "Peer health failed")
                    break
                require(time.monotonic() < until, "Owned loopback peer did not become ready")
                time.sleep(0.1)
            # Driver journey275s + cleanup25s = intended total300s.
            # External325s reserves terminal-evidence/owned-process overhead, not extra UI time.
            bounded_logged([PYTHON, DRIVER, "--apk", APK, "--identity", OUT / "identity-input.json",
                            "--expected-original", OUT / "expected-original.jpg", "--output-dir", OUT / "driver",
                            "--adb", Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb",
                            "--aapt", aapt, "--apksigner", apksigner], "driver.log", seconds=325)
    finally:
        cleanup = {}
        if record:
            cleanup["peer"] = stop_peer(record)
            if peer:
                peer.wait(timeout=3)
        cleanup["default_app_absent"] = f"package:{DEFAULT_APP}" not in no_default_app()
        write("launcher-cleanup.json", cleanup)


def finalize():
    # Action normally kills its own AVD. If it did not, only the verified owned AVD may be stopped.
    cleanup = {}
    for owner in sorted(OUT.glob("*.log-owner.json")):
        cleanup[owner.name] = stop_peer(read(owner.name))
    if (OUT / "peer-owner.json").exists():
        cleanup["peer"] = stop_peer(read("peer-owner.json"))
    devices = command([Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb", "devices"])
    if re.search(r"(?m)^emulator-5554\s", devices):
        require(adb("emu", "avd", "name").splitlines()[0] == AVD_NAME, "Live emulator ownership mismatch")
        adb("emu", "kill")
        until = time.monotonic() + 10
        while re.search(r"(?m)^emulator-5554\s", command(
                [Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb", "devices"], seconds=2)):
            require(time.monotonic() < until, "Owned emulator did not terminate")
            time.sleep(0.2)
    cleanup["emulator"] = "not running"
    write("final-process-cleanup.json", cleanup)
    path = OUT / "driver/result.json"
    result = (json.loads(path.read_text()) if path.exists()
              else {"outcome": "NOT EXERCISED", "reason": "Driver result is absent"})
    passed = (os.environ["EOS_ACTION_OUTCOME"] == "success" and result["outcome"] == "PASS"
              and (OUT / "launcher-cleanup.json").exists()
              and read("launcher-cleanup.json").get("default_app_absent") is True)
    write("job-result.json", {"outcome": ("PASS" if passed
                                        else "NOT EXERCISED" if result["outcome"] == "PASS" else result["outcome"]),
                              "driver_outcome": result["outcome"], "action_outcome": os.environ["EOS_ACTION_OUTCOME"],
                              "reason": (result.get("reason") if passed or result["outcome"] != "PASS"
                                         else "Action or launcher cleanup failed; driver PASS is insufficient"),
                              "visual_review": {"status": "PENDING", "reviewer": "owner"},
                              "final_acceptance": False, "release_hold": True, "original_root_cause": "UNKNOWN",
                              "physical_camera": False, "cleanup": cleanup})
    require(passed, "No automated PASS; inspect job/driver outcomes")


if __name__ == "__main__":
    def terminate(_signal, _frame):
        raise SystemExit("Launcher received termination; unwinding owned subprocesses")

    signal.signal(signal.SIGTERM, terminate)
    phase = sys.argv[1]
    try:
        {"prepare": prepare, "configure-avd": configure_avd, "run": run, "finalize": finalize}[phase]()
    except BaseException as exc:
        write(f"launcher-{phase}-failure.json", {"phase": phase, "outcome": "NOT EXERCISED",
                                               "reason": f"{type(exc).__name__}: {exc}"})
        raise
