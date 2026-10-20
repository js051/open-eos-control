"""Finite status body deadlines, not a whole-status or total Stop time guarantee."""
from __future__ import annotations

import json
import threading
import time
from contextlib import suppress
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
from unittest.mock import Mock, patch
from urllib.parse import urlsplit

import pytest

from open_eos_bridge.ccapi import CcapiOperation, CcapiSession, CcapiStreamResponse, UrllibCcapiTransport
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.sessions import SessionManager

PREFIX = "/ccapi/ver100"
BATTERY = PREFIX + "/devicestatus/batterylist"
AF = PREFIX + "/shooting/control/af"
MANUAL = PREFIX + "/shooting/control/shutterbutton/manual"
BUDGET = 0.1
OPTIONAL_STATUS_PATHS = (
    "/functions/cardselection/stillimage", "/functions/cardselection/movie",
    "/functions/beep", "/functions/displayoff",
)
OPTIONAL_STATUS_VALUES = {
    "/functions/cardselection/stillimage": {"value": "card1", "ability": ["none", "card1", "card2"]},
    "/functions/cardselection/movie": {"value": "card2", "ability": ["none", "card1", "card2"]},
    "/functions/beep": {"value": "enable", "ability": ["enable", "disable", "disabletouch"]},
    "/functions/displayoff": {"value": "60", "ability": ["10", "20", "30", "60", "120", "180"]},
}
OPTIONAL_SETTING_KEYS = {
    "/functions/cardselection/stillimage": "cardselectionstillimage",
    "/functions/cardselection/movie": "cardselectionmovie",
    "/functions/beep": "beep",
    "/functions/displayoff": "displayoff",
}
STATUS_PATHS = (
    "/devicestatus/batterylist", "/devicestatus/battery",
    "/devicestatus/storage", "/devicestatus/currentstorage", "/contents",
    "/shooting/information/recordable", "/devicestatus/lens", "/devicestatus/temperature",
) + OPTIONAL_STATUS_PATHS


class StatusPeer:
    """Synthetic localhost peer with one finite, valid-JSON slow status response."""

    def __init__(self):
        self.requests = []
        self.slow_path = None
        self.slow_status = 200
        self.redirect_path = None
        self.reject_stop = False
        self.values = {}
        self.statuses = {}
        self.first_byte = threading.Event()
        self.closed = threading.Event()
        self._lock = threading.Lock()
        peer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def respond(self):
                raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                payload = json.loads(raw) if raw else None
                path = urlsplit(self.path).path
                with peer._lock:
                    peer.requests.append((self.command, path, payload))
                    slow = self.command == "GET" and path == peer.slow_path
                    if slow:
                        peer.slow_path = None
                if path == peer.redirect_path:
                    self.send_response(302)
                    self.send_header("Location", "/status-target")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                action = (payload or {}).get("action")
                status = peer.slow_status if slow else (
                    503 if peer.reject_stop and action in {"stop", "release"} else peer.statuses.get(path, 200)
                )
                value = peer.values.get(
                    path, {"shootingmode": {"value": "Bulb"}} if path.endswith("/shooting/settings") else {},
                )
                body = b" " * 40 + b"{}" if slow else json.dumps(value).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                with suppress(BrokenPipeError, ConnectionResetError):
                    if slow:
                        for byte in body:
                            self.wfile.write(bytes([byte]))
                            self.wfile.flush()
                            peer.first_byte.set()
                            if peer.closed.wait(0.015):
                                break
                    else:
                        self.wfile.write(body)

            do_GET = do_POST = do_PUT = respond

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
def peer():
    value = StatusPeer()
    yield value
    value.close()


@pytest.mark.parametrize("suffix", STATUS_PATHS)
def test_status_slow_drip_rejects_late_success_body(peer, suffix):
    path = PREFIX + suffix
    peer.slow_path = path
    started = time.monotonic()
    with pytest.raises(BridgeError) as failure:
        UrllibCcapiTransport().request("GET", peer.origin + path, timeout=BUDGET)
    assert failure.value.code == "CCAPI_UNREACHABLE"
    assert time.monotonic() - started < 0.4
    assert peer.requests == [("GET", path, None)]


def test_status_slow_rejection_body_preserves_received_status(peer):
    peer.slow_path = BATTERY
    peer.slow_status = 503
    started = time.monotonic()
    response = UrllibCcapiTransport().request("GET", peer.origin + BATTERY, timeout=BUDGET)
    assert response.status == 503 and response.body == b""
    assert time.monotonic() - started < 0.4
    assert peer.requests == [("GET", BATTERY, None)]


def test_status_get_keeps_existing_redirect_policy(peer):
    peer.redirect_path = BATTERY
    response = UrllibCcapiTransport().request("GET", peer.origin + BATTERY, timeout=BUDGET)
    assert response.status == 200 and response.body == b"{}"
    assert peer.requests == [("GET", BATTERY, None), ("GET", "/status-target", None)]


@pytest.mark.parametrize("suffix", OPTIONAL_STATUS_PATHS)
def test_optional_status_slow_rejection_body_preserves_received_status(peer, suffix):
    path = PREFIX + suffix
    peer.slow_path, peer.slow_status = path, 503
    started = time.monotonic()
    response = UrllibCcapiTransport().request("GET", peer.origin + path, timeout=BUDGET)
    assert response.status == 503 and response.body == b""
    assert time.monotonic() - started < 0.4
    assert peer.requests == [("GET", path, None)]


@pytest.mark.parametrize("suffix", OPTIONAL_STATUS_PATHS)
def test_optional_status_get_keeps_existing_redirect_policy(peer, suffix):
    path = PREFIX + suffix
    peer.redirect_path = path
    response = UrllibCcapiTransport().request("GET", peer.origin + path, timeout=BUDGET)
    assert response.status == 200 and response.body == b"{}"
    assert peer.requests == [("GET", path, None), ("GET", "/status-target", None)]


@pytest.mark.parametrize("suffix", STATUS_PATHS)
@pytest.mark.parametrize("finished_at", [BUDGET, BUDGET + 0.01])
def test_exact_status_routes_reject_bytes_at_or_after_absolute_deadline(suffix, finished_at):
    body = BytesIO(b"{}")
    transport = UrllibCcapiTransport()
    with patch.object(transport, "_open", return_value=CcapiStreamResponse(200, {}, body)) as opened, patch(
        "open_eos_bridge.ccapi.time.monotonic", side_effect=[0, 0.01, finished_at],
    ), pytest.raises(BridgeError) as failure:
        transport.request("GET", "http://192.0.2.1/ccapi/ver110" + suffix + "?synthetic=1", timeout=BUDGET)
    assert failure.value.code == "CCAPI_UNREACHABLE"
    assert body.closed
    assert opened.call_args.kwargs["follow_redirects"] is True
    assert opened.call_args.kwargs["strict_control"] is False


@pytest.mark.parametrize("suffix", STATUS_PATHS)
def test_status_open_cannot_reset_the_absolute_body_deadline(suffix):
    class UnreadBody(BytesIO):
        def read1(self, _size):
            raise AssertionError("Body must not be read after open consumed the existing budget")

    body = UnreadBody(b"{}")
    transport = UrllibCcapiTransport()
    with patch.object(transport, "_open", return_value=CcapiStreamResponse(200, {}, body)), patch(
        "open_eos_bridge.ccapi.time.monotonic", side_effect=[0, BUDGET],
    ), pytest.raises(BridgeError) as failure:
        transport.request("GET", "http://192.0.2.1" + PREFIX + suffix, timeout=BUDGET)
    assert failure.value.code == "CCAPI_UNREACHABLE"
    assert body.closed


def test_status_default_timeout_remains_fifteen_seconds():
    body = BytesIO(b"{}")
    transport = UrllibCcapiTransport()
    with patch.object(transport, "_open", return_value=CcapiStreamResponse(200, {}, body)) as opened:
        assert transport.request("GET", "http://192.0.2.1" + BATTERY).body == b"{}"
    assert opened.call_args.kwargs["timeout"] == 15.0
    assert body.closed


@pytest.mark.parametrize(("method", "path"), [
    ("POST", BATTERY), ("PUT", BATTERY), ("DELETE", BATTERY),
    ("GET", BATTERY + "/unknown"), ("GET", PREFIX + "/contents/card/SYNTHETIC.JPG"),
    ("GET", PREFIX + "/shooting/liveview"), ("GET", PREFIX + "/event/polling"),
    ("GET", "/ccapi"), ("POST", PREFIX + "/shooting/control/recbutton"),
    ("POST", PREFIX + "/shooting/control/drivefocus"),
])
def test_unrelated_routes_keep_legacy_body_policy(method, path):
    body = BytesIO(b"{}")
    transport = UrllibCcapiTransport()
    with patch.object(transport, "_open", return_value=CcapiStreamResponse(200, {}, body)), patch(
        "open_eos_bridge.ccapi.time.monotonic",
    ) as clock:
        assert transport.request(method, "http://192.0.2.1" + path).body == b"{}"
    clock.assert_not_called()
    assert body.closed


@pytest.mark.parametrize("suffix", OPTIONAL_STATUS_PATHS)
@pytest.mark.parametrize("method", ["PUT", "POST", "DELETE", "HEAD"])
def test_optional_status_non_get_methods_keep_legacy_body_policy(suffix, method):
    body = BytesIO(b"{}")
    transport = UrllibCcapiTransport()
    with patch.object(transport, "_open", return_value=CcapiStreamResponse(200, {}, body)) as opened, patch(
        "open_eos_bridge.ccapi.time.monotonic",
    ) as clock:
        assert transport.request(method, "http://192.0.2.1" + PREFIX + suffix).body == b"{}"
    clock.assert_not_called()
    assert opened.call_args.kwargs["follow_redirects"] is (method != "DELETE")
    assert opened.call_args.kwargs["strict_control"] is False
    assert body.closed


@pytest.mark.parametrize("suffix", OPTIONAL_STATUS_PATHS)
@pytest.mark.parametrize("tail", ["/unknown", "unknown", "/"])
def test_optional_status_suffix_extensions_keep_legacy_body_policy(suffix, tail):
    body = BytesIO(b"{}")
    transport = UrllibCcapiTransport()
    with patch.object(transport, "_open", return_value=CcapiStreamResponse(200, {}, body)), patch(
        "open_eos_bridge.ccapi.time.monotonic",
    ) as clock:
        assert transport.request("GET", "http://192.0.2.1" + PREFIX + suffix + tail).body == b"{}"
    clock.assert_not_called()
    assert body.closed


@pytest.mark.parametrize("suffix", OPTIONAL_STATUS_PATHS)
def test_optional_status_open_stream_does_not_read_or_close_the_body(suffix):
    class UnreadBody(BytesIO):
        def read(self, _size=-1):
            raise AssertionError("Opening a stream must not consume its response body")

        read1 = read

    body = UnreadBody(b"{}")
    response = CcapiStreamResponse(200, {}, body)
    transport = UrllibCcapiTransport()
    url = "http://192.0.2.1" + PREFIX + suffix
    try:
        with patch.object(transport, "_open", return_value=response) as opened, patch(
            "open_eos_bridge.ccapi.time.monotonic",
        ) as clock:
            assert transport.open_stream("GET", url, timeout=BUDGET) is response
        clock.assert_not_called()
        opened.assert_called_once_with(
            "GET", url, headers=None, timeout=BUDGET, follow_redirects=True, strict_control=False,
        )
        assert not body.closed
    finally:
        response.close()
    assert body.closed


@pytest.mark.parametrize(("payload", "headers", "limit", "error"), [
    (b"1234", {"content-length": "4"}, 4, None),
    (b"12345", {}, 4, "CCAPI_RESPONSE_TOO_LARGE"),
    (b"12", {"content-length": "4"}, 4, "CCAPI_UNREACHABLE"),
])
def test_status_size_and_declared_length_checks_are_preserved(payload, headers, limit, error):
    body = BytesIO(payload)
    transport = UrllibCcapiTransport()
    with patch.object(transport, "_open", return_value=CcapiStreamResponse(200, headers, body)):
        if error is None:
            assert transport.request("GET", "http://192.0.2.1" + BATTERY, max_bytes=limit).body == payload
        else:
            with pytest.raises(BridgeError) as failure:
                transport.request("GET", "http://192.0.2.1" + BATTERY, max_bytes=limit)
            assert failure.value.code == error
    assert body.closed


@pytest.mark.parametrize("broken", [False, True])
def test_status_rejection_keeps_diagnostic_or_original_status_and_closes(broken):
    class DiagnosticBody(BytesIO):
        def read1(self, size):
            if broken:
                raise OSError("synthetic diagnostic read failure")
            return super().read1(size)

    body = DiagnosticBody(b"diagnostic")
    transport = UrllibCcapiTransport()
    with patch.object(transport, "_open", return_value=CcapiStreamResponse(401, {}, body)):
        response = transport.request("GET", "http://192.0.2.1" + BATTERY)
    assert response.status == 401
    assert response.body == (b"" if broken else b"diagnostic")
    assert body.closed


@pytest.mark.parametrize("suffix", OPTIONAL_STATUS_PATHS)
@pytest.mark.parametrize("broken", [False, True])
def test_optional_status_rejection_keeps_diagnostic_or_original_status_and_closes(suffix, broken):
    class DiagnosticBody(BytesIO):
        def read(self, size=-1):
            if broken:
                raise OSError("synthetic diagnostic read failure")
            return super().read(size)

        read1 = read

    body = DiagnosticBody(b"diagnostic")
    transport = UrllibCcapiTransport()
    with patch.object(transport, "_open", return_value=CcapiStreamResponse(401, {}, body)):
        response = transport.request("GET", "http://192.0.2.1" + PREFIX + suffix)
    assert response.status == 401
    assert response.body == (b"" if broken else b"diagnostic")
    assert body.closed


class TrackedBody:
    def __init__(self, body, trace, label):
        self.body, self.trace, self.label = body, trace, label

    def read(self, size):
        return self.body.read(size)

    def read1(self, size):
        return self.body.read1(size)

    def close(self):
        self.body.close()
        self.trace.append((self.label, "body-closed"))


class ObservedTransport(UrllibCcapiTransport):
    """Keep real I/O; a test barrier holds the original producer before its exit."""

    def __init__(self, observed_path=BATTERY):
        super().__init__()
        self.trace = []
        self.observe_next_status = False
        self.observed_path = observed_path
        self.producer_ready = threading.Event()
        self.allow_producer_exit = threading.Event()
        self.stop_entered = threading.Event()
        self._local = threading.local()

    def _open(self, method, url, **kwargs):
        response = super()._open(method, url, **kwargs)
        response.body = TrackedBody(response.body, self.trace, self._local.label)
        return response

    def request(self, method, url, **kwargs):
        payload = json.loads(kwargs["body"]) if kwargs.get("body") else {}
        action = payload.get("action")
        observed = self.observe_next_status and method == "GET" and urlsplit(url).path == self.observed_path
        if observed:
            self.observe_next_status = False
        label = "status-producer" if observed else action or urlsplit(url).path
        self._local.label = label
        self.trace.append((label, "enter"))
        if action in {"stop", "release"}:
            self.stop_entered.set()
        kwargs["timeout"] = BUDGET
        try:
            return super().request(method, url, **kwargs)
        finally:
            if observed:
                self.producer_ready.set()
                assert self.allow_producer_exit.wait(2), "Test did not release original status producer"
            self.trace.append((label, "exit"))


def session_and_manager(peer, optional_suffixes=(), observed_path=BATTERY):
    transport = ObservedTransport(observed_path)
    session = CcapiSession(peer.origin, transport)
    session._initialized = True
    # By default optional reads remain unadvertised, preserving the original
    # battery-only fixture. Opt-in pairs use the same exact GET/PUT version.
    session._operations = {CcapiOperation("POST", AF), CcapiOperation("PUT", MANUAL)}
    for suffix in optional_suffixes:
        path = PREFIX + suffix
        session._operations.update({CcapiOperation("GET", path), CcapiOperation("PUT", path)})
        peer.values[path] = OPTIONAL_STATUS_VALUES[suffix]
    manager = SessionManager(Mock())
    identifier = manager._register(session).id
    return session, transport, manager, identifier


@pytest.mark.parametrize("suffix", OPTIONAL_STATUS_PATHS)
def test_optional_status_reads_only_its_advertised_same_version_pair_after_aggregate(peer, suffix):
    session, _transport, _manager, _identifier = session_and_manager(peer, (suffix,))
    assert session.status().mode == "Bulb"
    assert peer.requests == [
        ("GET", BATTERY, None), ("GET", PREFIX + "/devicestatus/storage", None),
        ("GET", PREFIX + "/shooting/settings", None), ("GET", PREFIX + suffix, None),
    ]
    assert session._setting_paths[OPTIONAL_SETTING_KEYS[suffix]] == PREFIX + suffix


@pytest.mark.parametrize("suffix", OPTIONAL_STATUS_PATHS)
@pytest.mark.parametrize("advertisement", ["unadvertised", "get-only", "put-only", "mismatched"])
def test_optional_status_without_matching_pair_keeps_aggregate_only_fallback(peer, suffix, advertisement):
    session, _transport, _manager, _identifier = session_and_manager(peer)
    path = PREFIX + suffix
    if advertisement in {"get-only", "mismatched"}:
        session._operations.add(CcapiOperation("GET", path))
    if advertisement in {"put-only", "mismatched"}:
        write_path = "/ccapi/ver110" + suffix if advertisement == "mismatched" else path
        session._operations.add(CcapiOperation("PUT", write_path))
    peer.slow_path = path
    assert session.status().mode == "Bulb"
    assert peer.requests == [
        ("GET", BATTERY, None), ("GET", PREFIX + "/devicestatus/storage", None),
        ("GET", PREFIX + "/shooting/settings", None),
    ]
    assert peer.slow_path == path
    assert OPTIONAL_SETTING_KEYS[suffix] not in session._setting_paths


@pytest.mark.parametrize("suffix", OPTIONAL_STATUS_PATHS)
def test_optional_status_skips_newer_mismatched_pair_and_reads_original_pair(peer, suffix):
    session, _transport, _manager, _identifier = session_and_manager(peer, (suffix,))
    newer_read = "/ccapi/ver110" + suffix
    session._operations.update({
        CcapiOperation("GET", newer_read), CcapiOperation("PUT", "/ccapi/ver120" + suffix),
    })
    peer.slow_path = newer_read
    assert session.status().mode == "Bulb"
    assert peer.requests == [
        ("GET", BATTERY, None), ("GET", PREFIX + "/devicestatus/storage", None),
        ("GET", PREFIX + "/shooting/settings", None), ("GET", PREFIX + suffix, None),
    ]
    assert peer.slow_path == newer_read
    assert session._setting_paths[OPTIONAL_SETTING_KEYS[suffix]] == PREFIX + suffix


@pytest.mark.parametrize("suffix", OPTIONAL_STATUS_PATHS)
def test_optional_status_http_rejection_keeps_other_advertised_reads(peer, suffix):
    session, _transport, _manager, _identifier = session_and_manager(peer, OPTIONAL_STATUS_PATHS)
    peer.statuses[PREFIX + suffix] = 404
    assert session.status().mode == "Bulb"
    assert peer.requests == [
        ("GET", BATTERY, None), ("GET", PREFIX + "/devicestatus/storage", None),
        ("GET", PREFIX + "/shooting/settings", None),
        *(("GET", PREFIX + path, None) for path in OPTIONAL_STATUS_PATHS),
    ]
    assert OPTIONAL_SETTING_KEYS[suffix] not in session._setting_paths
    for other in OPTIONAL_STATUS_PATHS:
        if other != suffix:
            assert session._setting_paths[OPTIONAL_SETTING_KEYS[other]] == PREFIX + other


@pytest.mark.parametrize("cleanup", ["held-af", "owned-bulb", "close"])
def test_status_timeout_joins_original_producer_before_saved_stop(peer, cleanup):
    _assert_status_timeout_joins_original_producer_before_saved_stop(peer, cleanup)


@pytest.mark.parametrize("suffix", OPTIONAL_STATUS_PATHS)
@pytest.mark.parametrize("cleanup", ["held-af", "owned-bulb", "close"])
def test_optional_status_timeout_joins_original_producer_before_saved_stop(peer, suffix, cleanup):
    _assert_status_timeout_joins_original_producer_before_saved_stop(
        peer, cleanup, optional_suffixes=OPTIONAL_STATUS_PATHS, slow_path=PREFIX + suffix,
    )


def _assert_status_timeout_joins_original_producer_before_saved_stop(
    peer, cleanup, *, optional_suffixes=(), slow_path=BATTERY,
):
    session, transport, manager, identifier = session_and_manager(peer, optional_suffixes, slow_path)
    owner = "synthetic-owner"
    if cleanup == "held-af":
        session.start_autofocus(owner)
        saved_stop = session._short_control_release
        stop_action = "stop"
    else:
        assert manager.start_bulb_exposure(identifier, owner).outcome == "started"
        saved_stop = session._bulb_release_operation
        stop_action = "release"

    def stop():
        if cleanup == "held-af":
            return session.stop_autofocus(owner)
        if cleanup == "close":
            return session.close()
        return manager.stop_bulb_exposure(identifier, owner)

    peer.slow_path = slow_path
    transport.observe_next_status = True
    outcomes = {}
    completed = {"status": threading.Event(), "stop": threading.Event()}
    stop_attempted = threading.Event()

    def call(name, operation):
        try:
            if name == "stop":
                stop_attempted.set()
            outcomes[name] = operation()
        except Exception as error:
            outcomes[name] = error
        finally:
            completed[name].set()

    reader = threading.Thread(target=call, args=("status", manager.get(identifier).status), daemon=True)
    stopper = threading.Thread(target=call, args=("stop", stop), daemon=True)
    try:
        reader.start()
        assert peer.first_byte.wait(1)
        stopper.start()
        assert stop_attempted.wait(1), "The Stop caller must actually be scheduled"
        assert transport.producer_ready.wait(0.4), "Status body exceeded its absolute response-body budget"
        assert ("status-producer", "body-closed") in transport.trace
        assert not transport.stop_entered.is_set(), "Saved Stop must wait for the original producer to exit"
        assert not completed["stop"].is_set()
        if cleanup == "held-af":
            assert session._held_autofocus_owner == owner
            assert session._short_control_release is saved_stop
        else:
            assert session._bulb_release_operation is saved_stop
            assert manager._bulb_requests_for(identifier)._active_owner == owner
        transport.allow_producer_exit.set()
        assert completed["status"].wait(1) and completed["stop"].wait(1)
        assert isinstance(outcomes["status"], BridgeError)
        assert outcomes["status"].code == "CCAPI_UNREACHABLE"
        assert not isinstance(outcomes["stop"], Exception)
        assert transport.trace.index(("status-producer", "body-closed")) < transport.trace.index(
            ("status-producer", "exit"),
        ) < transport.trace.index((stop_action, "enter"))
        commands = [payload["action"] for _, _, payload in peer.requests if payload]
        assert commands == (["start", "stop"] if cleanup == "held-af" else ["full_press", "release"])
        saved_operation = saved_stop[0] if cleanup == "held-af" else saved_stop
        assert [(method, path) for method, path, payload in peer.requests
                if payload and payload["action"] == stop_action] == [(saved_operation.method, saved_operation.path)]
        assert {path for method, path, _ in peer.requests if method == "GET"} <= {
            BATTERY, PREFIX + "/devicestatus/storage", PREFIX + "/shooting/settings",
            *(PREFIX + suffix for suffix in optional_suffixes),
        }, "Only explicitly advertised optional pairs may extend the fixture's status reads"
        assert ("GET", slow_path, None) in peer.requests
        if cleanup == "held-af":
            assert outcomes["stop"].active is False
            assert session._short_control_release is None and session._held_autofocus_owner is None
        elif cleanup == "owned-bulb":
            assert outcomes["stop"].owner_id == owner and outcomes["stop"].outcome == "released"
            assert session._bulb_release_operation is None
            assert manager.stop_bulb_exposure(identifier, owner).outcome == "released"
            assert [payload["action"] for _, _, payload in peer.requests if payload] == commands
        else:
            assert session._closed and session._bulb_release_operation is None
    finally:
        transport.allow_producer_exit.set()
        reader.join(2)
        if stopper.ident is not None:
            stopper.join(2)
        if session._held_autofocus_timer is not None:
            session._held_autofocus_timer.cancel()
        assert not reader.is_alive() and not stopper.is_alive()


def test_status_timeout_does_not_retire_failed_saved_stop_or_replay_start(peer):
    _assert_status_timeout_does_not_retire_failed_saved_stop_or_replay_start(peer)


@pytest.mark.parametrize("suffix", OPTIONAL_STATUS_PATHS)
def test_optional_status_timeout_does_not_retire_failed_saved_stop_or_replay_start(peer, suffix):
    _assert_status_timeout_does_not_retire_failed_saved_stop_or_replay_start(
        peer, optional_suffixes=OPTIONAL_STATUS_PATHS, slow_path=PREFIX + suffix,
    )


def _assert_status_timeout_does_not_retire_failed_saved_stop_or_replay_start(
    peer, *, optional_suffixes=(), slow_path=BATTERY,
):
    session, transport, manager, identifier = session_and_manager(peer, optional_suffixes)
    owner = "synthetic-owner"
    manager.start_bulb_exposure(identifier, owner)
    original = session._bulb_release_operation
    peer.slow_path = slow_path
    with pytest.raises(BridgeError) as failure:
        session.status()
    assert failure.value.code == "CCAPI_UNREACHABLE"
    peer.reject_stop = True
    with pytest.raises(BridgeError) as failure:
        manager.stop_bulb_exposure(identifier, owner)
    assert failure.value.code == "SHUTTER_RELEASE_UNCONFIRMED"
    assert session._bulb_release_operation is original and session._shutter_release_unconfirmed
    assert manager._bulb_requests_for(identifier)._active_owner == owner
    peer.reject_stop = False
    assert manager.stop_bulb_exposure(identifier, owner).outcome == "released"
    assert [payload["action"] for _, _, payload in peer.requests if payload] == ["full_press", "release", "release"]
    assert session._bulb_release_operation is None
    assert transport.stop_entered.is_set()


def test_late_stop_status_tail_preserves_release_ack_and_owner_recovery(peer):
    _assert_late_stop_status_tail_preserves_release_ack_and_owner_recovery(peer)


@pytest.mark.parametrize("suffix", OPTIONAL_STATUS_PATHS)
def test_optional_late_stop_status_tail_preserves_release_ack_and_owner_recovery(peer, suffix):
    _assert_late_stop_status_tail_preserves_release_ack_and_owner_recovery(
        peer, optional_suffixes=OPTIONAL_STATUS_PATHS, slow_path=PREFIX + suffix,
    )


def _assert_late_stop_status_tail_preserves_release_ack_and_owner_recovery(
    peer, *, optional_suffixes=(), slow_path=BATTERY,
):
    session, _transport, manager, identifier = session_and_manager(peer, optional_suffixes)
    owner = "synthetic-owner"
    manager.start_bulb_exposure(identifier, owner)
    peer.slow_path = slow_path
    with pytest.raises(BridgeError) as failure:
        manager.stop_bulb_exposure(identifier, owner)
    assert failure.value.code == "CCAPI_UNREACHABLE"
    assert session._bulb_release_operation is None
    assert session._bulb_exposure_active is False and session._shutter_release_unconfirmed is False
    assert manager._bulb_requests_for(identifier)._active_owner == owner
    assert manager.stop_bulb_exposure(identifier, owner).outcome == "released"
    assert manager._bulb_requests_for(identifier)._active_owner is None
    assert [payload["action"] for _, _, payload in peer.requests if payload] == ["full_press", "release"]
