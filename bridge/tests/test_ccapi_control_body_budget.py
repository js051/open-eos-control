"""Cooperative control body budgets, using production urllib and loopback sockets.

The short fixture budget is not proof of bounded DNS, headers, or one blocking read.
"""
from __future__ import annotations

import json
import threading
import time
from contextlib import suppress
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
from unittest.mock import patch

import pytest

from open_eos_bridge.ccapi import CcapiOperation, CcapiSession, CcapiStreamResponse, UrllibCcapiTransport
from open_eos_bridge.errors import BridgeError

PREFIX = "/ccapi/ver100"
AF = PREFIX + "/shooting/control/af"
MANUAL = PREFIX + "/shooting/control/shutterbutton/manual"
LIVE_VIEW = PREFIX + "/shooting/liveview"
CONFIGURATION = PREFIX + "/functions/directory/createdirectory"
BUDGET = 0.1


class LoopbackPeer:
    def __init__(self, *, status=200, slow=True, redirect=False, slow_actions=None):
        self.status = status
        self.slow = slow
        self.redirect = redirect
        self.slow_actions = slow_actions
        self.requests = []
        self.first_byte = threading.Event()
        self.closed = threading.Event()
        peer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def respond(self):
                data = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                payload = json.loads(data) if data else None
                peer.requests.append((self.command, self.path, payload))
                if peer.redirect and self.path != "/target":
                    self.send_response(302)
                    self.send_header("Location", "/target")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                slow = peer.slow and (
                    peer.slow_actions is None or (payload or {}).get("action") in peer.slow_actions
                )
                body = b" " * 40 if slow else b"diagnostic"
                self.send_response(peer.status)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                with suppress(BrokenPipeError, ConnectionResetError):
                    if slow:
                        for value in body:
                            self.wfile.write(bytes([value]))
                            self.wfile.flush()
                            peer.first_byte.set()
                            if peer.closed.wait(0.015):
                                break
                    else:
                        self.wfile.write(body)

            do_GET = do_POST = do_PUT = do_DELETE = respond

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(
            target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True,
        )
        self.thread.start()
        self.origin = f"http://127.0.0.1:{self.server.server_port}"

    def close(self):
        self.closed.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        assert not self.thread.is_alive()


@pytest.fixture
def peer_factory():
    peers = []

    def create(**kwargs):
        peer = LoopbackPeer(**kwargs)
        peers.append(peer)
        return peer

    yield create
    for peer in peers:
        peer.close()


@pytest.mark.parametrize(("method", "path"), [("POST", AF), ("PUT", MANUAL), ("DELETE", LIVE_VIEW)])
def test_control_slow_drip_rejects_late_success_body(peer_factory, method, path):
    peer = peer_factory()
    started = time.monotonic()
    with pytest.raises(BridgeError) as failure:
        UrllibCcapiTransport().request(method, peer.origin + path, body=b"{}", timeout=BUDGET)
    assert failure.value.code == "CCAPI_UNREACHABLE"
    assert time.monotonic() - started < 0.4
    assert len(peer.requests) == 1


def test_control_slow_rejection_body_preserves_received_status(peer_factory):
    peer = peer_factory(status=400)
    started = time.monotonic()
    response = UrllibCcapiTransport().request("POST", peer.origin + AF, body=b"{}", timeout=BUDGET)
    assert response.status == 400
    assert response.body == b""
    assert time.monotonic() - started < 0.4
    assert len(peer.requests) == 1


@pytest.mark.parametrize("status", [200, 400])
def test_existing_configuration_body_budget_is_unchanged(peer_factory, status):
    peer = peer_factory(status=status)
    started = time.monotonic()
    transport = UrllibCcapiTransport()
    if status == 200:
        with pytest.raises(BridgeError) as failure:
            transport.request("POST", peer.origin + CONFIGURATION, body=b"{}", timeout=BUDGET)
        assert failure.value.code == "CCAPI_UNREACHABLE"
    else:
        response = transport.request("POST", peer.origin + CONFIGURATION, body=b"{}", timeout=BUDGET)
        assert response.status == 400 and response.body == b""
    assert time.monotonic() - started < 0.4


@pytest.mark.parametrize("path", [LIVE_VIEW])
def test_unrelated_get_retains_legacy_body_read(peer_factory, path):
    peer = peer_factory()
    started = time.monotonic()
    response = UrllibCcapiTransport().request("GET", peer.origin + path, timeout=BUDGET)
    assert response.status == 200 and response.body == b" " * 40
    assert time.monotonic() - started > 0.4


@pytest.mark.parametrize(("method", "path", "followed"), [
    ("POST", AF, True), ("DELETE", LIVE_VIEW, False), ("POST", CONFIGURATION, False),
])
def test_existing_redirect_selection_is_unchanged(peer_factory, method, path, followed):
    peer = peer_factory(slow=False, redirect=True)
    response = UrllibCcapiTransport().request(method, peer.origin + path, body=b"{}", timeout=BUDGET)
    assert response.status == (200 if followed else 302)
    assert [request[:2] for request in peer.requests] == (
        [(method, path), ("GET", "/target")] if followed else [(method, path)]
    )


def test_prompt_rejection_keeps_optional_diagnostic_body(peer_factory):
    peer = peer_factory(status=400, slow=False)
    response = UrllibCcapiTransport().request("POST", peer.origin + LIVE_VIEW, body=b"{}", timeout=BUDGET)
    assert response.status == 400 and response.body == b"diagnostic"


@pytest.mark.parametrize(("method", "suffix"), [
    ("POST", "/shooting/control/shutterbutton"),
    ("PUT", "/shooting/control/shutterbutton/manual"),
    ("POST", "/shooting/control/shutterbutton/manual"),
    ("POST", "/shooting/control/af"),
    ("POST", "/shooting/liveview"),
    ("DELETE", "/shooting/liveview"),
    ("POST", "/shooting/liveview/rtp"),
    ("DELETE", "/shooting/liveview/multipart"),
])
def test_exact_control_routes_reject_late_single_chunk_and_close(method, suffix):
    body = BytesIO(b"ok")
    response = CcapiStreamResponse(200, {}, body)
    transport = UrllibCcapiTransport()
    with patch.object(transport, "_open", return_value=response), patch(
        "open_eos_bridge.ccapi.time.monotonic", side_effect=[0, 0.01, 0.2],
    ), pytest.raises(BridgeError) as failure:
        transport.request(method, "http://192.0.2.1" + PREFIX + suffix, timeout=BUDGET)
    assert failure.value.code == "CCAPI_UNREACHABLE"
    assert body.closed


class ShortBudgetTransport(UrllibCcapiTransport):
    def __init__(self):
        super().__init__()
        self.order = []

    def request(self, method, url, **kwargs):
        action = json.loads(kwargs["body"])["action"]
        self.order.append((action, "enter"))
        kwargs["timeout"] = BUDGET
        try:
            return super().request(method, url, **kwargs)
        finally:
            self.order.append((action, "exit"))


def held_session(peer):
    transport = ShortBudgetTransport()
    session = CcapiSession(peer.origin, transport)
    session._initialized = True
    session._operations = {CcapiOperation("POST", AF)}
    return session, transport


def test_original_stop_waits_for_producer_then_releases_after_body_budget(peer_factory):
    peer = peer_factory(slow_actions={"start"})
    session, transport = held_session(peer)
    start_done, stop_done = threading.Event(), threading.Event()
    outcomes = {}

    def call(kind, done):
        try:
            outcomes[kind] = (session.start_autofocus if kind == "start" else session.stop_autofocus)("owner")
        except Exception as error:
            outcomes[kind] = error
        finally:
            done.set()

    start = threading.Thread(target=call, args=("start", start_done), daemon=True)
    stop = threading.Thread(target=call, args=("stop", stop_done), daemon=True)
    try:
        start.start()
        assert peer.first_byte.wait(1)
        stop.start()
        assert not stop_done.wait(0.025), "Stop must not detach the physical Start producer"
        assert [payload["action"] for _, _, payload in peer.requests] == ["start"]
        assert stop_done.wait(0.35), "Slow-drip body retained the provider lock beyond its budget"
        assert start_done.wait(0.1)
        assert isinstance(outcomes["start"], BridgeError)
        assert outcomes["start"].code == "CCAPI_UNREACHABLE"
        assert outcomes["stop"].active is False
        assert transport.order == [("start", "enter"), ("start", "exit"), ("stop", "enter"), ("stop", "exit")]
        assert session._held_autofocus_owner is None
    finally:
        start.join(2)
        stop.join(2)
        if session._held_autofocus_timer is not None:
            session._held_autofocus_timer.cancel()


def test_late_stop_keeps_exact_owner_and_explicit_retry_never_represses(peer_factory):
    peer = peer_factory(slow_actions={"stop"})
    session, _transport = held_session(peer)
    session.start_autofocus("owner")
    original_release = session._short_control_release
    try:
        with pytest.raises(BridgeError) as failure:
            session.stop_autofocus("owner")
        assert failure.value.code == "SHUTTER_RELEASE_UNCONFIRMED"
        assert session._held_autofocus_owner == "owner"
        assert session._short_control_release is original_release
        assert session._shutter_release_unconfirmed is True
        with pytest.raises(BridgeError):
            session.start_autofocus("new-owner")
        peer.slow = False
        assert session.stop_autofocus("owner").active is False
        assert [payload["action"] for _, _, payload in peer.requests] == ["start", "stop", "stop"]
        assert session._short_control_release is None
    finally:
        if session._held_autofocus_timer is not None:
            session._held_autofocus_timer.cancel()


@pytest.mark.parametrize(("method", "path"), [
    ("GET", AF), ("DELETE", AF), ("PUT", LIVE_VIEW),
    ("GET", LIVE_VIEW + "/rtpsessiondesc"), ("GET", LIVE_VIEW + "/multipart"),
    ("POST", LIVE_VIEW + "/unknown"), ("POST", PREFIX + "/shooting/control/recbutton"),
    ("PUT", PREFIX + "/shooting/control/drivefocus"),
])
def test_nonlisted_method_or_suffix_does_not_gain_a_body_budget(method, path):
    body = BytesIO(b"ok")
    transport = UrllibCcapiTransport()
    with patch.object(transport, "_open", return_value=CcapiStreamResponse(200, {}, body)), patch(
        "open_eos_bridge.ccapi.time.monotonic",
    ) as clock:
        assert transport.request(method, "http://192.0.2.1" + path).body == b"ok"
    clock.assert_not_called()
    assert body.closed


@pytest.mark.parametrize(("body_bytes", "headers", "limit", "error"), [
    (b"1234", {"content-length": "4"}, 4, None),
    (b"12345", {}, 4, "CCAPI_RESPONSE_TOO_LARGE"),
    (b"12", {"content-length": "4"}, 4, "CCAPI_UNREACHABLE"),
])
def test_control_size_and_declared_length_checks_are_preserved(body_bytes, headers, limit, error):
    body = BytesIO(body_bytes)
    transport = UrllibCcapiTransport()
    with patch.object(transport, "_open", return_value=CcapiStreamResponse(200, headers, body)):
        if error is None:
            assert transport.request("POST", "http://192.0.2.1" + AF, max_bytes=limit).body == body_bytes
        else:
            with pytest.raises(BridgeError) as failure:
                transport.request("POST", "http://192.0.2.1" + AF, max_bytes=limit)
            assert failure.value.code == error
    assert body.closed


def test_rejection_read_error_preserves_status_and_closes():
    class BrokenBody(BytesIO):
        def read1(self, _size):
            raise OSError("synthetic diagnostic body failure")

    body = BrokenBody()
    transport = UrllibCcapiTransport()
    with patch.object(transport, "_open", return_value=CcapiStreamResponse(503, {}, body)):
        response = transport.request("POST", "http://192.0.2.1" + AF)
    assert response.status == 503 and response.body == b""
    assert body.closed
