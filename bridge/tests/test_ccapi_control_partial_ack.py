"""Real session, urllib and owner regressions; only HTTP/discovery/timer are fake.

No sockets or physical camera. Header normalization and the production command
and release ownership code execute unchanged.
"""
import json
from email.message import Message
from io import BytesIO
from unittest.mock import Mock, patch
from urllib.parse import urlsplit

import pytest

from open_eos_bridge.bulb import BulbRequests
from open_eos_bridge.ccapi import CcapiEngine, CcapiResponse, CcapiSession, UrllibCcapiTransport
from open_eos_bridge.errors import BridgeError
from tests.test_ccapi import FakeCcapiTransport, RecordedRequest

BAD = [
    CcapiResponse(206, {"Content-Length": "2"}, b"{}"),
    CcapiResponse(200, {"Content-Range": "bytes 0-1/4"}, b"{}"),
    CcapiResponse(200, {"cOnTeNt-RaNgE": ""}, b"{}"),
]
GOOD = [
    CcapiResponse(200, {}, b"{}"), CcapiResponse(204, {}, b""),
    CcapiResponse(200, {}, b""), CcapiResponse(200, {"Content-Type": "text/plain"}, b"Accepted"),
    CcapiResponse(202, {}, b"Accepted"),
]
ROUTES = [
    ("POST", "/shooting/control/shutterbutton"),
    ("PUT", "/shooting/control/shutterbutton/manual"),
    ("POST", "/shooting/control/shutterbutton/manual"),
    ("POST", "/shooting/control/af"),
    ("POST", "/shooting/liveview"), ("DELETE", "/shooting/liveview"),
    ("POST", "/shooting/liveview/rtp"), ("DELETE", "/shooting/liveview/multipart"),
]


def wire_response(transport, method, url, response, **kwargs):
    raw = BytesIO(response.body)
    raw.status = response.status
    raw.headers = Message()
    for name, value in response.headers.items():
        raw.headers[name] = value
    opener = Mock()
    opener.open.return_value = raw
    with patch("open_eos_bridge.ccapi.urlopen", return_value=raw), patch(
        "open_eos_bridge.ccapi.build_opener", return_value=opener,
    ):
        result = transport.request(method, url, **kwargs)
    assert raw.closed
    return result


class Peer(FakeCcapiTransport):
    def __init__(self):
        super().__init__()
        self.active = False
        self.stop_response = CcapiResponse(503, {}, b"busy")
        self.apply_stop = False
        self.commands = []
        self.http = UrllibCcapiTransport()

    def request(self, method, url, *, body=None, **kwargs):
        path = urlsplit(url).path
        if path.endswith(("/shooting/control/af", "/shooting/control/shutterbutton/manual")):
            payload = json.loads(body)
            self.requests.append(RecordedRequest(method, path, payload))
            self.commands.append((method, path, payload))
            if payload["action"] in {"start", "full_press"}:
                self.active = True
                response = CcapiResponse(200, {"Content-Length": "2"}, b"{}")
            else:
                assert payload["action"] in {"stop", "release"}
                if self.apply_stop:
                    self.active = False
                response = self.stop_response
            return wire_response(self.http, method, url, response, body=body, **kwargs)
        return super().request(method, url, body=body, **kwargs)


@pytest.mark.parametrize("mode", ["AF", "Bulb"])
@pytest.mark.parametrize("response", BAD + GOOD)
def test_original_owner_retains_stop_until_complete_ack(mode, response):
    peer = Peer()
    session = CcapiEngine(lambda *_: peer, sleeper=lambda _: None).open_connection("http://synthetic.invalid")
    guard = BulbRequests(session)
    start = session.start_autofocus if mode == "AF" else guard.start
    stop = session.stop_autofocus if mode == "AF" else guard.stop
    partial = response in BAD
    with patch("open_eos_bridge.ccapi.threading.Timer", return_value=Mock()):
        start("owner-A")
        original_start, = peer.commands
        with pytest.raises(BridgeError) as first:
            stop("owner-A")
        assert first.value.code == "SHUTTER_RELEASE_UNCONFIRMED"
        original_stop = peer.commands[-1]
        assert peer.active and session._shutter_release_unconfirmed
        peer.stop_response, peer.apply_stop = response, not partial
        if partial:
            with pytest.raises(BridgeError) as rejected:
                stop("owner-A")
            assert rejected.value.code == "SHUTTER_RELEASE_UNCONFIRMED"
            assert peer.active and session._shutter_release_unconfirmed
            if mode == "AF":
                assert session._held_autofocus_owner == "owner-A"
                assert session._short_control_release is not None
            else:
                assert guard._active_owner == "owner-A"
                assert session._bulb_release_operation is not None
            count = len(peer.commands)
            with pytest.raises(BridgeError):
                start("blocked-owner")
            assert len(peer.commands) == count
            peer.stop_response, peer.apply_stop = CcapiResponse(204, {}, b""), True
        stop("owner-A")
        assert not peer.active and not session._shutter_release_unconfirmed
        # Every attempted release preserves the original method, path and payload.
        assert peer.commands == [original_start] + [original_stop] * (3 if partial else 2)
        count = len(peer.commands)
        stop("owner-A")
        assert len(peer.commands) == count, "A completed owner must retain its cached receipt"
        start("owner-B")
        peer.stop_response, peer.apply_stop = CcapiResponse(204, {}, b""), True
        stop("owner-B")
        session.close()


@pytest.mark.parametrize(("method", "suffix"), ROUTES)
@pytest.mark.parametrize("response", BAD)
def test_physical_control_routes_reject_partial_success(method, suffix, response):
    transport = UrllibCcapiTransport()
    session = CcapiSession("http://synthetic.invalid", transport)
    with patch.object(transport, "request", side_effect=lambda method, url, **kwargs:
                      wire_response(UrllibCcapiTransport(), method, url, response, **kwargs)), \
            pytest.raises(BridgeError) as failure:
        session._request_ok(method, "/ccapi/ver120" + suffix, {})
    assert failure.value.code == "INVALID_CCAPI_RESPONSE"


@pytest.mark.parametrize(("method", "suffix"), ROUTES)
@pytest.mark.parametrize("response", GOOD)
def test_control_success_body_compatibility(method, suffix, response):
    transport = Mock()
    transport.request.return_value = response
    CcapiSession("http://synthetic.invalid", transport)._request_ok(method, "/ccapi/ver120" + suffix, {})


@pytest.mark.parametrize(("method", "path"), [
    ("GET", "/ccapi/ver120/shooting/liveview"),
    ("GET", "/ccapi/ver120/contents/card1/IMG_0001.JPG"),
    ("GET", "/ccapi/ver120/devicestatus/battery"),
    ("POST", "/ccapi/ver120/unrelated"),
])
def test_unrelated_and_media_range_responses_keep_existing_policy(method, path):
    transport = Mock()
    response = CcapiResponse(206, {"Content-Range": "bytes 0-1/4"}, b"xx")
    transport.request.return_value = response
    session = CcapiSession("http://synthetic.invalid", transport)
    assert session._request(method, path) == response
    session._request_ok(method, path)


def test_partial_header_on_rejection_keeps_original_http_error():
    transport = Mock()
    transport.request.return_value = CcapiResponse(400, {"Content-Range": ""}, b"busy")
    with pytest.raises(BridgeError) as failure:
        CcapiSession("http://synthetic.invalid", transport)._request_ok("POST", "/ccapi/ver120/shooting/liveview", {})
    assert failure.value.code == "CCAPI_HTTP_ERROR"
    assert failure.value.camera_status == 400


def test_explicit_status_and_maintenance_rules_are_not_weakened():
    transport = Mock()
    transport.request.return_value = CcapiResponse(200, {}, b"Accepted")
    session = CcapiSession("http://synthetic.invalid", transport)
    with pytest.raises(BridgeError):
        session._request_ok("DELETE", "/ccapi/ver120/shooting/liveview", expected_status=204)
    session._maintenance_control_active = True
    transport.request.return_value = CcapiResponse(204, {}, b"not empty")
    with pytest.raises(BridgeError):
        session._request_ok("DELETE", "/ccapi/ver120/shooting/liveview")
