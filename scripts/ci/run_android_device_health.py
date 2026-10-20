#!/usr/bin/env python3
"""Run a child command only while the Android window observer remains usable.

This guard does not interpret or rewrite test results. It detects only a currently
focused SystemUI ANR dialog, not historical ANR text or a general log occurrence.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import resource
import signal
import subprocess
import sys
import tempfile
import time
from typing import Callable, Sequence


UNHEALTHY_EXIT = 86
OBSERVATION_ERROR_EXIT = 87
POLL_SECONDS = 5.0
ADB_TIMEOUT_SECONDS = 5.0
CAPTURE_LIMIT_BYTES = 16 * 1024 * 1024
FOCUSED_SYSTEMUI_ANR = re.compile(
    r"^\s*mCurrentFocus\s*=\s*Window\{[^}\r\n]*"
    r"\bApplication Not Responding: com\.android\.systemui(?=[\s}])[^}\r\n]*\}\s*$"
)


@dataclass(frozen=True)
class CommandCapture:
    returncode: int | None
    stdout: bytes = b""
    stderr: bytes = b""
    error: str | None = None


@dataclass(frozen=True)
class Observation:
    observed_at: str
    window_text: str
    focused_anr: str | None = None
    error: str | None = None


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def focused_systemui_anr(window_text: str) -> str | None:
    """Return the original current-focus line, never a matching historical line."""
    return next(
        (line for line in window_text.splitlines() if FOCUSED_SYSTEMUI_ANR.fullmatch(line)),
        None,
    )


def stop_process_group(
    child: subprocess.Popen,
    grace_seconds: float = 2.0,
    kill_seconds: float = 1.0,
) -> list[str]:
    """Signal only the process group created for this child; all waits are bounded."""
    errors: list[str] = []

    def send(sig: int) -> None:
        try:
            os.killpg(child.pid, sig)
        except ProcessLookupError:
            pass
        except OSError as exc:
            errors.append(f"signal {sig}: {exc}")

    def group_exists() -> bool:
        try:
            os.killpg(child.pid, 0)
            return True
        except ProcessLookupError:
            return False
        except OSError as exc:
            errors.append(f"inspect owned process group: {exc}")
            return True

    send(signal.SIGTERM)
    deadline = time.monotonic() + grace_seconds
    while group_exists() and time.monotonic() < deadline:
        try:
            child.poll()  # Reap the leader; descendants may still own the group.
        except OSError as exc:
            errors.append(f"poll owned child: {exc}")
        time.sleep(min(0.05, max(0.0, deadline - time.monotonic())))
    if group_exists():
        send(signal.SIGKILL)
    try:
        child.wait(timeout=kill_seconds)
    except subprocess.TimeoutExpired:
        errors.append("owned child did not exit within the bounded kill wait")
    except OSError as exc:
        errors.append(f"wait for owned child: {exc}")
    return errors


def run_capture(command: Sequence[str], timeout: float = ADB_TIMEOUT_SECONDS) -> CommandCapture:
    """Use temporary files so inherited pipe handles cannot hang communicate()."""
    try:
        with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
            child = subprocess.Popen(
                list(command), stdout=stdout, stderr=stderr, start_new_session=True,
                preexec_fn=limit_capture_file_size,
            )
            error = None
            try:
                child.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                error = f"command timed out after {timeout:g} seconds"
            finally:
                # An exited leader can leave same-group descendants holding the
                # temporary output handles. Reap that owned group in every case.
                cleanup = stop_process_group(child, grace_seconds=0.25, kill_seconds=0.5)
                if cleanup:
                    error = (error + "; " if error else "") + "; ".join(cleanup)
            stdout.seek(0)
            stderr.seek(0)
            output = stdout.read(CAPTURE_LIMIT_BYTES + 1)
            diagnostic = stderr.read(64 * 1024 + 1)
            if len(output) > CAPTURE_LIMIT_BYTES or len(diagnostic) > 64 * 1024:
                error = (error + "; " if error else "") + "command output exceeded capture limit"
            return CommandCapture(
                child.poll(), output[:CAPTURE_LIMIT_BYTES], diagnostic[:64 * 1024], error,
            )
    except (OSError, subprocess.SubprocessError) as exc:
        return CommandCapture(None, error=f"unable to run command: {exc}")


def limit_capture_file_size() -> None:
    """Bound each temporary output file in this capture child and descendants only."""
    _, inherited_hard = resource.getrlimit(resource.RLIMIT_FSIZE)
    limit = CAPTURE_LIMIT_BYTES + 1
    if inherited_hard != resource.RLIM_INFINITY:
        limit = min(limit, inherited_hard)
    resource.setrlimit(resource.RLIMIT_FSIZE, (limit, limit))


def observe_device(
    runner: Callable[[Sequence[str], float], CommandCapture] = run_capture,
) -> Observation:
    # API 36 exposes current focus in displays, not the windows-only section. Match the
    # existing synthetic-suite observer, with both service and outer process deadlines.
    result = runner(["adb", "shell", "dumpsys", "-t", "1", "window", "displays"], ADB_TIMEOUT_SECONDS)
    text = result.stdout.decode("utf-8", errors="replace")
    error = result.error
    if result.returncode != 0:
        error = error or f"adb window observation exited {result.returncode}"
    if not text.strip():
        error = error or "adb window observation returned no window state"
    elif not re.search(r"^\s*mCurrentFocus\s*=", text, re.MULTILINE):
        error = error or "adb window observation contained no current-focus field"
    if error and result.stderr:
        error += ": " + result.stderr.decode("utf-8", errors="replace").strip()
    # Keep exact focus lines, not the surrounding display/window/application inventory.
    focus_text = "\n".join(
        line for line in text.splitlines()
        if re.match(r"^\s*(mCurrentFocus|mFocusedApp|mTopFocusedDisplayId)\s*=", line)
    )
    return Observation(utc_now(), focus_text, focused_systemui_anr(text), error)


def collect_diagnostics(
    evidence_dir: Path,
    runner: Callable[[Sequence[str], float], CommandCapture] = run_capture,
) -> dict:
    results = {}
    for name, command in (
        ("systemui-anr.png", ["adb", "exec-out", "screencap", "-p"]),
        ("lastanr.txt", ["adb", "shell", "dumpsys", "activity", "lastanr"]),
    ):
        try:
            result = runner(command, ADB_TIMEOUT_SECONDS)
            details = {"returncode": result.returncode}
            if result.error:
                details["error"] = result.error
            if result.stderr:
                details["stderr"] = result.stderr.decode("utf-8", errors="replace")
            if result.stdout:
                (evidence_dir / name).write_bytes(result.stdout)
                details["artifact"] = name
            elif result.returncode == 0:
                details["error"] = "diagnostic command returned empty output"
            results[name] = details
        except Exception as exc:
            results[name] = {"error": f"diagnostic capture failed: {exc}"}
    return results


def write_report(evidence_dir: Path, report: dict, observation: Observation | None = None) -> bool:
    """Best effort evidence must never prevent owned-child cleanup."""
    try:
        evidence_dir.mkdir(parents=True, exist_ok=True)
        if observation is not None:
            (evidence_dir / "window-focus.txt").write_text(observation.window_text, encoding="utf-8")
        temporary = evidence_dir / "device-health.json.tmp"
        temporary.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        temporary.replace(evidence_dir / "device-health.json")
        return True
    except OSError as exc:
        print(f"Android health guard could not write evidence: {exc}", file=sys.stderr)
        return False


def start_child(command: Sequence[str]) -> subprocess.Popen:
    # Inherit stdout/stderr unchanged. No shell, global daemon cleanup or package mutation.
    return subprocess.Popen(list(command), start_new_session=True)


class GuardInterrupted(BaseException):
    def __init__(self, signum: int):
        self.signum = signum


def run_guard(
    command: Sequence[str],
    evidence_dir: Path,
    *,
    observe: Callable[[], Observation] = observe_device,
    collect: Callable[[Path], dict] = collect_diagnostics,
    start: Callable[[Sequence[str]], subprocess.Popen] = start_child,
    stop: Callable[[subprocess.Popen], list[str]] = stop_process_group,
    monotonic: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
    poll_seconds: float = POLL_SECONDS,
) -> int:
    """Inject observers/process controls/clocks for tests without adb or native tools."""
    if poll_seconds <= 0:
        raise ValueError("poll_seconds must be positive")
    child = None
    observations = 0
    report = {
        "started_at": utc_now(), "command_started": False, "outcome": "preflight_pending",
        "cleanup_scope": "owned_host_process_group_only",
        "cleanup_caveat": (
            "Detached Gradle/device instrumentation may outlive the owned group; "
            "existing emulator-runner cleanup remains responsible. XML/logs may be partial."
        ),
    }

    def stop_owned() -> None:
        if child is not None:
            try:
                report["cleanup_errors"] = stop(child)
            except Exception as exc:
                report["cleanup_errors"] = [f"owned-child cleanup failed: {exc}"]
            try:
                returncode = child.poll()
                if returncode is not None:
                    report.setdefault("child_returncode", returncode)
            except Exception as exc:
                report.setdefault("cleanup_errors", []).append(f"final child status unavailable: {exc}")

    def inspect() -> int | None:
        nonlocal observations
        observations += 1
        try:
            observation = observe()
        except Exception as exc:
            observation = Observation(utc_now(), "", error=f"window observer failed: {exc}")
        report["observation_count"] = observations
        report["last_observed_at"] = observation.observed_at
        if observation.error:
            report.update(outcome="environment_observation_error", error=observation.error,
                          exit_code=OBSERVATION_ERROR_EXIT, finished_at=utc_now())
            print(f"Android device health {observation.observed_at}: observation error: "
                  f"{observation.error}", file=sys.stderr, flush=True)
            if observation.focused_anr is not None:
                print(observation.focused_anr, file=sys.stderr, flush=True)
            stop_owned()
            write_report(evidence_dir, report, observation)
            return OBSERVATION_ERROR_EXIT
        if observation.focused_anr is None:
            report["last_observation"] = "no_detected_focused_systemui_anr"
            if not write_report(evidence_dir, report, observation):
                report.update(outcome="environment_observation_error",
                              error="unable to persist window observation", exit_code=OBSERVATION_ERROR_EXIT,
                              finished_at=utc_now())
                print(f"Android device health {observation.observed_at}: "
                      "unable to persist window observation", file=sys.stderr, flush=True)
                stop_owned()
                write_report(evidence_dir, report)
                return OBSERVATION_ERROR_EXIT
            return None
        report.update(outcome="unhealthy", reason="focused_systemui_anr",
                      focused_window=observation.focused_anr, exit_code=UNHEALTHY_EXIT)
        print(f"Android device health {observation.observed_at}: focused SystemUI ANR; "
              f"{observation.focused_anr}", file=sys.stderr, flush=True)
        # Preserve the original observation before any slow diagnostic action.
        if not write_report(evidence_dir, report, observation):
            report["evidence_error"] = "unable to persist original ANR observation"
        stop_owned()
        try:
            report["diagnostics"] = collect(evidence_dir)
        except Exception as exc:
            report["diagnostics"] = {"error": f"diagnostic collection failed: {exc}"}
        report["finished_at"] = utc_now()
        write_report(evidence_dir, report)
        return UNHEALTHY_EXIT

    try:
        if not write_report(evidence_dir, report):
            print(f"Android device health {utc_now()}: evidence directory is not writable; "
                  "command was not started", file=sys.stderr, flush=True)
            return OBSERVATION_ERROR_EXIT
        preflight = inspect()
        if preflight is not None:
            return preflight
        child = start(command)
        report.update(command_started=True, child_pid=child.pid, outcome="running")
        if not write_report(evidence_dir, report):
            report.update(outcome="environment_observation_error",
                          error="unable to persist command start", exit_code=OBSERVATION_ERROR_EXIT,
                          finished_at=utc_now())
            stop_owned()
            write_report(evidence_dir, report)
            return OBSERVATION_ERROR_EXIT
        next_observation = monotonic() + poll_seconds
        while True:
            returncode = child.poll()
            if returncode is not None:
                # A short failing command may finish under an ANR before the next poll.
                # Preserve its original status even when the final environment check fails.
                report["child_returncode"] = returncode
                final_observation = inspect()
                if final_observation is not None:
                    return final_observation
                # Preserve ordinary failures; a completed command is not a claim of test pass.
                exit_code = returncode if returncode >= 0 else 128 - returncode
                report.update(outcome="child_completed" if returncode == 0 else "child_failed",
                              child_returncode=returncode, exit_code=exit_code, finished_at=utc_now())
                if not write_report(evidence_dir, report):
                    stop_owned()
                    return OBSERVATION_ERROR_EXIT
                return exit_code
            now = monotonic()
            if now >= next_observation:
                next_observation = now + poll_seconds
                unhealthy = inspect()
                if unhealthy is not None:
                    return unhealthy
            else:
                sleep(min(0.2, next_observation - now))
    except (KeyboardInterrupt, GuardInterrupted) as exc:
        exit_code = 128 + (exc.signum if isinstance(exc, GuardInterrupted) else signal.SIGINT)
        report.update(outcome="interrupted", exit_code=exit_code, finished_at=utc_now())
        stop_owned()
        write_report(evidence_dir, report)
        return exit_code
    except Exception as exc:
        report.update(outcome="environment_observation_error", error=str(exc),
                      exit_code=OBSERVATION_ERROR_EXIT, finished_at=utc_now())
        print(f"Android device health {report['finished_at']}: guard error: {exc}",
              file=sys.stderr, flush=True)
        stop_owned()
        write_report(evidence_dir, report)
        return OBSERVATION_ERROR_EXIT


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-dir", required=True, type=Path)
    parser.add_argument("command", nargs=argparse.REMAINDER, help="-- COMMAND [args]")
    args = parser.parse_args(argv)
    if not args.command or args.command[0] != "--" or len(args.command) == 1:
        parser.error("a child command is required after --")
    if os.name != "posix":
        parser.error("the owned-process-group guard requires a POSIX host")

    def interrupted(signum, _frame):
        raise GuardInterrupted(signum)

    previous = signal.signal(signal.SIGTERM, interrupted)
    try:
        return run_guard(args.command[1:], args.evidence_dir)
    finally:
        signal.signal(signal.SIGTERM, previous)


if __name__ == "__main__":
    raise SystemExit(main())
