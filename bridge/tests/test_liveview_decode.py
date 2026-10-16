from io import BytesIO

import pytest

from open_eos_bridge import liveview_decode
from open_eos_bridge.ccapi import CcapiMultipartSession, CcapiStreamResponse
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.models import LiveViewStartRequest

from . import test_ccapi_live_view_recovery as recovery
from .liveview_jpeg_fixture import JPEG


def test_actual_isolated_first_frame_decode():
    liveview_decode.validate_first_jpeg(JPEG)


@pytest.mark.parametrize('data', [b'\xff\xd8\xff\xd9', b'\xff\xd8not-pixels\xff\xd9', JPEG[:50] + b'\xff\xd9'])
def test_jpeg_envelope_is_not_decoder_evidence(data):
    with pytest.raises(BridgeError, match='decoded'):
        liveview_decode.validate_first_jpeg(data)


def test_decode_deadline_releases_slot_without_success(monkeypatch):
    monkeypatch.setattr(liveview_decode, 'DECODE_TIMEOUT_SECONDS', 0.00001)
    with pytest.raises(BridgeError):
        liveview_decode.validate_first_jpeg(JPEG)
    assert liveview_decode._SLOT.acquire(blocking=False)
    liveview_decode._SLOT.release()


def test_busy_decoder_does_not_take_or_release_another_probe_slot():
    assert liveview_decode._SLOT.acquire(blocking=False)
    try:
        with pytest.raises(BridgeError):
            liveview_decode.validate_first_jpeg(JPEG)
        assert not liveview_decode._SLOT.acquire(blocking=False)
    finally:
        liveview_decode._SLOT.release()


@pytest.mark.parametrize('terminal', [False, True])
def test_invalid_or_terminal_multipart_falls_back_only_after_original_stop(terminal):
    harness = recovery.LiveViewStopRecoveryTests()
    session, transport, receiver = harness.create_session(sources=recovery.SOURCES[:2])
    transport.stream_terminal = terminal
    transport.stream_frame = JPEG if terminal else b'\xff\xd8\xff\xd9'
    try:
        session.start_live_view(LiveViewStartRequest(source='AUTO'))
        assert session.live_view_source == 'CCAPI_JPEG_POLLING'
        commands = [(method, path) for method, path, _ in transport.commands]
        assert commands == [('POST', recovery.PREFIX), ('DELETE', recovery.PREFIX + '/multipart'),
                            ('DELETE', recovery.PREFIX), ('POST', recovery.PREFIX)]
        assert all(body.closed for body in transport.stream_bodies)
    finally:
        harness.close_session(session, transport, receiver)


def test_decoder_failure_and_uncertain_cleanup_never_fallback_or_repeat_stop():
    harness = recovery.LiveViewStopRecoveryTests()
    session, transport, receiver = harness.create_session(sources=recovery.SOURCES[:2])
    transport.stream_frame = b'\xff\xd8\xff\xd9'
    transport.failed_stops.add(recovery.PREFIX)
    try:
        with pytest.raises(BridgeError) as raised:
            session.start_live_view(LiveViewStartRequest(source='AUTO'))
        assert raised.value.code == 'LIVE_VIEW_STOP_UNCONFIRMED'
        assert [(method, path) for method, path, _ in transport.commands] == [
            ('POST', recovery.PREFIX), ('DELETE', recovery.PREFIX + '/multipart'), ('DELETE', recovery.PREFIX)]
    finally:
        harness.close_session(session, transport, receiver)


def test_known_terminal_multipart_wins_over_cached_valid_frame():
    body = (b'--b\r\nContent-Type: image/jpeg\r\nContent-Length: ' + str(len(JPEG)).encode()
            + b'\r\n\r\n' + JPEG + b'\r\n--b--\r\n')
    session = CcapiMultipartSession(CcapiStreamResponse(200, {}, BytesIO(body)), 'b')
    session._worker.join(1)
    assert not session._worker.is_alive()
    for read in [session.wait_until_ready, session.next_frame, session.check_usable]:
        with pytest.raises(BridgeError):
            read()
    session.close()


@pytest.mark.parametrize('status,headers', [(206, {}), (200, {'content-range': 'bytes 0-9/20'})])
def test_partial_jpeg_http_response_cannot_admit_source(status, headers):
    harness = recovery.LiveViewStopRecoveryTests()
    session, transport, receiver = harness.create_session(sources=['CCAPI_JPEG_POLLING'])
    original = transport.request

    def request(method, url, **kwargs):
        result = original(method, url, **kwargs)
        if '/flip?' in url:
            return recovery.CcapiResponse(status, {'content-type': 'image/jpeg', **headers}, JPEG)
        return result

    transport.request = request
    try:
        with pytest.raises(BridgeError) as raised:
            session.start_live_view(LiveViewStartRequest(source='CCAPI_JPEG_POLLING'))
        assert raised.value.code == 'INVALID_LIVE_VIEW_FRAME'
        assert [(method, path) for method, path, _ in transport.commands] == [
            ('POST', recovery.PREFIX), ('DELETE', recovery.PREFIX)]
    finally:
        harness.close_session(session, transport, receiver)


def test_multipart_terminating_during_real_decode_cannot_be_published(monkeypatch):
    from open_eos_bridge import ccapi

    harness = recovery.LiveViewStopRecoveryTests()
    session, transport, receiver = harness.create_session(sources=recovery.SOURCES[:2])
    actual_decode = ccapi.validate_first_jpeg
    calls = []

    def decode_then_terminate(frame):
        actual_decode(frame)
        calls.append(frame)
        if len(calls) == 1:
            stream = session._multipart_session
            stream.close()
            stream._worker.join(1)

    monkeypatch.setattr(ccapi, 'validate_first_jpeg', decode_then_terminate)
    try:
        session.start_live_view(LiveViewStartRequest(source='AUTO'))
        assert len(calls) == 2
        assert session.live_view_source == 'CCAPI_JPEG_POLLING'
    finally:
        harness.close_session(session, transport, receiver)
