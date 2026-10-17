"""Synthetic gphoto short-control release ownership; no camera or native CLI."""
from unittest.mock import patch

import pytest

from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import GPhoto2Engine
from open_eos_bridge.models import CameraFeature
from .fakes import FakeRunner

CASES = [
    ('half_press_shutter', '/main/actions/eosremoterelease', 'Press Half',
     '/main/actions/eosremoterelease', 'Release Half', CameraFeature.SHUTTER_HALF_PRESS),
    ('autofocus', '/main/actions/autofocusdrive', '1',
     '/main/actions/autofocuscancel', '1', CameraFeature.AUTOFOCUS),
]

class ShortRunner(FakeRunner):
    lose_press = False
    reject_release = False
    lose_release = False
    active = False

    def run(self, arguments, *, timeout=30.0):
        press = f'{self.start_path}={self.start_value}' in arguments
        release = f'{self.stop_path}={self.stop_value}' in arguments
        if release and self.reject_release:
            self.commands.append(tuple(arguments))
            raise BridgeError('SYNTHETIC_STOP_FAILED', 'Synthetic stop rejected.')
        result = super().run(arguments, timeout=timeout)
        if press:
            self.active = True
            if self.lose_press:
                raise BridgeError('SYNTHETIC_PRESS_LOST', 'Synthetic applied press response lost.')
        if release:
            self.active = False
            if self.lose_release:
                raise OSError('Synthetic applied stop response lost.')
        return result

    def writes(self):
        allowed = {f'{self.start_path}={self.start_value}', f'{self.stop_path}={self.stop_value}'}
        return [arg for command in self.commands for arg in command if arg in allowed]

@pytest.fixture(params=CASES, ids=['half-press', 'autofocus'])
def short(request, tmp_path):
    method, start_path, start_value, stop_path, stop_value, feature = request.param
    runner = ShortRunner()
    runner.start_path, runner.start_value = start_path, start_value
    runner.stop_path, runner.stop_value = stop_path, stop_value
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()
    session._sleep = lambda _: None
    return session, runner, lambda: getattr(session, method)(), feature


def test_lost_press_failed_cleanup_retains_correct_retry(short):
    session, runner, operate, feature = short
    runner.lose_press = runner.reject_release = True
    with pytest.raises(BridgeError) as failure:
        operate()
    assert failure.value.code == 'SHUTTER_RELEASE_UNCONFIRMED'
    assert failure.value.feature == feature.value
    status = session.status()
    assert status.shutter_release_unconfirmed is True
    assert status.bulb_exposure_active is False, 'Short controls are not Bulb exposures'
    assert runner.active is True
    runner.reject_release = False
    stopped = session.stop_bulb_exposure()
    assert stopped.shutter_release_unconfirmed is False
    assert runner.active is False
    assert runner.writes() == [f'{runner.start_path}={runner.start_value}'] + [f'{runner.stop_path}={runner.stop_value}'] * 2
    assert CameraFeature.BULB_EXPOSURE not in session._observed
    assert feature not in session._observed


def test_lost_press_with_successful_cleanup_keeps_original_failure(short):
    session, runner, operate, _ = short
    runner.lose_press = True
    with pytest.raises(BridgeError) as failure:
        operate()
    assert failure.value.code == 'SYNTHETIC_PRESS_LOST'
    assert runner.active is False
    assert session.status().shutter_release_unconfirmed is False
    assert len(runner.writes()) == 2


def test_retry_uses_immutable_release_without_rediscovery(short):
    session, runner, operate, _ = short
    runner.reject_release = True
    with pytest.raises(BridgeError):
        operate()
    descriptor = session._configs[runner.stop_path]
    descriptor.path = '/main/actions/synthetic-replacement'
    descriptor.readonly = True
    descriptor.choices.clear()
    runner.reject_release = False
    with patch.object(session, '_half_press_values', side_effect=AssertionError('no rediscovery')), \
         patch.object(session, '_autofocus_configs', side_effect=AssertionError('no rediscovery')):
        session.stop_bulb_exposure()
    assert runner.active is False
    assert len(runner.writes()) == 3
    assert runner.writes()[-1] == f'{runner.stop_path}={runner.stop_value}'


def test_lost_stop_ack_remains_unknown_and_retry_is_stop_only(short):
    session, runner, operate, _ = short
    runner.lose_release = True
    with pytest.raises(BridgeError) as failure:
        operate()
    assert failure.value.code == 'SHUTTER_RELEASE_UNCONFIRMED'
    assert runner.active is False
    assert session.status().shutter_release_unconfirmed is True
    runner.lose_release = False
    session.stop_bulb_exposure()
    assert len(runner.writes()) == 3
    assert session.status().shutter_release_unconfirmed is False


def test_close_failure_reports_control_not_exposure_and_retires_locally(short):
    session, runner, operate, feature = short
    runner.reject_release = True
    with pytest.raises(BridgeError):
        operate()
    with pytest.raises(BridgeError) as failure:
        session.close()
    assert failure.value.code == 'SHUTTER_RELEASE_UNCONFIRMED'
    assert failure.value.feature == feature.value
    assert 'exposing' not in failure.value.message
    assert session._closed is True
    before = len(runner.writes())
    session.close()
    assert len(runner.writes()) == before


def test_close_retries_only_saved_stop(short):
    session, runner, operate, _ = short
    runner.reject_release = True
    with pytest.raises(BridgeError):
        operate()
    runner.reject_release = False
    session.close()
    assert session._closed is True
    assert runner.active is False
    assert len(runner.writes()) == 3


def test_uncertain_short_control_pauses_event_camera_io(short):
    session, runner, operate, _ = short
    runner.reject_release = True
    with pytest.raises(BridgeError):
        operate()
    assert session.status().raw['eventPollingPaused'] is True
    session._pending_event_keys.clear()
    before = list(runner.commands)
    session.poll_event()
    assert runner.commands == before


@pytest.mark.parametrize('next_action', ['start_bulb_exposure', 'capture_still'])
def test_uncertain_short_control_blocks_new_writes(short, next_action):
    session, runner, operate, _ = short
    runner.reject_release = True
    with pytest.raises(BridgeError):
        operate()
    before = list(runner.commands)
    with pytest.raises(BridgeError) as failure:
        getattr(session, next_action)()
    assert failure.value.code == 'SHUTTER_RELEASE_UNCONFIRMED'
    assert runner.commands == before


def test_normal_control_can_repeat_without_stale_release(short):
    session, runner, operate, feature = short
    operate(); operate()
    assert len(runner.writes()) == 4
    assert runner.active is False
    assert feature in session._observed
    assert session.status().shutter_release_unconfirmed is False
    session.stop_bulb_exposure()
    assert len(runner.writes()) == 4


def test_acknowledged_bulb_cannot_be_overwritten_by_short_control(short):
    session, runner, operate, _ = short
    session.start_bulb_exposure()
    before = list(runner.commands)
    with pytest.raises(BridgeError) as failure:
        operate()
    assert failure.value.code == 'CAMERA_BUSY'
    assert runner.commands == before
    session.stop_bulb_exposure()


def test_stop_status_tail_failure_does_not_replay_acknowledged_release(short):
    session, runner, operate, _ = short
    runner.reject_release = True
    with pytest.raises(BridgeError):
        operate()
    runner.reject_release = False
    with patch.object(session, 'status', side_effect=BridgeError('SYNTHETIC_READ_FAILED', 'Synthetic read failed.')):
        with pytest.raises(BridgeError):
            session.stop_bulb_exposure()
    before = len(runner.writes())
    assert session.stop_bulb_exposure().shutter_release_unconfirmed is False
    assert len(runner.writes()) == before


def test_hold_failure_still_releases_before_propagating(short):
    session, runner, operate, _ = short
    session._sleep = lambda _: (_ for _ in ()).throw(OSError('Synthetic hold failed.'))
    with pytest.raises(OSError, match='Synthetic hold failed'):
        operate()
    assert runner.active is False
    assert session.status().shutter_release_unconfirmed is False
    assert len(runner.writes()) == 2


@pytest.mark.parametrize('disconnect', [False, True])
def test_asgi_short_control_retry_and_replacement_isolation(short, disconnect, tmp_path):
    from fastapi.testclient import TestClient
    from open_eos_bridge.app import create_app
    _, runner, _, feature = short
    runner.lose_press = runner.reject_release = True
    engine = GPhoto2Engine(runner, capture_directory=tmp_path)
    with TestClient(create_app(engine=engine)) as client:
        created = client.post('/v1/session', json={'engine': 'libgphoto2'})
        assert created.status_code == 201
        route = f"/v1/session/{created.json()['id']}"
        suffix = '/shutter/half-press' if feature == CameraFeature.SHUTTER_HALF_PRESS else '/focus/auto'
        failed = client.post(route + suffix)
        assert failed.status_code == 502, failed.text
        assert failed.json()['error']['code'] == 'SHUTTER_RELEASE_UNCONFIRMED'
        assert failed.json()['error']['feature'] == feature.value
        status = client.get(route + '/status').json()
        assert status['shutterReleaseUnconfirmed'] is True
        assert status['bulbExposureActive'] is False
        if disconnect:
            closed = client.delete(route)
            assert closed.status_code == 502
            assert client.get(route + '/status').status_code == 404
            replacement = client.post('/v1/session', json={'engine': 'libgphoto2'})
            before = list(runner.writes())
            assert client.post(f"/v1/session/{replacement.json()['id']}/bulb/stop").status_code == 200
            assert runner.writes() == before
        else:
            runner.reject_release = False
            stopped = client.post(route + '/bulb/stop')
            assert stopped.status_code == 200
            assert stopped.json()['shutterReleaseUnconfirmed'] is False
            assert runner.active is False
