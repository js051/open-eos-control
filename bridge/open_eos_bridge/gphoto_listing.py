"""Bounded, cancellable CLI listing reads. No shell, retries, or camera fanout."""

from __future__ import annotations

import os
import select
import signal
import subprocess
import time
from collections.abc import Callable, Mapping
from contextlib import suppress

from .errors import BridgeError
from .media_listing import listing_progress_check

MAX_LIST_OUTPUT_BYTES = 32 * 1024 * 1024
MAX_LIST_LINE_BYTES = 65_536
MAX_LIST_STDERR_BYTES = 256 * 1024
LIST_POLL_SECONDS = 0.05
LIST_TERMINATE_SECONDS = 0.5


def listing_cancelled() -> BridgeError:
    return BridgeError("MEDIA_LIST_CANCELLED", "Media listing was cancelled.", status_code=409, engine="libgphoto2")


def _pipe_read(fd: int) -> bytes | None:
    """None means no data yet; b'' means EOF. Never block on an inherited pipe."""
    if os.name == "nt":
        import ctypes
        import msvcrt
        from ctypes import wintypes

        available = wintypes.DWORD()
        peek = ctypes.WinDLL("kernel32", use_last_error=True).PeekNamedPipe
        peek.argtypes = [wintypes.HANDLE, wintypes.LPVOID, wintypes.DWORD,
                         ctypes.POINTER(wintypes.DWORD), ctypes.POINTER(wintypes.DWORD),
                         ctypes.POINTER(wintypes.DWORD)]
        peek.restype = wintypes.BOOL
        if not peek(msvcrt.get_osfhandle(fd), None, 0, None, ctypes.byref(available), None):
            error = ctypes.get_last_error()
            if error in (109, 232):  # ERROR_BROKEN_PIPE / ERROR_NO_DATA
                return b""
            raise OSError(error, "Could not inspect listing pipe")
        return os.read(fd, min(available.value, 64 * 1024)) if available.value else None
    if not select.select([fd], [], [], 0)[0]:
        return None
    return os.read(fd, 64 * 1024)


def _stop_listing_process(process: subprocess.Popen) -> None:
    """Stop the owned native process group; never wait on pipe-reading threads."""
    if os.name != "nt":
        with suppress(ProcessLookupError):
            os.killpg(process.pid, signal.SIGTERM)
        # The group may still contain a child holding a pipe after its leader exits.
        deadline = time.monotonic() + LIST_TERMINATE_SECONDS
        while time.monotonic() < deadline:
            process.poll()
            try:
                os.killpg(process.pid, 0)
            except ProcessLookupError:
                break
            time.sleep(LIST_POLL_SECONDS)
        with suppress(ProcessLookupError):
            os.killpg(process.pid, signal.SIGKILL)
    elif process.poll() is None:
        process.terminate()
    try:
        process.wait(timeout=LIST_TERMINATE_SECONDS)
    except subprocess.TimeoutExpired:
        process.kill()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired as error:
            raise BridgeError(
                "MEDIA_LIST_CLEANUP_UNCONFIRMED", "Media listing process cleanup is unconfirmed.", status_code=409,
            ) from error


def run_listing_command(
    command: list[str],
    *,
    environment: Mapping[str, str],
    timeout: float,
    cancelled: Callable[[], bool],
    on_line: Callable[[str], None],
    on_started: Callable[[], None],
    on_failed: Callable[[], None],
) -> tuple[int, str]:
    """Publish complete UTF-8 lines before exit; drain both pipes and reap on exit.

    Native POSIX processes get an owned group. Windows pipe reads use PeekNamedPipe
    rather than blocking reader threads; the runner fences abnormal Windows/WSL
    cleanup because the local handle cannot establish remote/descendant cleanup.
    """
    started = time.monotonic()

    def check() -> None:
        if cancelled():
            raise listing_cancelled()
        if time.monotonic() - started >= timeout:
            raise BridgeError("ENGINE_TIMEOUT", "Media listing exceeded its time limit.", status_code=504,
                              engine="libgphoto2")

    check()
    process = subprocess.Popen(
        command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=environment, start_new_session=os.name != "nt",
    )
    on_started()
    assert process.stdout is not None and process.stderr is not None
    stderr = bytearray()
    pending = bytearray()
    total = 0
    pipes = {process.stdout.fileno(): True, process.stderr.fileno(): False}
    succeeded = False

    def line(value: bytes) -> None:
        check()
        if len(value) > MAX_LIST_LINE_BYTES:
            raise BridgeError("MEDIA_LIST_RESOURCE_LIMIT", "Media listing line exceeds the resource bound.")
        try:
            text = value.decode("utf-8")
        except UnicodeError as error:
            raise BridgeError("MEDIA_LIST_INVALID_OUTPUT", "Media listing text is invalid.") from error
        with listing_progress_check(check):
            on_line(text.rstrip("\r\n"))
        check()

    try:
        while pipes or process.poll() is None:
            check()
            read_any = False
            for fd, stdout in list(pipes.items()):
                try:
                    chunk = _pipe_read(fd)
                except OSError as error:
                    raise BridgeError("MEDIA_LIST_FAILED", "Media listing output could not be read.") from error
                if chunk is None:
                    continue
                read_any = True
                if not chunk:
                    del pipes[fd]
                    continue
                if not stdout:
                    stderr.extend(chunk)
                    if len(stderr) > MAX_LIST_STDERR_BYTES:
                        del stderr[:-MAX_LIST_STDERR_BYTES]
                    continue
                total += len(chunk)
                if total > MAX_LIST_OUTPUT_BYTES:
                    raise BridgeError("MEDIA_LIST_RESOURCE_LIMIT", "Media listing output exceeds the resource bound.")
                pending.extend(chunk)
                while (end := pending.find(b"\n")) >= 0:
                    value = bytes(pending[:end + 1])
                    del pending[:end + 1]
                    line(value)
                if len(pending) >= MAX_LIST_LINE_BYTES:
                    raise BridgeError("MEDIA_LIST_RESOURCE_LIMIT", "Media listing line exceeds the resource bound.")
            if not read_any:
                time.sleep(LIST_POLL_SECONDS)
        check()
        # Do not publish a trailing, possibly truncated row after a failed command.
        if process.returncode == 0 and pending:
            line(bytes(pending))
        succeeded = process.returncode == 0
    finally:
        try:
            if not succeeded:
                on_failed()
                try:
                    _stop_listing_process(process)
                except Exception as error:
                    raise BridgeError(
                        "MEDIA_LIST_CLEANUP_UNCONFIRMED", "Media listing process cleanup is unconfirmed.",
                        status_code=409,
                    ) from error
            else:
                process.wait()
        finally:
            process.stdout.close()
            process.stderr.close()
    check()
    return process.returncode, stderr.decode("utf-8", errors="replace")
