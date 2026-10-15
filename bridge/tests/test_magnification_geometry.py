"""Synthetic protocol fixtures exercising the production CCAPI geometry cache."""
from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Event
from urllib.parse import urlsplit

import pytest

from open_eos_bridge.ccapi import CcapiEngine, CcapiResponse
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.models import LiveViewStartRequest

from .liveview_jpeg_fixture import JPEG
from .test_ccapi import (
    DISCOVERY,
    FakeCcapiTransport,
    FakeRtpSession,
    RecordedRequest,
    _detail_packet,
    _detailed_live_view,
    _json_response,
)

NATIVE_SOURCES = ("CCAPI_RTP", "CCAPI_MULTIPART")
SOURCES = (*NATIVE_SOURCES, "CCAPI_JPEG_POLLING")
GEOMETRIES = {1: (100, 200, 6000, 4000), 5: (1000, 500, 1200, 800), 10: (2000, 1000, 600, 400)}


class MagnificationTransport(FakeCcapiTransport):
    def __init__(self):
        super().__init__(discovery={"ver100": [*DISCOVERY["ver100"],
            {"path": "/shooting/settings/lvzoom", "get": True, "put": True},
            {"path": "/shooting/liveview/rtpsessiondesc", "get": True},
            {"path": "/shooting/liveview/rtp", "post": True},
            {"path": "/shooting/liveview/multipart", "get": True, "delete": True},
        ]})
        self.detail_status = 200
        self.detail_headers = {"content-type": "application/octet-stream"}
        self.missing_geometry = False
        self.zoom_failure = None
        self.mismatched_readback = False
        self.zoom_sent = False
        self.before_zoom = None
        self.reported_geometry = None
        self.before_detail = None
        self.detail_body = None

    def request(self, method, url, *, body=None, **kwargs):
        parsed = urlsplit(url)
        path = parsed.path + (f"?{parsed.query}" if parsed.query else "")
        if method == "GET" and "/flipdetail?" in path:
            self.requests.append(RecordedRequest(method, path, None))
            if self.before_detail:
                self.before_detail()
            x, y, width, height = self.reported_geometry or GEOMETRIES[int(self.live_view_magnification)]
            info = {} if self.missing_geometry else {"liveview": {"image": {
                "positionx": x, "positiony": y, "positionwidth": width, "positionheight": height}}}
            data = _detailed_live_view(JPEG, info) if self.detail_body is None else self.detail_body
            return CcapiResponse(self.detail_status, self.detail_headers, data)
        if path.endswith("/lvzoom") and method == "PUT":
            self.zoom_sent = True
            if self.before_zoom:
                self.before_zoom()
            if self.zoom_failure:
                self.requests.append(RecordedRequest(method, path, json.loads(body)))
                raise self.zoom_failure
        if path.endswith("/lvzoom") and method == "GET" and self.zoom_sent and self.mismatched_readback:
            self.requests.append(RecordedRequest(method, path, None))
            return _json_response({"value": "1", "ability": ["1", "5", "10"]})
        return super().request(method, url, body=body, **kwargs)


@pytest.fixture
def setup_session():
    sessions = []

    def create(source):
        transport = MagnificationTransport()
        session = CcapiEngine(lambda *_: transport, rtp_session_factory=lambda *_: FakeRtpSession(),
                              route_resolver=lambda _: "192.0.2.2").open_connection("http://192.0.2.1:8080")
        sessions.append(session)
        session.start_live_view(LiveViewStartRequest(source=source))
        if source == "CCAPI_JPEG_POLLING":
            session.live_view_frame()
        session.tap_focus(.5, .5)
        return session, transport

    yield create
    for session in sessions:
        session.close()


def coordinate_requests(transport):
    return [r for r in transport.requests if r.path.endswith(("/afframeposition", "/clickwb"))]


@pytest.mark.parametrize("source", SOURCES)
@pytest.mark.parametrize("target", [5, 10, 1])
def test_zoom_retires_geometry_before_send_and_reacquires_for_each_coordinate_action(setup_session, source, target):
    session, transport = setup_session(source)
    transport.before_zoom = lambda: _assert_geometry_retired(session)
    for value in (5, 10, target):
        session.set_live_view_magnification(value)
        assert session._latest_live_view_geometry is None
        if source == "CCAPI_JPEG_POLLING":
            with pytest.raises(BridgeError, match="metadata"):
                session.tap_focus(.5, .5)
            session.live_view_frame()
        session.tap_focus(.5, .5)
        session.click_white_balance(.25, .75)
        x, y, width, height = GEOMETRIES[value]
        assert coordinate_requests(transport)[-2].body == {"positionx": x + width // 2, "positiony": y + height // 2}
        assert coordinate_requests(transport)[-1].body == {
            "positionx": x + width // 4, "positiony": y + height * 3 // 4}


def _assert_geometry_retired(session):
    assert session._latest_live_view_geometry is None, "pre-change Canon geometry survived magnification send"


@pytest.mark.parametrize("source", NATIVE_SOURCES)
@pytest.mark.parametrize("failure", [BridgeError("CCAPI_UNREACHABLE", "ACK lost", status_code=502),
                                      KeyboardInterrupt("cancelled after send")])
def test_failed_or_cancelled_zoom_keeps_old_geometry_retired(setup_session, source, failure):
    session, transport = setup_session(source)
    transport.zoom_failure = failure
    with pytest.raises(type(failure)):
        session.set_live_view_magnification(5)
    assert session._latest_live_view_geometry is None
    transport.missing_geometry = True
    before = len(coordinate_requests(transport))
    with pytest.raises(BridgeError):
        session.tap_focus(.5, .5)
    with pytest.raises(BridgeError):
        session.click_white_balance(.5, .5)
    assert len(coordinate_requests(transport)) == before


@pytest.mark.parametrize("source", NATIVE_SOURCES)
def test_mismatched_zoom_readback_cannot_restore_old_geometry(setup_session, source):
    session, transport = setup_session(source)
    transport.mismatched_readback = True
    with pytest.raises(BridgeError, match="did not match"):
        session.set_live_view_magnification(5)
    assert session._latest_live_view_geometry is None
    session.tap_focus(.5, .5)
    assert coordinate_requests(transport)[-1].body == {"positionx": 1600, "positiony": 900}


@pytest.mark.parametrize("source", NATIVE_SOURCES)
@pytest.mark.parametrize("invalid", ["missing", "partial-status", "partial-header"])
def test_reacquisition_requires_authoritative_complete_geometry(setup_session, source, invalid):
    session, transport = setup_session(source)
    session.set_live_view_magnification(5)
    transport.missing_geometry = invalid == "missing"
    transport.detail_status = 206 if invalid == "partial-status" else 200
    if invalid == "partial-header":
        transport.detail_headers["Content-Range"] = "bytes 0-9/100"
    before = len(coordinate_requests(transport))
    for action in (session.tap_focus, session.click_white_balance):
        with pytest.raises(BridgeError):
            action(.5, .5)
        assert session._latest_live_view_geometry is None
    assert len(coordinate_requests(transport)) == before
    transport.missing_geometry = False
    transport.detail_status = 200
    transport.detail_headers.pop("Content-Range", None)
    session.tap_focus(.5, .5)
    assert coordinate_requests(transport)[-1].body == {"positionx": 1600, "positiony": 900}


@pytest.mark.parametrize("source", NATIVE_SOURCES)
def test_invalid_zoom_before_send_preserves_current_geometry(setup_session, source):
    session, transport = setup_session(source)
    geometry = session._latest_live_view_geometry
    with pytest.raises(BridgeError, match="one of"):
        session.set_live_view_magnification(2)
    assert transport.zoom_sent is False
    assert session._latest_live_view_geometry is geometry


@pytest.mark.parametrize("source", SOURCES)
def test_shared_golden_journey_uses_each_authoritative_rectangle(setup_session, source):
    vectors = json.loads((Path(__file__).resolve().parents[2] /
                         "contracts/magnification-geometry/v1/geometry-transition-vectors.json").read_text())["vectors"]
    session, transport = setup_session(source)
    for offset in range(0, len(vectors), 3):
        group = vectors[offset:offset + 3]
        geometry = group[0]["reportedGeometry"]
        transport.reported_geometry = tuple(geometry[key] for key in ("x", "y", "width", "height"))
        session.set_live_view_magnification(group[0]["observedMagnification"])
        assert session._latest_live_view_geometry is None
        if source == "CCAPI_JPEG_POLLING":
            session.live_view_frame()
        for vector in group:
            point, expected = vector["normalized"], vector["expectedCameraPoint"]
            for action in (session.tap_focus, session.click_white_balance):
                action(point["x"], point["y"])
                assert coordinate_requests(transport)[-1].body == {
                    "positionx": expected["x"], "positiony": expected["y"]}, vector["id"]


@pytest.mark.parametrize("source", NATIVE_SOURCES)
@pytest.mark.parametrize("bad", [(-1, 0, 100, 100), (0, -1, 100, 100), (0, 0, 0, 100),
                                 (0, 0, 100, -1), (True, 0, 100, 100)])
def test_invalid_fresh_rectangle_does_not_reopen_coordinate_control(setup_session, source, bad):
    session, transport = setup_session(source)
    session.set_live_view_magnification(5)
    transport.reported_geometry = bad
    before = len(coordinate_requests(transport))
    for action in (session.tap_focus, session.click_white_balance):
        with pytest.raises(BridgeError):
            action(.5, .5)
    assert session._latest_live_view_geometry is None
    assert len(coordinate_requests(transport)) == before


@pytest.mark.parametrize("source", NATIVE_SOURCES)
def test_pending_geometry_and_coordinate_dispatch_are_serialized_before_zoom(setup_session, source):
    session, transport = setup_session(source)
    session.set_live_view_magnification(5)
    entered, release, attempted_zoom = Event(), Event(), Event()

    def block_detail():
        entered.set()
        assert release.wait(5), "fixture geometry response was not released"

    def change_zoom():
        attempted_zoom.set()
        return session.set_live_view_magnification(10)

    transport.before_detail = block_detail
    with ThreadPoolExecutor(max_workers=2) as executor:
        focus = executor.submit(session.tap_focus, .5, .5)
        assert entered.wait(5)
        zoom = executor.submit(change_zoom)
        assert attempted_zoom.wait(5)
        # A second thread cannot admit a mutation while this session owns the
        # detailed read and coordinate dispatch. No asynchronous preflight gap.
        assert session._lock.acquire(blocking=False) is False
        release.set()
        focus.result(timeout=5)
        zoom.result(timeout=5)
    relevant = [r for r in transport.requests if r.method in {"PUT", "POST"}
                and r.path.endswith(("/lvzoom", "/afframeposition"))]
    assert relevant[-2].path.endswith("/afframeposition")
    assert relevant[-2].body == {"positionx": 1600, "positiony": 900}
    assert relevant[-1].body == {"value": "10"}
    assert session._latest_live_view_geometry is None
    transport.before_detail = None
    session.click_white_balance(.5, .5)
    assert coordinate_requests(transport)[-1].body == {"positionx": 2300, "positiony": 1200}


@pytest.mark.parametrize("source", NATIVE_SOURCES)
def test_late_native_frame_does_not_repopulate_retired_geometry(setup_session, source):
    session, _transport = setup_session(source)
    entered, release = Event(), Event()
    reader = session._rtp_session if source == "CCAPI_RTP" else session._multipart_session

    def late_frame(**_kwargs):
        entered.set()
        assert release.wait(5), "fixture native frame was not released"
        return JPEG

    method = "read_frame" if source == "CCAPI_RTP" else "next_frame"
    previous = getattr(reader, method)
    setattr(reader, method, late_frame)
    try:
        with ThreadPoolExecutor(max_workers=1) as executor:
            frame = executor.submit(session.live_view_frame)
            assert entered.wait(5)
            session.set_live_view_magnification(5)
            release.set()
            assert frame.result(timeout=5) == JPEG
        assert session._latest_live_view_geometry is None
    finally:
        release.set()
        setattr(reader, method, previous)


@pytest.mark.parametrize("source", NATIVE_SOURCES)
def test_closed_owner_cannot_acquire_geometry_or_send_coordinate_action(setup_session, source):
    session, transport = setup_session(source)
    session.set_live_view_magnification(5)
    session.close()
    before = len(transport.requests)
    for action in (session.tap_focus, session.click_white_balance):
        with pytest.raises(BridgeError, match="closed"):
            action(.5, .5)
    assert len(transport.requests) == before


@pytest.mark.parametrize("source", NATIVE_SOURCES)
@pytest.mark.parametrize("tail", ["truncated-image", "trailing-bytes", "incomplete-header", "bad-footer"])
def test_geometry_prefix_cannot_admit_incomplete_detail_envelope(setup_session, source, tail):
    session, transport = setup_session(source)
    session.set_live_view_magnification(5)
    geometry = {"liveview": {"image": {
        "positionx": 1000, "positiony": 500, "positionwidth": 1200, "positionheight": 800}}}
    prefix = _detail_packet(0x01, json.dumps(geometry).encode())
    image = _detail_packet(0x00, JPEG)
    endings = {"truncated-image": image[:-4], "trailing-bytes": image + b"trailing",
               "incomplete-header": b"\xff\x00\x00\x00", "bad-footer": image[:-2] + b"xx"}
    transport.detail_body = prefix + endings[tail]
    before = len(coordinate_requests(transport))
    for action in (session.tap_focus, session.click_white_balance):
        with pytest.raises(BridgeError):
            action(.5, .5)
        assert session._latest_live_view_geometry is None
    assert len(coordinate_requests(transport)) == before
    transport.detail_body = None
    session.tap_focus(.5, .5)
    assert coordinate_requests(transport)[-1].body == {"positionx": 1600, "positiony": 900}
