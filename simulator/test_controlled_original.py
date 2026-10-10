"""Bounded real-socket checks of the opt-in original hold, never APK/device evidence."""

import asyncio
import hashlib
import http.client
import json
import socket
import threading
import time
from contextlib import contextmanager
from uuid import UUID, uuid4

import pytest
import uvicorn

import main

CONFIGURE = "/ccapi/test/capture-delivery"
HOLD = f"{CONFIGURE}/original-hold"
CAPTURE = "/ccapi/ver100/shooting/control/shutterbutton"
ITEM_ID = "SIM_0003.JPG"
ORIGINAL = f"/ccapi/ver100/contents/card1/100CANON/{ITEM_ID}"
ALIASES = ("", "?kind=main", "?type=main")


class Peer:
    def __init__(self, port):
        self.port = port

    def request(self, method, path, payload=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
        try:
            body = json.dumps(payload) if payload is not None else None
            connection.request(method, path, body=body, headers={"Content-Type": "application/json"})
            response = connection.getresponse()
            data = response.read()
            return response.status, json.loads(data) if data else None
        finally:
            connection.close()

    def fixture(self):
        status, state = self.request("GET", "/ccapi/test/state")
        assert status == 200
        return state["capture_delivery"]

    def gate(self):
        return self.fixture()["original_hold"]

    def prepare(self, settings=None):
        assert self.request("POST", CONFIGURE, settings or {})[0] == 200
        assert self.request("POST", CAPTURE, {"af": True})[0] == 204

    def arm(self):
        status, record = self.request("POST", HOLD, {"item_id": ITEM_ID})
        assert status == 200
        return record

    def control(self, record, action):
        return self.request("POST", f"{HOLD}/{record['gate_id']}/{action}")

    def wait_for(self, predicate):
        deadline = time.monotonic() + 3
        while True:
            record = self.gate()
            if predicate(record):
                return record
            assert time.monotonic() < deadline, record
            time.sleep(0.01)

    @contextmanager
    def original(self, query=""):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
        response = None
        try:
            connection.request("GET", ORIGINAL + query)
            response = connection.getresponse()
            yield response
        finally:
            if response is not None:
                response.close()
            connection.close()


@pytest.fixture(scope="module")
def peer():
    # One worker, no proxy, ephemeral loopback port, bounded startup and shutdown.
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    server = uvicorn.Server(uvicorn.Config(
        main.app, host="127.0.0.1", port=0, lifespan="off", log_level="critical",
        access_log=False, timeout_graceful_shutdown=1,
    ))
    thread = threading.Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
    thread.start()
    deadline = time.monotonic() + 3
    try:
        while not server.started:
            assert thread.is_alive() and time.monotonic() < deadline, "Uvicorn startup did not complete"
            time.sleep(0.01)
        yield Peer(listener.getsockname()[1])
    finally:
        server.should_exit = True
        thread.join(timeout=4)
        listener.close()
        assert not thread.is_alive(), "Uvicorn did not stop within the bounded shutdown"


@pytest.fixture(autouse=True)
def reset_peer(peer):
    assert peer.request("POST", "/ccapi/test/reset")[0] == 200
    yield
    record = peer.gate()
    if record is not None and record["stream_active"]:
        peer.control(record, "cancel")
        peer.wait_for(lambda gate: not gate["stream_active"])
    assert peer.request("POST", "/ccapi/test/reset")[0] == 200


def assert_truncated(response, prefix):
    with pytest.raises(http.client.IncompleteRead) as failure:
        response.read()
    observed = prefix + failure.value.partial
    original = main.capture_delivery_jpeg("original")
    assert observed == original[:len(observed)]
    assert len(observed) < len(original)


def assert_latched(peer, outcome):
    record = peer.wait_for(lambda gate: not gate["stream_active"])
    assert record["outcome"] == outcome
    assert record["phase"] == "terminal"
    assert record["timestamps"]["response_finished"] is None
    assert record["timestamps"]["response_ended"] is not None
    assert record["body_bytes_asgi_accepted"] < record["expected_byte_count"]
    for query in ALIASES:
        with peer.original(query) as response:
            assert response.status == 410
            response.read()
    assert peer.control(record, "release")[0] == 409
    assert peer.request("POST", CONFIGURE, {})[0] == 409
    assert peer.request("POST", HOLD, {"item_id": ITEM_ID})[0] == 409
    assert peer.gate()["rejected_original_count"] == 3
    return record


def test_default_and_unarmed_originals_are_unchanged(peer):
    assert peer.gate() is None
    assert peer.request("POST", HOLD, {"item_id": ITEM_ID})[0] == 409
    peer.prepare()
    expected = peer.fixture()["representations"]
    for query in ALIASES:
        with peer.original(query) as response:
            body = response.read()
            assert response.status == 200
            assert body == main.capture_delivery_jpeg("original")
            assert hashlib.sha256(body).hexdigest() == expected["original"]["sha256"]
    assert peer.gate() is None
    peer.arm()
    for representation in ("thumbnail", "display", "info"):
        with peer.original(f"?kind={representation}") as response:
            body = response.read()
            assert response.status == 200
            if representation == "info":
                assert json.loads(body)["filesize"] == expected["original"]["byte_count"]
            else:
                assert body == main.capture_delivery_jpeg(representation)
    assert peer.gate()["request_id"] is None


def test_arm_and_controls_reject_wrong_scope_without_mutation(peer):
    peer.prepare()
    baseline = peer.fixture()
    for payload in ({}, {"item_id": True}, {"item_id": "../SIM_0003.JPG"},
                    {"item_id": ITEM_ID, "max_hold_ms": 1}):
        assert peer.request("POST", HOLD, payload)[0] == 422
        assert peer.fixture() == baseline
    assert peer.request("POST", HOLD, {"item_id": "SIM_9999.JPG"})[0] == 409
    assert peer.fixture() == baseline
    record = peer.arm()
    assert record["max_hold_ms"] == 180_000
    assert record["drip_interval_ms"] == 1_000
    assert record["prefix_bytes"] == 64
    assert record["expected_byte_count"] == baseline["representations"]["original"]["byte_count"]
    assert record["expected_sha256"] == baseline["representations"]["original"]["sha256"]
    assert peer.control(record, "release")[0] == 409
    for action in ("release", "cancel"):
        assert peer.request("POST", f"{HOLD}/{uuid4()}/{action}")[0] == 409
        assert peer.request("POST", f"{HOLD}/invalid/{action}")[0] == 422
    assert peer.gate() == record
    assert peer.request("POST", CONFIGURE, {"enabled": False})[0] == 409
    assert peer.gate() == record


@pytest.mark.parametrize("settings", [{"original_delay_ms": 1}, {"original_failures": 1}])
def test_arm_rejects_conflicting_original_settings(peer, settings):
    peer.prepare(settings)
    baseline = peer.fixture()
    assert peer.request("POST", HOLD, {"item_id": ITEM_ID})[0] == 409
    assert peer.fixture() == baseline


@pytest.mark.parametrize("query", ALIASES)
def test_release_preserves_exact_bytes_and_one_request_identity(peer, query):
    peer.prepare()
    armed = peer.arm()
    started = time.monotonic()
    with peer.original(query) as response:
        assert response.status == 200
        assert response.headers["Content-Type"] == "image/jpeg"
        assert response.headers["Content-Length"] == str(armed["expected_byte_count"])
        assert response.headers["Cache-Control"] == "no-store"
        assert response.headers.get("Content-Encoding") is None
        prefix = response.read(64)
        assert prefix == main.capture_delivery_jpeg("original")[:64]
        assert time.monotonic() - started < 2
        holding = peer.wait_for(lambda gate: gate["phase"] == "holding")
        assert holding["body_bytes_asgi_accepted"] == 64
        assert holding["request_id"] == f"{armed['gate_id']}/1"
        assert holding["request_query"] == query.removeprefix("?")
        assert holding["timestamps"]["response_finished"] is None
        assert peer.request("POST", "/ccapi/test/reset")[0] == 409
        status, released = peer.control(armed, "release")
        assert status == 200
        complete = prefix + response.read()
    finished = peer.wait_for(lambda gate: not gate["stream_active"])
    assert finished["outcome"] == "finished"
    assert complete == main.capture_delivery_jpeg("original")
    assert len(complete) == finished["expected_byte_count"] == finished["body_bytes_asgi_accepted"]
    assert hashlib.sha256(complete).hexdigest() == finished["expected_sha256"]
    assert finished["body_sha256_asgi_accepted"] == finished["expected_sha256"]
    ordered = [finished["timestamps"][name]["elapsed_ms"] for name in (
        "armed", "request_arrived", "headers_asgi_accepted", "prefix_asgi_accepted",
        "release_requested", "response_finished", "response_ended",
    )]
    assert ordered == sorted(ordered)
    status, duplicate = peer.control(armed, "release")
    assert status == 200
    assert duplicate["timestamps"]["release_requested"] == released["timestamps"]["release_requested"]
    assert peer.control(armed, "cancel")[0] == 409
    with peer.original(query) as response:
        assert response.status == 410
        response.read()
    assert peer.fixture()["representation_get_counts"]["original"] == 2


def test_socket_receives_one_real_byte_each_second_before_release(peer):
    peer.prepare()
    armed = peer.arm()
    with peer.original() as response:
        received = response.read(64)
        previous = time.monotonic()
        for _ in range(2):
            received += response.read(1)
            now = time.monotonic()
            assert 0.8 <= now - previous < 2.5
            previous = now
        held = peer.gate()
        assert held["phase"] == "holding"
        assert held["drip_count"] == 2
        assert held["body_bytes_asgi_accepted"] == 66
        assert 800 <= held["max_drip_interval_ms"] < 2_500
        assert held["timestamps"]["last_drip_asgi_accepted"] is not None
        for query in ALIASES:
            with peer.original(query) as duplicate:
                assert duplicate.status == 409
                duplicate.read()
        assert peer.gate()["request_id"] == held["request_id"]
        assert peer.gate()["rejected_original_count"] == 3
        assert peer.control(armed, "release")[0] == 200
        assert received + response.read() == main.capture_delivery_jpeg("original")
    peer.wait_for(lambda gate: not gate["stream_active"])


def test_expiry_truncates_response_and_rejects_all_fallbacks(peer, monkeypatch):
    # Only this test shortens the internal constant; no API exposes a duration knob.
    monkeypatch.setattr(main, "ORIGINAL_HOLD_MAX_SECONDS", 0.25)
    peer.prepare()
    peer.arm()
    with peer.original() as response:
        prefix = response.read(64)
        assert_truncated(response, prefix)
    ended = assert_latched(peer, "expired")
    assert ended["timestamps"]["expired"] is not None
    assert ended["timestamps"]["release_requested"] is None


def test_cancel_truncates_response_and_reset_requires_cleanup(peer):
    peer.prepare()
    armed = peer.arm()
    with peer.original() as response:
        prefix = response.read(64)
        assert peer.request("POST", "/ccapi/test/reset")[0] == 409
        status, cancelled = peer.control(armed, "cancel")
        assert status == 200
        assert_truncated(response, prefix)
    ended = assert_latched(peer, "operator_cancelled")
    status, repeated = peer.control(armed, "cancel")
    assert status == 200
    assert repeated["timestamps"]["cancel_requested"] == cancelled["timestamps"]["cancel_requested"]
    assert peer.request("POST", "/ccapi/test/reset")[0] == 200
    assert peer.gate() is None
    assert peer.fixture()["enabled"] is False
    assert peer.control(ended, "release")[0] == 409
    peer.prepare()
    replacement = peer.arm()
    assert replacement["gate_id"] != armed["gate_id"]
    assert peer.control(armed, "cancel")[0] == 409
    assert peer.gate() == replacement


def test_disconnect_truncates_and_latches_the_selected_original(peer):
    peer.prepare()
    peer.arm()
    with peer.original() as response:
        assert len(response.read(64)) == 64
    ended = assert_latched(peer, "client_disconnected")
    assert ended["timestamps"]["disconnect_observed"] is not None


def test_cancel_before_request_remains_latched_until_reset(peer):
    peer.prepare()
    armed = peer.arm()
    assert peer.control(armed, "cancel")[0] == 200
    ended = assert_latched(peer, "operator_cancelled")
    assert ended["request_id"] is None
    assert ended["timestamps"]["request_arrived"] is None
    assert peer.control(armed, "cancel")[0] == 200


def test_cancel_cannot_undo_already_accepted_original_bytes(peer, monkeypatch):
    del peer

    async def exercise():
        gate = main.OriginalHold(ITEM_ID, main.capture_delivery_jpeg("original"))
        monkeypatch.setattr(main, "_original_hold", gate)
        response = gate.claim("")
        final_marker = asyncio.Event()
        complete = asyncio.Event()

        async def receive():
            await asyncio.Future()

        async def send(message):
            if message["type"] == "http.response.body" and not message.get("more_body", False):
                final_marker.set()
                await complete.wait()

        task = asyncio.create_task(response({"type": "http", "asgi": {"spec_version": "2.3"}}, receive, send))
        try:
            async with asyncio.timeout(1):
                while gate.record["phase"] != "holding":
                    await asyncio.sleep(0)
            released = await main.release_original_hold(UUID(gate.record["gate_id"]))
            await asyncio.wait_for(final_marker.wait(), timeout=1)
            assert gate.record["body_bytes_asgi_accepted"] == gate.record["expected_byte_count"]
            assert gate.record["timestamps"]["response_finished"] is None
            with pytest.raises(main.HTTPException) as rejected:
                await main.cancel_original_hold(UUID(gate.record["gate_id"]))
            assert rejected.value.status_code == 409
            assert gate.record["timestamps"]["cancel_requested"] is None
            duplicate = await main.release_original_hold(UUID(gate.record["gate_id"]))
            assert duplicate["timestamps"]["release_requested"] == released["timestamps"]["release_requested"]
            complete.set()
            await asyncio.wait_for(task, timeout=1)
            assert gate.record["outcome"] == "finished"
            assert gate.record["stream_active"] is False
        finally:
            if not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)

    asyncio.run(exercise())


@pytest.mark.parametrize("failure", ["blocked_expiry", "blocked_cancel", "send_error", "server_cancel"])
def test_asgi_cleanup_bounds_blocked_send_and_preserves_error_outcome(peer, monkeypatch, failure):
    del peer
    if failure == "blocked_expiry":
        monkeypatch.setattr(main, "ORIGINAL_HOLD_MAX_SECONDS", 0.05)

    async def exercise():
        gate = main.OriginalHold(ITEM_ID, main.capture_delivery_jpeg("original"))
        monkeypatch.setattr(main, "_original_hold", gate)
        response = gate.claim("")
        blocked = asyncio.Event()

        async def receive():
            await asyncio.Future()

        async def send(message):
            if message["type"] != "http.response.body":
                return
            blocked.set()
            if failure == "send_error":
                raise OSError("Synthetic send failure")
            await asyncio.Future()

        task = asyncio.create_task(response({"type": "http", "asgi": {"spec_version": "2.3"}}, receive, send))
        await asyncio.wait_for(blocked.wait(), timeout=1)
        if failure == "blocked_cancel":
            await main.cancel_original_hold(UUID(gate.record["gate_id"]))
        elif failure == "server_cancel":
            task.cancel()
        expected_error = {
            "blocked_expiry": TimeoutError, "blocked_cancel": asyncio.CancelledError,
            "send_error": OSError, "server_cancel": asyncio.CancelledError,
        }[failure]
        with pytest.raises(expected_error):
            await asyncio.wait_for(task, timeout=1)
        expected = {
            "blocked_expiry": "expired", "blocked_cancel": "operator_cancelled",
            "send_error": "stream_error", "server_cancel": "server_cancelled",
        }[failure]
        assert gate.record["outcome"] == expected
        assert gate.record["stream_active"] is False
        assert gate.record["body_bytes_asgi_accepted"] == 0
        assert gate.record["timestamps"]["response_finished"] is None
        assert gate.record["timestamps"]["response_ended"] is not None
        if expected == "stream_error":
            assert gate.record["timestamps"]["stream_error"] is not None
        assert gate.response_task is None

    asyncio.run(exercise())
