from __future__ import annotations

from unittest.mock import patch
from urllib.parse import urlsplit

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine, CcapiResponse
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import CommandOutput, GPhoto2Engine
from open_eos_bridge.models import CameraSetting
from open_eos_bridge.shooting_settings import exact_setting, responded

from .fakes import FakeRunner
from .test_ccapi import FakeCcapiTransport


class ScopedTransport(FakeCcapiTransport):
    failure = None
    writes = 0

    def request(self, method, url, **options):
        path = urlsplit(url).path
        if method == 'GET' and path.endswith('/shooting/settings') and self.writes:
            if self.failure == 'read':
                raise BridgeError('SYNTHETIC_READ_FAILURE', 'Synthetic read failed.')
            if self.failure == 'mismatch':
                self.settings['iso']['value'] = '800'
            if self.failure == 'duplicate':
                return CcapiResponse(200, {}, b'{"iso":{"value":"100","value":"800","ability":["100","800"]}}')
        result = super().request(method, url, **options)
        if method == 'PUT' and '/shooting/settings/' in path:
            self.writes += 1
            if self.failure == 'lost':
                raise BridgeError('SYNTHETIC_LOST_RESPONSE', 'Synthetic response lost.')
            if self.failure == 'partial':
                return CcapiResponse(206, {'content-range': 'bytes 0-1/2'}, b'{}')
            if self.failure == 'framing':
                return CcapiResponse(200, {'content-length': '3'}, b'{}')
        return result


def ccapi():
    transport = ScopedTransport()
    session = CcapiEngine(lambda _u, _p: transport).open_connection('http://192.0.2.55:8080')
    session.capabilities()
    transport.requests.clear()
    return session, transport


@pytest.mark.parametrize('failure,read', [(None, 'matched'), ('read', 'unavailable'), ('mismatch', 'mismatch'),
                                        ('duplicate', 'unavailable')])
def test_ccapi_response_survives_followup_and_only_one_frozen_write(failure, read):
    session, transport = ccapi()
    transport.failure = failure
    with patch.object(session, 'status', side_effect=AssertionError('No broad status read')):
        result = session.apply_setting_outcome('iso', '100')
    assert result['writeEvidence'] == 'response-received'
    assert result['readbackEvidence'] == read
    assert transport.writes == 1
    assert {item.path for item in transport.requests} <= {
        '/ccapi/ver100/shooting/settings', '/ccapi/ver100/shooting/settings/iso'}
    if read == 'unavailable':
        assert result['setting'] is None


@pytest.mark.parametrize('failure', ['lost', 'partial', 'framing'])
def test_ccapi_unknown_never_replays_alias_or_fabricates_response(failure):
    session, transport = ccapi()
    transport.failure = failure
    with pytest.raises(BridgeError):
        session.apply_setting_outcome('iso', '100')
    assert transport.writes == 1
    transport.failure = None
    read = session.read_setting('iso')
    assert read['setting']['value'] == '100'
    assert 'writeEvidence' not in read
    assert transport.writes == 1


def test_ccapi_structured_write_preserves_fresh_external_sibling():
    session, transport = ccapi()
    transport.settings['stillimagequality']['value']['jpeg'] = 'large_normal'
    result = session.apply_setting_outcome('stillimagequality.raw', 'raw')
    assert result['readbackEvidence'] == 'matched'
    assert transport.settings['stillimagequality']['value'] == {'raw': 'raw', 'jpeg': 'large_normal'}
    assert transport.writes == 1


@pytest.mark.parametrize('bad', ['duplicate', 'unknown-current', 'invalid-type'])
def test_ccapi_fresh_preflight_ability_rejects_without_write(bad):
    session, transport = ccapi()
    if bad == 'duplicate':
        transport.settings['iso']['ability'] = ['100', '100', '800']
    elif bad == 'unknown-current':
        transport.settings['iso']['value'] = '404'
    else:
        transport.settings['iso']['value'] = 800
    with pytest.raises(BridgeError):
        session.apply_setting_outcome('iso', '100')
    assert transport.writes == 0


def test_ccapi_read_rejects_late_observation_and_does_not_write():
    session, transport = ccapi()
    with patch('open_eos_bridge.ccapi.time.monotonic', side_effect=[0, 0, 0, 16]), pytest.raises(BridgeError):
        session.read_setting('iso')
    assert transport.writes == 0


class ScopedRunner(FakeRunner):
    read_failure = False
    lost_write = False
    writes = 0
    mismatch = False
    malformed = False

    def run(self, arguments, *, timeout=30.0):
        command = self._without_camera(arguments)
        if command[:1] == ['--get-config']:
            self.commands.append(tuple(arguments))
            assert 0 < timeout <= 15
            if self.read_failure and self.writes:
                raise BridgeError('SYNTHETIC_READ_FAILURE', 'Synthetic scoped read failed.')
            if self.malformed:
                return CommandOutput(b'Label: ISO\nReadonly: 0\nType: RADIO\nCurrent: 400\nCurrent: 800\nEND\n')
            if self.mismatch and self.writes:
                self.values[command[1]] = '400'
            blocks = self._config_dump().split('\n/main/')
            for block in blocks:
                block = '/main/' + block if not block.startswith('/main/') else block
                if block.splitlines()[0] == command[1]:
                    return CommandOutput(('\n'.join(block.splitlines()[1:])+'\n').encode())
            raise AssertionError('Synthetic path missing')
        result = super().run(arguments, timeout=timeout)
        if command[:1] == ['--set-config-value']:
            self.writes += 1
            if self.lost_write:
                raise BridgeError('SYNTHETIC_LOST_RESPONSE', 'Synthetic write response lost.')
        return result


def gphoto():
    runner = ScopedRunner()
    session = GPhoto2Engine(runner).open()
    session.capabilities()
    runner.commands.clear()
    return session, runner


@pytest.mark.parametrize('mode,read', [('normal', 'matched'), ('fail', 'unavailable'), ('mismatch', 'mismatch')])
def test_gphoto_one_exact_write_with_response_read_split(mode, read):
    session, runner = gphoto()
    runner.read_failure = mode == 'fail'
    runner.mismatch = mode == 'mismatch'
    result = session.apply_setting_outcome('iso', '800')
    assert result['writeEvidence'] == 'response-received'
    assert result['readbackEvidence'] == read
    assert runner.writes == 1
    commands = [runner._without_camera(list(command)) for command in runner.commands]
    assert [command[0] for command in commands] == ['--get-config', '--set-config-value', '--get-config']
    if mode == 'fail':
        assert session._configs['/main/imgsettings/iso'].current == '400'


def test_gphoto_unknown_write_can_only_be_reconciled_by_read():
    session, runner = gphoto()
    runner.lost_write = True
    with pytest.raises(BridgeError):
        session.apply_setting_outcome('iso', '800')
    assert runner.writes == 1
    assert session._configs['/main/imgsettings/iso'].current == '400'
    result = session.read_setting('iso')
    assert result['setting']['value'] == '800' and 'writeEvidence' not in result
    assert runner.writes == 1


def test_gphoto_duplicate_scoped_fields_fail_before_write():
    session, runner = gphoto()
    runner.malformed = True
    with pytest.raises(BridgeError):
        session.apply_setting_outcome('iso', '800')
    assert runner.writes == 0


def test_gphoto_card_and_text_keep_existing_safe_ability_contract():
    session, runner = gphoto()
    card = session.apply_setting_outcome('capturestorage', 'SD')
    assert card['readbackEvidence'] == 'matched'
    assert runner.values['/main/capturesettings/storageid'] == '00020001'
    with pytest.raises(BridgeError):
        session.apply_setting_outcome('capturestorage', 'Card 99')
    assert runner.writes == 1


@pytest.mark.parametrize('value', ['', '\n', 'x'*513])
def test_exact_setting_never_normalizes_invalid_choice(value):
    with pytest.raises(ValueError):
        exact_setting(CameraSetting(key='iso', label='ISO', value=value, values=[value]), 'iso')


def test_completed_response_is_independent_of_parse_error():
    result = responded('ccapi', 'iso', '100', lambda: (_ for _ in ()).throw(ValueError('Synthetic malformed read')))
    assert result == {'version': 1, 'engine': 'ccapi', 'key': 'iso', 'requestedValue': '100',
                      'writeEvidence': 'response-received', 'readbackEvidence': 'unavailable', 'setting': None}


def test_additive_api_is_fresh_no_store_and_does_not_fallback():
    runner = ScopedRunner()
    client = TestClient(create_app(engine=GPhoto2Engine(runner)))
    opened = client.post('/v1/session', json={}).json()
    session_id = opened['id']
    response = client.post(f'/v1/session/{session_id}/settings/iso/outcome', json={'value': '800'})
    assert response.status_code == 200, response.text
    assert response.headers['cache-control'] == 'no-store'
    assert response.json()['readbackEvidence'] == 'matched'
    read = client.get(f'/v1/session/{session_id}/settings/iso')
    assert read.status_code == 200 and read.json()['setting']['value'] == '800'
    assert read.headers['cache-control'] == 'no-store'
    assert runner.writes == 1


@pytest.mark.parametrize('malformed', ['wb-outside-ability', 'wb-bool-sibling', 'quality-long-sibling',
                                     'quality-empty-sibling', 'quality-duplicate'])
def test_entire_fresh_structured_payload_must_be_valid_before_any_write(malformed):
    session, transport = ccapi()
    key, value = 'wbshift.ba', '1'
    if malformed == 'wb-outside-ability':
        transport.settings['wbshift']['value']['mg'] = 100
    elif malformed == 'wb-bool-sibling':
        transport.settings['wbshift']['value']['mg'] = True
    else:
        key, value = 'stillimagequality.raw', 'raw'
        raw = transport.settings['stillimagequality']
        if malformed == 'quality-duplicate':
            raw['ability']['jpeg'] = ['large_fine', 'large_fine']
        else:
            sibling = 'x' * 513 if malformed == 'quality-long-sibling' else ''
            raw['value']['jpeg'] = sibling
            raw['ability']['jpeg'] = [sibling, 'none']
    with pytest.raises(BridgeError):
        session.apply_setting_outcome(key, value)
    assert transport.writes == 0


@pytest.mark.parametrize('headers', [
    [('Content-Length', '2'), ('Content-Length', '3')],
    [('Transfer-Encoding', 'gzip')],
    [('Transfer-Encoding', 'chunked'), ('Transfer-Encoding', 'chunked')],
    [('Content-Encoding', 'gzip')],
])
def test_real_transport_rejects_ambiguous_headers_before_flattening(headers):
    from email.message import Message
    from io import BytesIO
    from types import SimpleNamespace

    from open_eos_bridge.ccapi import UrllibCcapiTransport

    body = BytesIO(b'{}')
    message = Message()
    for key, value in headers:
        message.add_header(key, value)
    response = SimpleNamespace(status=200, headers=message, close=body.close)
    opener = SimpleNamespace(open=lambda *_args, **_kwargs: response)
    with patch('open_eos_bridge.ccapi.build_opener', return_value=opener), pytest.raises(BridgeError):
        UrllibCcapiTransport().request('PUT', 'http://192.0.2.1/ccapi/ver100/shooting/settings/iso')
    assert body.closed


def test_provider_without_scoped_contract_fails_before_legacy_dispatch():
    runner = ScopedRunner()
    app = create_app(engine=GPhoto2Engine(runner))
    client = TestClient(app)
    session_id = client.post('/v1/session', json={}).json()['id']
    session = app.state.session_manager.get(session_id)
    with patch.object(session, 'apply_setting_outcome', None), patch.object(session, 'set_setting') as legacy:
        result = client.post(f'/v1/session/{session_id}/settings/iso/outcome', json={'value': '800'})
        assert result.status_code == 409
        assert result.json()['error']['code'] == 'UNSUPPORTED_SETTING_OUTCOME'
        legacy.assert_not_called()
    assert runner.writes == 0
