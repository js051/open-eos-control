from __future__ import annotations

import asyncio
import subprocess
import threading
from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiSession
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import SubprocessByteStream
from open_eos_bridge.models import CameraDescriptor
from open_eos_bridge.sessions import SessionManager


def session(identifier: str, *, error: BaseException | None = None) -> Mock:
    value = Mock(
        engine_name="ccapi",
        camera=CameraDescriptor(id=identifier, model="Synthetic", port="http://camera.invalid", engine="ccapi"),
    )
    value.close.side_effect = error
    return value


@pytest.mark.parametrize("failure", [
    OSError("Synthetic OS cleanup failure"),
    RuntimeError("Synthetic provider cleanup failure"),
    subprocess.TimeoutExpired(["synthetic-gphoto2"], 5),
    BridgeError("SHUTTER_RELEASE_UNCONFIRMED", "Synthetic release failure"),
])
def test_shutdown_drains_remaining_sessions_and_preserves_first_failure(failure: Exception) -> None:
    manager = SessionManager(Mock())
    first = session("synthetic-first", error=failure)
    second = session("synthetic-second", error=OSError("Synthetic later failure"))
    last = session("synthetic-last")
    for value in (first, second, last):
        manager._register(value)

    with pytest.raises(type(failure)) as caught:
        manager.close_all()

    assert caught.value is failure
    for value in (first, second, last):
        value.close.assert_called_once_with()
    assert not manager._sessions
    assert not manager._camera_sessions


def test_successful_shutdown_closes_every_session_once() -> None:
    manager = SessionManager(Mock())
    values = [session("synthetic-first"), session("synthetic-second")]
    for value in values:
        manager._register(value)
    manager.close_all()
    manager.close_all()
    for value in values:
        value.close.assert_called_once_with()


@pytest.mark.parametrize("failure", [KeyboardInterrupt(), SystemExit(1), asyncio.CancelledError()])
def test_shutdown_does_not_swallow_process_interruption(failure: BaseException) -> None:
    manager = SessionManager(Mock())
    first = session("synthetic-first", error=failure)
    later = session("synthetic-later")
    manager._register(first)
    manager._register(later)
    with pytest.raises(type(failure)) as caught:
        manager.close_all()
    assert caught.value is failure
    later.close.assert_not_called()


@pytest.mark.parametrize("operation", ["terminate", "wait"])
def test_real_subprocess_cleanup_error_does_not_skip_later_ccapi_close(operation: str) -> None:
    # Execute the production stream cleanup against synthetic process calls;
    # no camera, subprocess, socket, or provider is started by this fixture.
    stream = object.__new__(SubprocessByteStream)
    stream._close_lock = threading.Lock()
    stream._closed = threading.Event()
    stream._stop_reading = threading.Event()
    stream._process = Mock()
    stream._process.poll.return_value = None
    stream._stdout_thread = Mock()
    stream._stderr_thread = Mock()
    failure = OSError("Synthetic terminate failure") if operation == "terminate" else subprocess.TimeoutExpired(
        ["synthetic-gphoto2"], 5,
    )
    getattr(stream._process, operation).side_effect = failure

    manager = SessionManager(Mock())
    first = session("synthetic-first")
    first.engine_name = "libgphoto2"
    first.camera = CameraDescriptor(
        id="synthetic-local", model="Synthetic", port="usb:001,001", engine="libgphoto2",
    )
    first.close.side_effect = stream.close
    transport = Mock()
    later = CcapiSession("http://camera.invalid", transport)
    manager._register(first)
    manager._register(later)
    with pytest.raises(type(failure)) as caught:
        manager.close_all()
    assert caught.value is failure
    transport.close.assert_called_once_with()
    assert later._closed
    if operation == "wait":
        stream._process.kill.assert_called_once_with()
        assert stream._process.wait.call_count == 2


def test_application_lifespan_drains_sessions_after_ordinary_close_failure() -> None:
    engine = Mock()
    engine.name = "libgphoto2"
    app = create_app(engine=engine, edsdk_engine=engine, ccapi_engine=Mock())
    manager = app.state.session_manager
    failure = OSError("Synthetic shutdown failure")
    first = session("synthetic-first", error=failure)
    later = session("synthetic-later")
    manager._register(first)
    manager._register(later)
    with pytest.raises(OSError) as caught, TestClient(app):
        pass
    assert caught.value is failure
    first.close.assert_called_once_with()
    later.close.assert_called_once_with()
