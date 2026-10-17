"""Production gphoto Bulb ownership, with a synthetic CLI peer and no hardware."""
from __future__ import annotations

from unittest.mock import patch

import pytest

from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import GPhoto2Engine
from open_eos_bridge.models import CameraFeature, LiveViewStartRequest

from .fakes import FakeRunner

RELEASE_PATH = "/main/actions/eosremoterelease"


class BulbRunner(FakeRunner):
    lose_press = False
    reject_release = False
    lose_release = False

    def run(self, arguments, *, timeout=30.0):
        press = f"{RELEASE_PATH}=Press Full" in arguments
        release = f"{RELEASE_PATH}=Release Full" in arguments
        if release and self.reject_release:
            self.commands.append(tuple(arguments))
            raise BridgeError("CAMERA_COMMAND_FAILED", "Synthetic release was rejected.")
        result = super().run(arguments, timeout=timeout)
        if press and self.lose_press:
            raise BridgeError("CAMERA_COMMAND_FAILED", "Synthetic applied press response was lost.")
        if release and self.lose_release:
            raise OSError("Synthetic applied release response was lost.")
        return result

    def shutter_commands(self):
        return [argument for command in self.commands for argument in command
                if argument.startswith(RELEASE_PATH + "=")]


def camera(tmp_path, **failures):
    runner = BulbRunner()
    for name, value in failures.items():
        setattr(runner, name, value)
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()
    return session, runner


def test_unknown_press_and_rejected_cleanup_keep_visible_stop_responsibility(tmp_path):
    session, runner = camera(tmp_path, lose_press=True, reject_release=True)
    with pytest.raises(BridgeError) as failure:
        session.start_bulb_exposure()
    assert failure.value.code == "SHUTTER_RELEASE_UNCONFIRMED"
    status = session.status()
    assert status.bulb_exposure_active is None
    assert status.shutter_release_unconfirmed is True
    assert runner.values[RELEASE_PATH] == "Press Full"
    runner.reject_release = False
    stopped = session.stop_bulb_exposure()
    assert stopped.bulb_exposure_active is False
    assert stopped.shutter_release_unconfirmed is False
    assert runner.shutter_commands() == [f"{RELEASE_PATH}=Press Full"] + [f"{RELEASE_PATH}=Release Full"] * 2
    assert CameraFeature.BULB_EXPOSURE not in session._observed


@pytest.mark.parametrize("lost_start", [False, True])
def test_stop_retains_exact_validated_release_after_discovery_changes(tmp_path, lost_start):
    session, runner = camera(tmp_path, lose_press=lost_start, reject_release=lost_start)
    if lost_start:
        with pytest.raises(BridgeError):
            session.start_bulb_exposure()
    else:
        session.start_bulb_exposure()
    original = session._configs[RELEASE_PATH]
    # Mutating or replacing a rediscovered descriptor must not change the saved stop.
    original.path = "/main/actions/synthetic-replacement"
    original.readonly = True
    original.choices.clear()
    runner.reject_release = False
    before = len(runner.shutter_commands())
    with patch.object(session, "_bulb_values", side_effect=AssertionError("Do not rediscover a saved release")):
        stopped = session.stop_bulb_exposure()
    assert stopped.shutter_release_unconfirmed is False
    assert len(runner.shutter_commands()) == before + 1
    assert runner.shutter_commands()[-1] == f"{RELEASE_PATH}=Release Full"
    assert (CameraFeature.BULB_EXPOSURE in session._observed) is not lost_start


@pytest.mark.parametrize("operation", [
    "start_bulb_exposure", "capture_still", "half_press_shutter", "autofocus", "start_recording",
    "sync_camera_clock", "set_setting", "apply_setting_outcome", "drive_focus",
    "set_live_view_magnification", "start_live_view", "perform_maintenance_outcome",
])
def test_unknown_release_blocks_new_controls_but_not_safe_stop_or_read(tmp_path, operation):
    session, runner = camera(tmp_path, reject_release=True)
    session.start_bulb_exposure()
    with pytest.raises(BridgeError):
        session.stop_bulb_exposure()
    before = list(runner.commands)
    arguments = {"set_setting": ("iso", "800"), "apply_setting_outcome": ("iso", "800"),
                 "drive_focus": ("NEAR", "SMALL"), "set_live_view_magnification": (5,),
                 "start_live_view": (LiveViewStartRequest(),),
                 "perform_maintenance_outcome": ("clock-sync", False)}.get(operation, ())
    with pytest.raises(BridgeError) as blocked:
        getattr(session, operation)(*arguments)
    assert blocked.value.code == "SHUTTER_RELEASE_UNCONFIRMED"
    assert blocked.value.status_code == 409
    assert runner.commands == before
    assert session.status().shutter_release_unconfirmed is True
    session.stop_live_view()
    session.stop_recording()
    assert session.status().shutter_release_unconfirmed is True
    runner.reject_release = False
    session.stop_bulb_exposure()


def test_uncertain_bulb_prevents_new_recording_lifecycle_write(tmp_path):
    session, runner = camera(tmp_path, lose_press=True, reject_release=True)
    with pytest.raises(BridgeError):
        session.start_bulb_exposure()
    before = list(runner.commands)
    assert session.perform_recording_outcome("start")["responseEvidence"] == "not-sent"
    assert runner.commands == before
    assert session.perform_recording_outcome("stop")["responseEvidence"] in {"not-sent", "response-received"}
    assert session.status().shutter_release_unconfirmed is True


def test_lost_release_reply_stays_stop_only_until_explicit_retry(tmp_path):
    session, runner = camera(tmp_path, lose_release=True)
    session.start_bulb_exposure()
    with pytest.raises(BridgeError) as failure:
        session.stop_bulb_exposure()
    assert failure.value.code == "SHUTTER_RELEASE_UNCONFIRMED"
    assert runner.values[RELEASE_PATH] == "Release Full"
    assert session.status().shutter_release_unconfirmed is True
    runner.lose_release = False
    session.stop_bulb_exposure()
    assert runner.shutter_commands() == [f"{RELEASE_PATH}=Press Full"] + [f"{RELEASE_PATH}=Release Full"] * 2
    before = list(runner.shutter_commands())
    session.stop_bulb_exposure()
    assert runner.shutter_commands() == before


def test_acknowledged_stop_survives_status_read_failure_without_resending(tmp_path):
    session, runner = camera(tmp_path)
    session.start_bulb_exposure()
    with patch.object(session, "status", side_effect=BridgeError("SYNTHETIC_READ_FAILED", "Status unavailable")):
        with pytest.raises(BridgeError):
            session.stop_bulb_exposure()
    before = list(runner.shutter_commands())
    assert session.status().shutter_release_unconfirmed is False
    session.stop_bulb_exposure()
    assert runner.shutter_commands() == before


@pytest.mark.parametrize("rejected", [False, True])
def test_close_attempts_saved_stop_and_reports_unconfirmed_release(tmp_path, rejected):
    session, runner = camera(tmp_path, lose_press=True, reject_release=True)
    with pytest.raises(BridgeError):
        session.start_bulb_exposure()
    runner.reject_release = rejected
    if rejected:
        with pytest.raises(BridgeError) as failure:
            session.close()
        assert failure.value.code == "SHUTTER_RELEASE_UNCONFIRMED"
        assert "closed" in failure.value.message
    else:
        session.close()
    assert session._closed is True
    assert len(runner.shutter_commands()) == 3
    session.close()
    assert len(runner.shutter_commands()) == 3
    with pytest.raises(BridgeError) as closed:
        session.stop_bulb_exposure()
    assert closed.value.code == "SESSION_CLOSED"


def test_lost_press_with_acknowledged_cleanup_does_not_invent_bulb_observation(tmp_path):
    session, runner = camera(tmp_path, lose_press=True)
    with pytest.raises(BridgeError) as failure:
        session.start_bulb_exposure()
    assert failure.value.code == "CAMERA_COMMAND_FAILED"
    assert session.status().bulb_exposure_active is False
    assert session.status().shutter_release_unconfirmed is False
    assert CameraFeature.BULB_EXPOSURE not in session._observed
    session.stop_bulb_exposure()
    assert len(runner.shutter_commands()) == 2


def test_unknown_exposure_keeps_event_camera_io_paused(tmp_path):
    session, runner = camera(tmp_path, lose_press=True, reject_release=True)
    with pytest.raises(BridgeError):
        session.start_bulb_exposure()
    assert session.status().raw["eventPollingPaused"] is True
    session._pending_event_keys.clear()
    before = list(runner.commands)
    with patch.object(session, "_sleep"):
        session.poll_event()
    assert runner.commands == before


@pytest.mark.parametrize("disconnect", [False, True])
def test_asgi_status_retry_and_disconnect_preserve_same_session_stop(tmp_path, disconnect):
    from fastapi.testclient import TestClient
    from open_eos_bridge.app import create_app

    runner = BulbRunner()
    runner.lose_press = True
    runner.reject_release = True
    engine = GPhoto2Engine(runner, capture_directory=tmp_path)
    with TestClient(create_app(engine=engine)) as client:
        created = client.post("/v1/session", json={"engine": "libgphoto2"})
        assert created.status_code == 201, created.text
        route = f"/v1/session/{created.json()['id']}"
        failed = client.post(route + "/bulb/start")
        assert failed.status_code == 502, failed.text
        assert failed.json()["error"]["code"] == "SHUTTER_RELEASE_UNCONFIRMED"
        status = client.get(route + "/status").json()
        assert status["bulbExposureActive"] is None
        assert status["shutterReleaseUnconfirmed"] is True
        if disconnect:
            closed = client.delete(route)
            assert closed.status_code == 502, closed.text
            assert closed.json()["error"]["code"] == "SHUTTER_RELEASE_UNCONFIRMED"
            assert client.get(route + "/status").status_code == 404
            # A replacement session never inherits a prior camera's release command.
            replacement = client.post("/v1/session", json={"engine": "libgphoto2"})
            assert replacement.status_code == 201, replacement.text
            before = list(runner.shutter_commands())
            assert client.post(f"/v1/session/{replacement.json()['id']}/bulb/stop").status_code == 200
            assert runner.shutter_commands() == before
        else:
            runner.reject_release = False
            stopped = client.post(route + "/bulb/stop")
            assert stopped.status_code == 200, stopped.text
            assert stopped.json()["shutterReleaseUnconfirmed"] is False
            assert runner.shutter_commands() == [f"{RELEASE_PATH}=Press Full"] + [f"{RELEASE_PATH}=Release Full"] * 2


@pytest.mark.parametrize("cleanup", ["_stop_movie_stream", "_set_viewfinder"])
def test_close_cleanup_failure_cannot_hide_unconfirmed_shutter_release(tmp_path, cleanup):
    session, runner = camera(tmp_path, reject_release=True)
    session.start_bulb_exposure()
    session._live_view_active = True
    calls = 0
    def fail_cleanup(*args):
        nonlocal calls
        calls += 1
        # _run first stops the stream before dispatching release. Inject the
        # subsequent close-cleanup failure, after the release has been attempted.
        if cleanup != "_stop_movie_stream" or calls == 2:
            raise OSError("Synthetic cleanup failure")
    with patch.object(session, cleanup, side_effect=fail_cleanup):
        with pytest.raises(BridgeError) as failure:
            session.close()
    assert failure.value.code == "SHUTTER_RELEASE_UNCONFIRMED"
    assert "closed" in failure.value.message
    assert any("cleanup also failed" in note for note in failure.value.__notes__)
    assert session._closed is True
    assert session._last_error == failure.value.message
    assert len(runner.shutter_commands()) == 2


def test_next_deliberate_start_uses_choices_after_saved_release(tmp_path):
    session, runner = camera(tmp_path)
    session.start_bulb_exposure()
    session.stop_bulb_exposure()
    session.start_bulb_exposure()
    assert runner.shutter_commands() == [f"{RELEASE_PATH}=Press Full", f"{RELEASE_PATH}=Release Full",
                                          f"{RELEASE_PATH}=Press Full"]
    session.stop_bulb_exposure()
