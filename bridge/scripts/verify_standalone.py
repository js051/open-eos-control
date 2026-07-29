from __future__ import annotations

import argparse
import json
import os
import socket
import subprocess
import time
import urllib.error
import urllib.request
from collections.abc import Sequence
from pathlib import Path

from open_eos_bridge import __version__


def available_loopback_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def read_url(url: str, timeout: float = 2.0) -> tuple[int, dict[str, str], bytes]:
    with urllib.request.urlopen(url, timeout=timeout) as response:
        headers = {key.casefold(): value for key, value in response.headers.items()}
        return response.status, headers, response.read()


def wait_for_root(
    process: subprocess.Popen[str],
    url: str,
    timeout_seconds: float = 60.0,
) -> tuple[dict[str, str], bytes]:
    deadline = time.monotonic() + timeout_seconds
    last_error = "service did not answer"
    while time.monotonic() < deadline:
        if process.poll() is not None:
            output = process.stdout.read() if process.stdout is not None else ""
            raise RuntimeError(f"Standalone process exited with {process.returncode}.\n{output}")
        try:
            status, headers, body = read_url(url)
            if status == 200:
                return headers, body
            last_error = f"HTTP {status}"
        except (OSError, TimeoutError, urllib.error.URLError) as error:
            last_error = str(error)
        time.sleep(0.2)
    raise RuntimeError(f"Standalone service was not ready within {timeout_seconds:.0f}s: {last_error}")


def stop_process_tree(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/PID", str(process.pid), "/T", "/F"],
            check=False,
            capture_output=True,
            text=True,
            timeout=15,
        )
    else:
        process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)


def require_service_stopped(url: str, timeout_seconds: float = 5.0) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            read_url(url, timeout=0.25)
        except (OSError, TimeoutError, urllib.error.URLError):
            return
        time.sleep(0.1)
    raise RuntimeError("Standalone process tree stopped but the loopback service is still reachable.")


def verify(executable: Path) -> None:
    executable = executable.resolve()
    if not executable.is_file():
        raise SystemExit(f"Standalone executable does not exist: {executable}")

    port = available_loopback_port()
    origin = f"http://127.0.0.1:{port}"
    environment = os.environ.copy()
    environment.update(
        {
            "OPEN_EOS_BRIDGE_HOST": "127.0.0.1",
            "OPEN_EOS_BRIDGE_PORT": str(port),
            "OPEN_EOS_BRIDGE_OPEN_BROWSER": "0",
        }
    )
    process = subprocess.Popen(
        [str(executable), "--no-browser"],
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    try:
        headers, root = wait_for_root(process, f"{origin}/")
        if b"Open EOS Control" not in root:
            raise RuntimeError("Bundled root page does not contain the Desktop Bridge UI.")
        if "default-src 'self'" not in headers.get("content-security-policy", ""):
            raise RuntimeError("Bundled root page is missing the expected Content-Security-Policy.")

        status, _, script = read_url(f"{origin}/app/app.js")
        if status != 200 or b"localVideo" not in script:
            raise RuntimeError("Bundled static application resources are incomplete.")

        status, _, health_body = read_url(f"{origin}/health", timeout=10.0)
        health = json.loads(health_body)
        if status != 200 or health.get("version") != __version__:
            raise RuntimeError("Bundled health endpoint returned the wrong product version.")
        if set(health.get("engines", {})) != {"libgphoto2", "ccapi"}:
            raise RuntimeError("Bundled health endpoint did not report both camera engines.")
    finally:
        stop_process_tree(process)
        require_service_stopped(f"{origin}/")


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Launch and verify a standalone Desktop Bridge executable.")
    parser.add_argument("executable", type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> None:
    args = build_argument_parser().parse_args(argv)
    verify(args.executable)
    print(f"Verified {args.executable.name} on loopback with bundled UI and camera engines.")


if __name__ == "__main__":
    main()
