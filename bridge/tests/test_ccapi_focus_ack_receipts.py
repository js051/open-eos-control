"""Production API/session/urllib focus receipts; only discovery and wire bytes are synthetic."""

import json
import threading
import time
from contextlib import contextmanager, suppress
from email.message import Message
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
from unittest.mock import Mock, patch
from urllib.parse import urlsplit

import pytest
from fastapi.testclient import TestClient

from open_eos_bridge import ccapi as ccapi_module
from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import (
    MAX_JSON_BYTES,
    CcapiEngine,
    CcapiResponse,
    CcapiStreamResponse,
    UrllibCcapiTransport,
)
from open_eos_bridge.errors import BridgeError
from tests.test_ccapi import FakeCcapiTransport, RecordedRequest

ROUTES = [
    ("drive", "/focus/drive", "/shooting/control/drivefocus", {"direction": "NEAR", "step": "MEDIUM"}, "FOCUS_DRIVE"),
    ("tap", "/focus/tap", "/shooting/liveview/afframeposition", {"x": 0.4, "y": 0.6}, "TAP_FOCUS"),
    ("white_balance", "/whitebalance/click", "/shooting/liveview/clickwb", {"x": 0.4, "y": 0.6}, "CLICK_WHITE_BALANCE"),
]
VARIANTS = [
    ("partial_206", CcapiResponse(206, {"content-length": "2"}, b"{}"), False),
    ("ranged_200", CcapiResponse(200, {"content-length": "2", "content-range": "bytes 0-1/4"}, b"{}"), False),
    ("empty_range_200", CcapiResponse(200, {"content-length": "2", "content-range": ""}, b"{}"), False),
    ("valid_json_200", CcapiResponse(200, {"content-length": "2"}, b"{}"), True),
    ("valid_empty_204", CcapiResponse(204, {"content-length": "0"}, b""), True),
    ("valid_plain_200", CcapiResponse(200, {"content-length": "8", "content-type": "text/plain"}, b"Accepted"), True),
    ("rejected_503", CcapiResponse(503, {"content-length": "4"}, b"busy"), False),
]


class Peer(FakeCcapiTransport):
    def __init__(self, suffix, response, apply):
        super().__init__(reject_live_view_size=False)
        self.suffix, self.response, self.apply = suffix, response, apply
        self.commands, self.applied = [], []
        self.http = UrllibCcapiTransport()

    def request(self, method, url, *, body=None, **kwargs):
        path = urlsplit(url).path
        if path.endswith(self.suffix) and method in {"PUT", "POST"}:
            payload = json.loads(body)
            self.requests.append(RecordedRequest(method, path, payload))
            self.commands.append([method, path, payload])
            if self.apply:
                self.applied.append([method, path, payload])
            raw = BytesIO(self.response.body)
            raw.status = self.response.status
            raw.headers = Message()
            for name, value in self.response.headers.items():
                raw.headers[name] = value
            opener = Mock()
            opener.open.return_value = raw
            with (
                patch("open_eos_bridge.ccapi.urlopen", return_value=raw),
                patch("open_eos_bridge.ccapi.build_opener", return_value=opener),
            ):
                return self.http.request(method, url, body=body, **kwargs)
        return super().request(method, url, body=body, **kwargs)


def run(route, variant):
    label, route_path, suffix, payload, feature = route
    name, wire_response, apply = variant
    peer = Peer(suffix, wire_response, apply)
    app = create_app(ccapi_engine=CcapiEngine(lambda *_: peer, sleeper=lambda _: None))
    with TestClient(app) as client:
        created = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": "http://synthetic.invalid:8080"})
        assert created.status_code == 201, created.text
        base = "/v1/session/" + created.json()["id"]
        start = client.post(
            base + "/liveview/start", json={"fps": 15, "size": "MEDIUM", "source": "CCAPI_JPEG_POLLING"}
        )
        assert start.status_code == 200, start.text
        frame = client.get(base + "/liveview/frame")
        assert frame.status_code == 200, frame.text
        response = client.post(base + route_path, json=payload)
        assert len(peer.commands) == 1, peer.commands
        caps = client.get(base + "/capabilities").json()
        status = client.get(base + "/status")
        assert status.status_code == 200
        assert len(peer.commands) == 1, "Read-only follow-up must not replay mutation"
        result = {
            "control": label,
            "variant": name,
            "wire_status": wire_response.status,
            "range_present": "content-range" in wire_response.headers,
            "api_status": response.status_code,
            "api_result": response.json(),
            "feature_observed": feature in caps["evidence"]["observedFeatures"],
            "synthetic_mutation_applied": bool(peer.applied),
            "mutation_calls_after_status_and_capabilities": len(peer.commands),
            "commands": peer.commands,
        }
        client.delete(base)
        return result


@pytest.mark.parametrize("route", ROUTES, ids=lambda item: item[0])
@pytest.mark.parametrize("variant", VARIANTS, ids=lambda item: item[0])
def test_original_focus_receipt_controls_success_and_observed_evidence(route, variant):
    result = run(route, variant)
    accepted = variant[2]
    assert result["api_status"] == (200 if accepted else 502), result
    assert result["feature_observed"] is accepted, result
    assert result["mutation_calls_after_status_and_capabilities"] == 1
    assert result["synthetic_mutation_applied"] is accepted
    method, path, payload = result["commands"][0]
    assert method == ("PUT" if route[0] == "tap" else "POST")
    assert path.endswith(route[2])
    if route[0] == "drive":
        assert payload == {"value": "near2"}
    else:
        assert set(payload) == {"positionx", "positiony"}
    if accepted and route[0] != "white_balance":
        assert result["api_result"]["accepted"] is True
    elif not accepted:
        expected = "CCAPI_HTTP_ERROR" if variant[1].status == 503 else "INVALID_CCAPI_RESPONSE"
        assert result["api_result"]["error"]["code"] == expected


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("GET", "/shooting/control/drivefocus"),
        ("PUT", "/shooting/control/drivefocus"),
        ("POST", "/shooting/liveview/afframeposition"),
        ("PUT", "/shooting/liveview/clickwb"),
        ("POST", "/shooting/liveview/clickwb/other"),
        ("GET", "/contents/card1/IMAGE.JPG"),
        ("POST", "/focus/drive"),
        ("POST", "/whitebalance/click"),
    ],
)
def test_unrelated_methods_paths_and_simulator_routes_keep_range_policy(method, path):
    from open_eos_bridge.ccapi import CcapiSession

    peer = Mock()
    peer.request.return_value = CcapiResponse(206, {"Content-Range": "bytes 0-1/4"}, b"{}")
    CcapiSession("http://synthetic.invalid", peer)._request_ok(method, "/ccapi/ver120" + path)
    assert peer.request.call_count == 1


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("POST", "/shooting/control/drivefocus"),
        ("PUT", "/shooting/liveview/afframeposition"),
        ("POST", "/shooting/liveview/clickwb"),
    ],
)
@pytest.mark.parametrize(("status", "body"), [(200, b""), (202, b"Accepted")])
def test_other_legacy_full_success_forms_remain_valid(method, path, status, body):
    from open_eos_bridge.ccapi import CcapiSession

    peer = Mock()
    peer.request.return_value = CcapiResponse(status, {}, body)
    CcapiSession("http://synthetic.invalid", peer)._request_ok(method, "/ccapi/ver120" + path)
    assert peer.request.call_count == 1


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("POST", "/shooting/control/drivefocus"),
        ("PUT", "/shooting/liveview/afframeposition"),
        ("POST", "/shooting/liveview/clickwb"),
    ],
)
def test_focus_receipt_keeps_full_body_redirect_and_close_with_absolute_deadline(method, path):
    from open_eos_bridge.ccapi import CcapiStreamResponse

    raw = BytesIO(b"Accepted")
    peer = UrllibCcapiTransport()
    with (
        patch.object(peer, "_open", return_value=CcapiStreamResponse(200, {}, raw)) as opened,
        patch(
            "open_eos_bridge.ccapi.time.monotonic",
            side_effect=[7.0, 7.01, 7.02, 7.03, 7.04],
        ) as clock,
        patch(
            "open_eos_bridge.ccapi._read_ccapi_body_with_deadline",
            wraps=ccapi_module._read_ccapi_body_with_deadline,
        ) as read_body,
    ):
        assert peer.request(method, "http://synthetic.invalid/ccapi/ver120" + path).body == b"Accepted"
    # Intentional policy extension of the former three no-clock cases. Keep
    # their complete-body, redirect and closure assertions; defaults do not change.
    assert clock.call_count == 5
    read_body.assert_called_once_with(raw, MAX_JSON_BYTES + 1, 22.0)
    assert opened.call_args.kwargs["timeout"] == 15.0
    assert opened.call_args.kwargs["follow_redirects"] is True
    assert opened.call_args.kwargs["strict_control"] is False
    assert raw.closed


# Short fixture budgets exercise the existing cooperative reader, not a promise
# that DNS, open, headers, a single blocking read, or total Stop time is bounded.
PREFIX = "/ccapi/ver100"
BODY_BUDGET = 0.1
FOCUS_WIRE_ROUTES = [
    ("POST", "/shooting/control/drivefocus"),
    ("PUT", "/shooting/liveview/afframeposition"),
    ("POST", "/shooting/liveview/clickwb"),
]


class FiniteFocusPeer:
    """Finite loopback ACK after a synthetic command has already been applied."""

    def __init__(self, *, status=200, headers=None, slow=True, body=None, apply=True, redirect=False):
        self.status = status
        self.headers = dict(headers or {})
        self.slow = slow
        self.body = body
        self.apply = apply
        self.redirect = redirect
        self.requests, self.applied, self.trace = [], [], []
        self.first_byte = threading.Event()
        self.halted = threading.Event()
        peer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def respond(self):
                raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                payload = json.loads(raw) if raw else None
                command = (self.command, urlsplit(self.path).path, payload)
                peer.requests.append(command)
                if peer.redirect and command[1] != "/redirected-focus":
                    self.send_response(302)
                    self.send_header("Location", "/redirected-focus")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                # Application precedes even the response headers. A lost/late
                # receipt cannot prove rejection or that the write was not sent.
                if peer.apply:
                    peer.applied.append(command)
                    peer.trace.append(("command", "applied"))
                slow = peer.slow
                data = peer.body if peer.body is not None else (b" " * 40 + b"{}" if slow else b"{}")
                self.send_response(peer.status)
                self.send_header("Content-Length", str(len(data)))
                for name, value in peer.headers.items():
                    self.send_header(name, value)
                self.end_headers()
                with suppress(BrokenPipeError, ConnectionResetError):
                    if slow:
                        for value in data:
                            self.wfile.write(bytes([value]))
                            self.wfile.flush()
                            peer.first_byte.set()
                            if peer.halted.wait(0.015):
                                break
                    else:
                        self.wfile.write(data)
                        self.wfile.flush()
                        peer.first_byte.set()

            do_GET = do_POST = do_PUT = do_DELETE = respond

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        # server_close joins finite handlers instead of abandoning an I/O worker.
        self.server.daemon_threads = False
        self.thread = threading.Thread(
            target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True,
        )
        self.thread.start()
        self.origin = f"http://127.0.0.1:{self.server.server_port}"

    def close(self):
        self.halted.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        assert not self.thread.is_alive()


@pytest.fixture
def focus_peer_factory():
    peers = []

    def create(**kwargs):
        peer = FiniteFocusPeer(**kwargs)
        peers.append(peer)
        return peer

    yield create
    for peer in peers:
        peer.close()


class ObservedFocusBody:
    """Observe the real urllib body; neither reads nor close are substituted."""

    def __init__(self, body, owner):
        self.body, self.owner = body, owner
        self.close_count = 0
        self.read_count = 0

    def read(self, size):
        self.read_count += 1
        return self.body.read(size)

    def read1(self, size):
        self.read_count += 1
        return self.body.read1(size)

    def close(self):
        self.body.close()
        self.close_count += 1
        self.owner.trace.append(("command", "body-closed"))
        self.owner.body_closed.set()


class ObservedFocusHttp(UrllibCcapiTransport):
    def __init__(self, owner):
        super().__init__()
        self.owner = owner
        self.open_options, self.bodies = [], []

    def _open(self, method, url, **kwargs):
        self.open_options.append(dict(kwargs))
        response = super()._open(method, url, **kwargs)
        response.body = ObservedFocusBody(response.body, self.owner)
        self.bodies.append(response.body)
        return response


class FocusCommandTransport(FakeCcapiTransport):
    """Only the selected command uses localhost; discovery/JPEG/status stay fast."""

    def __init__(self, peer, method, suffix, *, hold_producer=False):
        super().__init__(reject_live_view_size=False)
        self.peer, self.method, self.suffix = peer, method, suffix
        self.trace = peer.trace
        self.commands, self.stop_commands, self.request_options = [], [], []
        self.hold_producer = hold_producer
        self.producer_entered = threading.Event()
        self.body_closed = threading.Event()
        self.producer_held = threading.Event()
        self.allow_producer_exit = threading.Event()
        self.stop_entered = threading.Event()
        self.producer_thread = None
        self.producer_exit_thread = None
        self.producer_lock_observer = None
        self.producer_lock_state = None
        self.reject_stop = False
        self.http = ObservedFocusHttp(self)

    def request(self, method, url, *, body=None, **kwargs):
        path = urlsplit(url).path
        payload = json.loads(body) if body else None
        if method == self.method and path == PREFIX + self.suffix:
            command = RecordedRequest(method, path, payload)
            self.requests.append(command)
            self.commands.append(command)
            self.request_options.append(dict(kwargs))
            self.trace.append(("command", "enter"))
            self.producer_thread = threading.get_ident()
            self.producer_entered.set()
            # Preserve the production argument for an explicit assertion, then
            # shorten only this fixture's transport budget. No production clock,
            # lock, ownership, redirect, retry, or receipt rule is patched.
            kwargs["timeout"] = BODY_BUDGET if self.peer.slow else 1.0
            try:
                return self.http.request(method, url, body=body, **kwargs)
            finally:
                if self.hold_producer:
                    if self.producer_lock_observer is not None:
                        self.producer_lock_state = self.producer_lock_observer()
                    self.trace.append(("command", "producer-held"))
                    self.producer_held.set()
                    assert self.allow_producer_exit.wait(2), "Original command producer was not released"
                self.producer_exit_thread = threading.get_ident()
                self.trace.append(("command", "exit"))
        if method == "DELETE" and path == PREFIX + "/shooting/liveview":
            command = RecordedRequest(method, path, payload)
            self.stop_commands.append(command)
            self.trace.append(("saved-stop", "enter"))
            self.stop_entered.set()
            try:
                if self.reject_stop:
                    self.requests.append(command)
                    return CcapiResponse(503, {"content-length": "4"}, b"busy")
                return super().request(method, url, body=body, **kwargs)
            finally:
                self.trace.append(("saved-stop", "exit"))
        return super().request(method, url, body=body, **kwargs)

    def close(self):
        self.trace.append(("transport", "closed"))
        self.http.close()
        super().close()


@contextmanager
def focus_api(peer, route, *, hold_producer=False, live_view=True):
    method = "PUT" if route[0] == "tap" else "POST"
    transport = FocusCommandTransport(peer, method, route[2], hold_producer=hold_producer)
    app = create_app(ccapi_engine=CcapiEngine(
        lambda *_: transport, sleeper=lambda _: None, route_resolver=lambda _: "127.0.0.1",
    ))
    with TestClient(app) as client:
        created = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": peer.origin})
        assert created.status_code == 201, created.text
        identifier = created.json()["id"]
        base = "/v1/session/" + identifier
        if live_view:
            started = client.post(
                base + "/liveview/start", json={"fps": 15, "size": "MEDIUM", "source": "CCAPI_JPEG_POLLING"},
            )
            assert started.status_code == 200, started.text
            frame = client.get(base + "/liveview/frame")
            assert frame.status_code == 200, frame.text
        manager = app.state.session_manager
        session = manager.get(identifier)
        try:
            yield client, base, session, transport, manager, identifier
        finally:
            transport.allow_producer_exit.set()


def assert_unknown_focus_receipt(response):
    assert response.status_code == 502, response.text
    error = response.json()["error"]
    assert error["code"] == "CCAPI_UNREACHABLE", error
    assert "command completion is unknown" in error["message"], error
    assert "accepted" not in response.json()
    assert response.json().get("responseEvidence") not in {"rejected", "not-sent"}


def assert_one_applied_focus(peer, transport, route):
    assert len(transport.commands) == len(peer.requests) == len(peer.applied) == 1
    command = transport.commands[0]
    assert (command.method, command.path, command.body) == peer.requests[0] == peer.applied[0]
    assert command.method == ("PUT" if route[0] == "tap" else "POST")
    assert command.path == PREFIX + route[2]
    if route[0] == "drive":
        assert command.body == {"value": "near2"}
    else:
        assert set(command.body) == {"positionx", "positiony"}
    assert transport.request_options[0]["timeout"] == 15.0
    assert transport.request_options[0]["max_bytes"] == MAX_JSON_BYTES
    assert transport.request_options[0].get("strict_control", False) is False
    assert transport.http.open_options[0]["follow_redirects"] is True
    assert transport.http.open_options[0]["strict_control"] is False
    assert transport.http.bodies[0].close_count == 1
    assert transport.http.bodies[0].body.closed


def assert_read_only_followup_does_not_replay(client, base, transport, route):
    status = client.get(base + "/status")
    capabilities = client.get(base + "/capabilities")
    assert status.status_code == capabilities.status_code == 200
    assert route[4] not in capabilities.json()["evidence"]["observedFeatures"]
    assert len(transport.commands) == 1, "Read-only follow-up must never replay an uncertain command"


@pytest.mark.parametrize("route", ROUTES, ids=lambda item: item[0])
def test_focus_finite_slow_drip_rejects_late_applied_success(focus_peer_factory, route):
    peer = focus_peer_factory()
    with focus_api(peer, route) as (client, base, _session, transport, _manager, _identifier):
        started = time.monotonic()
        response = client.post(base + route[1], json=route[3])
        elapsed = time.monotonic() - started
        assert_one_applied_focus(peer, transport, route)
        # Baseline f9254312 reads all finite drip bytes and returns HTTP 200.
        # Candidate must not publish that late success, even though it applied.
        assert_unknown_focus_receipt(response)
        assert elapsed < 0.4, "Cooperative body reader did not reject the finite slow-drip ACK"
        assert transport.http.bodies[0].read_count > 1
        assert_read_only_followup_does_not_replay(client, base, transport, route)


@pytest.mark.parametrize(("method", "suffix"), FOCUS_WIRE_ROUTES, ids=[route[0] for route in ROUTES])
@pytest.mark.parametrize("finished_at", [BODY_BUDGET, BODY_BUDGET + 0.01], ids=["exact", "late"])
def test_focus_bytes_at_or_after_deadline_are_unknown_and_closed(method, suffix, finished_at):
    raw = BytesIO(b"Accepted")
    transport = UrllibCcapiTransport()
    with (
        patch.object(transport, "_open", return_value=CcapiStreamResponse(200, {}, raw)) as opened,
        patch("open_eos_bridge.ccapi.time.monotonic", side_effect=[4.0, 4.01, 4.0 + finished_at]) as clock,
        pytest.raises(BridgeError) as failure,
    ):
        transport.request(method, "http://synthetic.invalid/ccapi/ver120" + suffix + "?synthetic=1",
                          timeout=BODY_BUDGET)
    assert failure.value.code == "CCAPI_UNREACHABLE"
    assert "command completion is unknown" in failure.value.message
    assert clock.call_count == 3
    assert opened.call_args.kwargs["timeout"] == BODY_BUDGET
    assert opened.call_args.kwargs["follow_redirects"] is True
    assert opened.call_args.kwargs["strict_control"] is False
    assert raw.closed


@pytest.mark.parametrize(("method", "suffix"), FOCUS_WIRE_ROUTES, ids=[route[0] for route in ROUTES])
def test_focus_open_consumes_the_original_absolute_body_budget(method, suffix):
    class UnreadBody(BytesIO):
        def read(self, _size):
            raise AssertionError("Open consumed the budget; no body read may begin")

        read1 = read

    raw = UnreadBody(b"{}")
    transport = UrllibCcapiTransport()
    with (
        patch.object(transport, "_open", return_value=CcapiStreamResponse(200, {}, raw)) as opened,
        patch("open_eos_bridge.ccapi.time.monotonic", side_effect=[9.0, 9.0 + BODY_BUDGET]) as clock,
        pytest.raises(BridgeError) as failure,
    ):
        transport.request(method, "http://synthetic.invalid" + PREFIX + suffix, timeout=BODY_BUDGET)
    assert failure.value.code == "CCAPI_UNREACHABLE"
    assert "command completion is unknown" in failure.value.message
    assert clock.call_count == 2
    assert opened.call_count == 1
    assert raw.closed


@pytest.mark.parametrize(("method", "suffix"), FOCUS_WIRE_ROUTES, ids=[route[0] for route in ROUTES])
@pytest.mark.parametrize("late", [False, True], ids=["timely", "late"])
def test_focus_received_http_rejection_keeps_status_and_only_timely_diagnostic(method, suffix, late):
    raw = BytesIO(b"diagnostic")
    transport = UrllibCcapiTransport()
    ticks = [2.0, 2.01, 2.11] if late else [2.0, 2.01, 2.02, 2.03, 2.04]
    with (
        patch.object(transport, "_open", return_value=CcapiStreamResponse(503, {}, raw)) as opened,
        patch("open_eos_bridge.ccapi.time.monotonic", side_effect=ticks),
    ):
        response = transport.request(method, "http://synthetic.invalid" + PREFIX + suffix,
                                     timeout=BODY_BUDGET)
    assert response.status == 503
    assert response.body == (b"" if late else b"diagnostic")
    assert opened.call_count == 1
    assert raw.closed


@pytest.mark.parametrize("route", ROUTES, ids=lambda item: item[0])
def test_focus_api_slow_http_rejection_is_received_not_unknown(focus_peer_factory, route):
    peer = focus_peer_factory(status=503, body=b"diagnostic" * 4, apply=False)
    with focus_api(peer, route) as (client, base, _session, transport, _manager, _identifier):
        response = client.post(base + route[1], json=route[3])
        assert response.status_code == 502, response.text
        error = response.json()["error"]
        assert error["code"] == "CCAPI_HTTP_ERROR", error
        assert "returned HTTP 503" in error["message"], error
        assert "unknown" not in error["message"]
        assert "Body:" not in error["message"], "A late optional diagnostic must be discarded"
        assert len(peer.requests) == len(transport.commands) == 1
        assert peer.applied == []
        assert transport.http.bodies[0].close_count == 1
        assert transport.http.bodies[0].body.closed
        assert_read_only_followup_does_not_replay(client, base, transport, route)


@pytest.mark.parametrize("route", ROUTES, ids=lambda item: item[0])
@pytest.mark.parametrize(
    ("status", "headers"),
    [(206, {}), (200, {"Content-Range": "bytes 0-1/4"}), (200, {"Content-Range": ""})],
    ids=["partial_206", "ranged_200", "empty_range_200"],
)
@pytest.mark.parametrize("slow", [False, True], ids=["timely", "late"])
def test_focus_partial_ack_never_becomes_accepted(focus_peer_factory, route, status, headers, slow):
    peer = focus_peer_factory(status=status, headers=headers, slow=slow)
    with focus_api(peer, route) as (client, base, _session, transport, _manager, _identifier):
        response = client.post(base + route[1], json=route[3])
        assert_one_applied_focus(peer, transport, route)
        if slow:
            # The body deadline wins before complete-ACK validation; it must not
            # be reinterpreted as proof the already-applied command was rejected.
            assert_unknown_focus_receipt(response)
        else:
            assert response.status_code == 502, response.text
            assert response.json()["error"]["code"] == "INVALID_CCAPI_RESPONSE"
        assert_read_only_followup_does_not_replay(client, base, transport, route)


@pytest.mark.parametrize(("method", "suffix"), FOCUS_WIRE_ROUTES, ids=[route[0] for route in ROUTES])
def test_focus_redirect_keeps_legacy_method_behavior(focus_peer_factory, method, suffix):
    peer = focus_peer_factory(slow=False, apply=False, redirect=True)
    response = UrllibCcapiTransport().request(method, peer.origin + PREFIX + suffix,
                                            body=b"{}", timeout=1.0)
    # urllib's legacy 302 POST -> GET behavior is unchanged. A PUT is not
    # silently rewritten, and returns its received redirect status instead.
    expected = [(method, PREFIX + suffix, {})]
    if method == "POST":
        expected.append(("GET", "/redirected-focus", None))
    assert peer.requests == expected
    assert response.status == (200 if method == "POST" else 302)
    assert response.body == (b"{}" if method == "POST" else b"")


@pytest.mark.parametrize(
    ("method", "suffix"),
    [
        ("GET", "/shooting/control/drivefocus"),
        ("PUT", "/shooting/control/drivefocus"),
        ("POST", "/shooting/liveview/afframeposition"),
        ("PUT", "/shooting/liveview/clickwb"),
        ("POST", "/shooting/liveview/clickwb/other"),
        ("GET", "/contents/card1/IMAGE.JPG"),
        ("POST", "/focus/drive"),
        ("POST", "/whitebalance/click"),
        ("POST", "/shooting/control/recbutton"),
    ],
)
def test_unrelated_focus_method_suffix_and_legacy_rec_keep_no_clock(method, suffix):
    raw = BytesIO(b"Accepted")
    transport = UrllibCcapiTransport()
    with (
        patch.object(transport, "_open", return_value=CcapiStreamResponse(200, {}, raw)) as opened,
        patch("open_eos_bridge.ccapi.time.monotonic") as clock,
    ):
        assert transport.request(method, "http://synthetic.invalid" + PREFIX + suffix).body == b"Accepted"
    clock.assert_not_called()
    assert opened.call_args.kwargs["follow_redirects"] is True
    assert opened.call_args.kwargs["strict_control"] is False
    assert raw.closed


@pytest.mark.parametrize(("method", "suffix"), FOCUS_WIRE_ROUTES, ids=[route[0] for route in ROUTES])
def test_focus_stream_open_does_not_gain_a_body_deadline_or_close(method, suffix):
    raw = BytesIO(b"stream bytes")
    response = CcapiStreamResponse(200, {}, raw)
    transport = UrllibCcapiTransport()
    try:
        with (
            patch.object(transport, "_open", return_value=response) as opened,
            patch("open_eos_bridge.ccapi.time.monotonic") as clock,
        ):
            assert transport.open_stream(method, "http://synthetic.invalid" + PREFIX + suffix) is response
        clock.assert_not_called()
        assert raw.tell() == 0 and not raw.closed
        assert opened.call_args.kwargs["timeout"] == 60.0
        assert opened.call_args.kwargs["follow_redirects"] is True
        assert opened.call_args.kwargs["strict_control"] is False
    finally:
        response.close()
    assert raw.closed


@pytest.mark.parametrize("method", ["POST", "PUT"])
def test_modern_rec_strict_control_remains_an_already_bounded_negative_control(method):
    raw = BytesIO(b"{}")
    transport = UrllibCcapiTransport()
    with (
        patch.object(transport, "_open", return_value=CcapiStreamResponse(200, {}, raw)) as opened,
        patch("open_eos_bridge.ccapi.time.monotonic", side_effect=[1.0, 1.01, 1.11]) as clock,
        pytest.raises(BridgeError) as failure,
    ):
        transport.request(method, "http://synthetic.invalid" + PREFIX + "/shooting/control/recbutton",
                          body=b'{"action":"start"}', timeout=BODY_BUDGET, strict_control=True)
    # Both baseline and candidate are already bounded through strict_control.
    # This is a control, never evidence of newly fixing the modern REC route.
    assert failure.value.code == "CCAPI_UNREACHABLE"
    assert clock.call_count == 3
    assert opened.call_args.kwargs["follow_redirects"] is False
    assert opened.call_args.kwargs["strict_control"] is True
    assert raw.closed


def test_registered_modern_rec_is_already_bounded_and_keeps_unconfirmed_saved_stop(focus_peer_factory):
    route = ("modern_rec", "/recording/start/outcome", "/shooting/control/recbutton", {}, "VIDEO_RECORDING")
    peer = focus_peer_factory()
    with focus_api(peer, route, live_view=False) as (client, base, session, transport, _manager, _identifier):
        response = client.post(base + route[1])
        assert response.status_code == 200, response.text
        unavailable = {
            "version": 1, "engine": "ccapi", "observationEvidence": "unavailable",
            "recording": None, "source": None,
        }
        assert response.json() == {
            "version": 1, "engine": "ccapi", "operation": "start",
            "responseEvidence": "unconfirmed", "observation": unavailable,
        }
        assert len(transport.commands) == len(peer.requests) == len(peer.applied) == 1
        assert transport.commands[0] == RecordedRequest("POST", PREFIX + route[2], {"action": "start"})
        assert transport.request_options[0]["timeout"] == 10.0
        assert transport.request_options[0]["max_bytes"] == 32768
        assert transport.request_options[0]["strict_control"] is True
        assert transport.http.open_options[0]["follow_redirects"] is False
        assert transport.http.open_options[0]["strict_control"] is True
        assert transport.http.bodies[0].close_count == 1
        assert transport.http.bodies[0].body.closed
        saved_stop = session._recording_stop_operation
        assert saved_stop is not None
        assert (saved_stop.method, saved_stop.path) == ("POST", PREFIX + route[2])
        assert session._recording_last_stop_operation is saved_stop
        assert_read_only_followup_does_not_replay(client, base, transport, route)
        observation = client.get(base + "/recording/observation")
        assert observation.status_code == 200 and observation.json() == unavailable
        assert session._recording_stop_operation is saved_stop
        assert len(transport.commands) == 1
        # This explicit Stop is the existing modern recovery contract, not a
        # focus deadline change and never an automatic retry of REC Start.
        peer.slow = False
        stopped = client.post(base + "/recording/stop/outcome")
        assert stopped.status_code == 200, stopped.text
        assert stopped.json() == {
            "version": 1, "engine": "ccapi", "operation": "stop",
            "responseEvidence": "response-received", "observation": unavailable,
        }
        assert transport.commands == [
            RecordedRequest("POST", PREFIX + route[2], {"action": "start"}),
            RecordedRequest(saved_stop.method, saved_stop.path, {"action": "stop"}),
        ]
        assert len(peer.requests) == len(peer.applied) == 2
        assert session._recording_stop_operation is None


def assert_focus_producer_still_owns_original_locks(session, guard, transport, saved_stop):
    assert transport.producer_lock_state == (True, True), "Producer must retain provider and execution locks"
    assert guard._execution.locked(), "Closing the body must not release API execution ownership"
    acquired = session._lock.acquire(blocking=False)
    if acquired:
        session._lock.release()
    assert acquired is False, "Closing the body must not release the original provider lock"
    assert session._pending_live_view_stop is saved_stop
    assert transport.producer_thread is not None
    assert transport.producer_exit_thread is None
    assert transport.body_closed.is_set()
    assert transport.http.bodies[0].close_count == 1
    assert transport.http.bodies[0].body.closed
    assert not transport.stop_entered.is_set()


@pytest.mark.parametrize("route", ROUTES, ids=lambda item: item[0])
@pytest.mark.parametrize("cleanup", ["ordinary_stop", "delete", "direct_close"])
def test_registered_focus_timeout_joins_actual_producer_before_saved_liveview_stop(
    focus_peer_factory, route, cleanup,
):
    peer = focus_peer_factory()
    with focus_api(peer, route, hold_producer=True) as (client, base, session, transport, manager, identifier):
        guard = manager._bulb_requests_for(identifier)
        assert manager.is_current(identifier, session)
        saved_stop = session._pending_live_view_stop
        assert saved_stop is not None
        assert (saved_stop.operation.method, saved_stop.operation.path, saved_stop.payload) == (
            "DELETE", PREFIX + "/shooting/liveview", None,
        )
        transport.producer_lock_observer = lambda: (session._lock._is_owned(), guard._execution.locked())
        outcomes = {}
        done = {name: threading.Event() for name in ("focus", "cleanup")}
        cleanup_attempted = threading.Event()

        def clean_up():
            cleanup_attempted.set()
            if cleanup == "ordinary_stop":
                return client.post(base + "/liveview/stop")
            if cleanup == "delete":
                return client.delete(base)
            return session.close()

        def call(name, operation):
            try:
                outcomes[name] = operation()
            except Exception as error:
                outcomes[name] = error
            finally:
                done[name].set()

        producer = threading.Thread(
            target=call, args=("focus", lambda: client.post(base + route[1], json=route[3])), daemon=True,
        )
        cleaner = threading.Thread(target=call, args=("cleanup", clean_up), daemon=True)
        try:
            producer.start()
            assert peer.first_byte.wait(1)
            cleaner.start()
            assert cleanup_attempted.wait(1), "Cleanup caller must actually run"
            assert transport.producer_held.wait(1), "Finite body did not return to its original producer"
            assert not done["focus"].is_set() and not done["cleanup"].is_set()
            assert_focus_producer_still_owns_original_locks(session, guard, transport, saved_stop)
            assert transport.stop_commands == []
            transport.allow_producer_exit.set()
            assert done["focus"].wait(1) and done["cleanup"].wait(1)
            assert not isinstance(outcomes["focus"], Exception), outcomes["focus"]
            assert_unknown_focus_receipt(outcomes["focus"])
            assert not isinstance(outcomes["cleanup"], Exception), outcomes["cleanup"]
            if cleanup == "ordinary_stop":
                assert outcomes["cleanup"].status_code == 200, outcomes["cleanup"].text
                assert outcomes["cleanup"].json()["active"] is False
                assert manager.is_current(identifier, session)
                assert_read_only_followup_does_not_replay(client, base, transport, route)
            elif cleanup == "delete":
                assert outcomes["cleanup"].status_code == 204, outcomes["cleanup"].text
                assert not manager.is_current(identifier, session)
            else:
                assert outcomes["cleanup"] is None
                assert session._closed is True
            assert_one_applied_focus(peer, transport, route)
            assert transport.stop_commands == [RecordedRequest(
                saved_stop.operation.method, saved_stop.operation.path,
                dict(saved_stop.payload) if saved_stop.payload is not None else None,
            )]
            assert transport.producer_thread == transport.producer_exit_thread
            assert transport.trace.index(("command", "body-closed")) < transport.trace.index(
                ("command", "producer-held"),
            ) < transport.trace.index(("command", "exit")) < transport.trace.index(("saved-stop", "enter"))
            assert session._pending_live_view_stop is None
            assert session.live_view_stop_unconfirmed is False
        finally:
            transport.allow_producer_exit.set()
            producer.join(2)
            if cleaner.ident is not None:
                cleaner.join(2)
            assert not producer.is_alive() and not cleaner.is_alive()


@pytest.mark.parametrize("route", ROUTES, ids=lambda item: item[0])
def test_focus_timeout_failed_stop_retains_exact_owner_until_explicit_recovery(focus_peer_factory, route):
    peer = focus_peer_factory()
    with focus_api(peer, route, hold_producer=True) as (client, base, session, transport, manager, identifier):
        guard = manager._bulb_requests_for(identifier)
        saved_stop = session._pending_live_view_stop
        assert saved_stop is not None
        transport.producer_lock_observer = lambda: (session._lock._is_owned(), guard._execution.locked())
        transport.reject_stop = True
        outcomes = {}
        done = {name: threading.Event() for name in ("focus", "stop")}
        stop_attempted = threading.Event()

        def call(name):
            try:
                if name == "stop":
                    stop_attempted.set()
                    outcomes[name] = client.post(base + "/liveview/stop")
                else:
                    outcomes[name] = client.post(base + route[1], json=route[3])
            except Exception as error:
                outcomes[name] = error
            finally:
                done[name].set()

        producer = threading.Thread(target=call, args=("focus",), daemon=True)
        stopper = threading.Thread(target=call, args=("stop",), daemon=True)
        try:
            producer.start()
            assert peer.first_byte.wait(1)
            stopper.start()
            assert stop_attempted.wait(1)
            assert transport.producer_held.wait(1)
            assert not done["focus"].is_set() and not done["stop"].is_set()
            assert_focus_producer_still_owns_original_locks(session, guard, transport, saved_stop)
            transport.allow_producer_exit.set()
            assert done["focus"].wait(1) and done["stop"].wait(1)
            assert not isinstance(outcomes["focus"], Exception), outcomes["focus"]
            assert_unknown_focus_receipt(outcomes["focus"])
            assert_one_applied_focus(peer, transport, route)
            rejected = outcomes["stop"]
            assert not isinstance(rejected, Exception), rejected
            assert rejected.status_code == 409, rejected.text
            assert rejected.json()["error"]["code"] == "LIVE_VIEW_STOP_UNCONFIRMED"
            assert manager.is_current(identifier, session)
            assert manager._bulb_requests_for(identifier) is guard
            assert session._pending_live_view_stop is saved_stop
            assert session.live_view_stop_unconfirmed is True
            expected_stop = RecordedRequest(
                saved_stop.operation.method, saved_stop.operation.path,
                dict(saved_stop.payload) if saved_stop.payload is not None else None,
            )
            assert transport.stop_commands == [expected_stop]
            assert_read_only_followup_does_not_replay(client, base, transport, route)
            assert session._pending_live_view_stop is saved_stop
            assert transport.stop_commands == [expected_stop], "Read-only checks must not retry Stop"
            transport.reject_stop = False
            recovered = client.post(base + "/liveview/stop")
            assert recovered.status_code == 200, recovered.text
            assert recovered.json()["active"] is False
            assert recovered.json()["liveViewStopUnconfirmed"] is False
            assert transport.stop_commands == [expected_stop, expected_stop]
            assert session._pending_live_view_stop is None
            assert session.live_view_stop_unconfirmed is False
            assert manager._bulb_requests_for(identifier) is guard
            assert_one_applied_focus(peer, transport, route)
            assert_read_only_followup_does_not_replay(client, base, transport, route)
            assert transport.producer_thread == transport.producer_exit_thread
            assert transport.trace.index(("command", "body-closed")) < transport.trace.index(
                ("command", "producer-held"),
            ) < transport.trace.index(("command", "exit")) < transport.trace.index(("saved-stop", "enter"))
        finally:
            # A failed assertion must not make fixture shutdown an implicit
            # rejected-Stop loop; teardown may finish the same saved obligation.
            transport.reject_stop = False
            transport.allow_producer_exit.set()
            producer.join(2)
            if stopper.ident is not None:
                stopper.join(2)
            assert not producer.is_alive() and not stopper.is_alive()
