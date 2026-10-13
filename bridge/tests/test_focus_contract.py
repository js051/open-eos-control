from __future__ import annotations

import json
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Event
from unittest.mock import Mock, patch

from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine, CcapiOperation
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.focus_info import MAX_FOCUS_INFO_BYTES, parse_ccapi_focus_info, parse_ccapi_focus_info_packet
from open_eos_bridge.gphoto2 import GPhoto2Engine
from open_eos_bridge.models import LiveViewStartRequest

from .ccapi_bulb_peer import AF_PATH, BulbPeer
from .fakes import FakeRunner


def focus_json(status=0x21):
    return {"liveviewdata": {
        "image": {"positionx": 100, "positiony": 200, "positionwidth": 1000, "positionheight": 800},
        "zoom": {"magnification": 1},
        "afframe": [{"status": status, "select": 1, "x": 200, "y": 280, "width": 200, "height": 160}],
    }}


def packet(value):
    body = json.dumps(value).encode()
    return b"\xff\x00\x01" + len(body).to_bytes(4, "big") + body + b"\xff\xff"


def connect(client, peer):
    response = client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": peer.origin})
    assert response.status_code == 201, response.text
    return f"/v1/session/{response.json()['id']}"


def check_documented_status_kind_and_cropped_geometry(kind, name, status, name_status):
    info = parse_ccapi_focus_info_packet(packet(focus_json(kind | status)))
    assert info.magnification == 1 and info.image_aspect_ratio == 1.25
    assert info.frames[0].model_dump() == {
        "left": 0.1, "top": 0.1, "right": 0.3, "bottom": 0.3, "status": name_status, "kind": name,
    }


def check_invalid_status_never_becomes_focused(invalid):
    assert parse_ccapi_focus_info(focus_json(invalid)).frames == []


def check_only_exact_selected_frame_is_visible(selection):
    data = focus_json()
    data["liveviewdata"]["afframe"][0]["select"] = selection
    assert parse_ccapi_focus_info(data).frames == []


def check_lost_start_or_stop_keeps_stop_only_owner_and_never_represses(camera, lost_start):
    with TestClient(create_app(ccapi_engine=CcapiEngine())) as client:
        path = connect(client, camera)
        camera.drop_press = lost_start
        camera.drop_release = True
        started = client.post(f"{path}/focus/auto/start", json={"ownerId": "gesture"})
        if not lost_start:
            assert started.status_code == 200
        failed = started if lost_start else client.post(f"{path}/focus/auto/stop", json={"ownerId": "gesture"})
        assert failed.status_code == 502
        assert failed.json()["error"]["code"] == "SHUTTER_RELEASE_UNCONFIRMED"
        assert failed.json()["error"]["feature"] == "AUTOFOCUS"
        status = client.get(f"{path}/status").json()
        assert status["heldAutofocusActive"] is None and status["shutterReleaseUnconfirmed"] is True
        for _ in range(2):
            assert client.post(f"{path}/focus/auto/start", json={"ownerId": "gesture"}).status_code == 409
        camera.drop_release = False
        assert client.post(f"{path}/focus/auto/stop", json={"ownerId": "gesture"}).status_code == 200
        assert [body["action"] for _, _, body in camera.commands] == ["start", "stop", "stop"]


class FocusContractTests(unittest.TestCase):
    def setUp(self):
        self.camera = BulbPeer()
        self.camera.mode = "Manual"
        self.camera.dedicated_af = True
        self.camera.drop_press = False
        self.camera.reject_release = False
        self.camera.focus_info_body = packet(focus_json())
        self.temp = TemporaryDirectory()

    def tearDown(self):
        self.camera.close()
        self.temp.cleanup()

    def test_documented_status_kind_and_cropped_geometry(self):
        for kind, name in ((0, "GENERIC"), (0x10, "NORMAL"), (0x20, "FACE"), (0x30, "TRACKING")):
            for status, status_name in enumerate(("STANDBY", "FOCUSED", "UNFOCUSED", "SERVO_OFF", "FOCUSING")):
                with self.subTest(kind=kind, status=status):
                    check_documented_status_kind_and_cropped_geometry(kind, name, status, status_name)

    def test_invalid_status_never_becomes_focused(self):
        for invalid in ("1", 1.5, -1, 256, None, True):
            with self.subTest(status=invalid):
                check_invalid_status_never_becomes_focused(invalid)

    def test_only_exact_selected_frame_is_visible(self):
        for selection in (0, 2, 3, -1, "1", True):
            with self.subTest(selection=selection):
                check_only_exact_selected_frame_is_visible(selection)

    def test_lost_start_keeps_stop_only_owner_and_never_represses(self):
        check_lost_start_or_stop_keeps_stop_only_owner_and_never_represses(self.camera, True)

    def test_lost_stop_keeps_stop_only_owner_and_never_represses(self):
        check_lost_start_or_stop_keeps_stop_only_owner_and_never_represses(self.camera, False)

    def test_stop_that_overtakes_start_rejects_the_late_press(self):
        session = CcapiEngine().open_connection(self.camera.origin)
        with self.assertRaisesRegex(BridgeError, "does not own"):
            session.stop_autofocus("cancelled")
        with self.assertRaisesRegex(BridgeError, "does not own"):
            session.start_autofocus("cancelled")
        assert session.stop_autofocus("cancelled").active is False
        assert self.camera.commands == []
        session.close()

    def test_watchdog_stops_once_and_old_timer_cannot_stop_new_owner(self):
        timers = []

        def timer_factory(delay, callback):
            assert delay == 30.0
            timer = Mock()
            timers.append((timer, callback))
            return timer

        session = CcapiEngine().open_connection(self.camera.origin)
        with patch("open_eos_bridge.ccapi.threading.Timer", side_effect=timer_factory):
            session.start_autofocus("old")
            timers[0][0].start.assert_called_once()
            timers[0][1]()
            assert session.status().held_autofocus_active is False
            session.start_autofocus("new")
            before = list(self.camera.commands)
            timers[0][1]()
            assert self.camera.commands == before and self.camera.active
            self.camera.reject_release = True
            timers[1][1]()
            status = session.status()
            assert status.held_autofocus_active is None and status.shutter_release_unconfirmed is True
            before = list(self.camera.commands)
            timers[1][1]()
            assert self.camera.commands == before
            self.camera.reject_release = False
            session.stop_autofocus("new")
            session.close()
            timers[1][1]()
            assert [body["action"] for _, _, body in self.camera.commands] == [
                "start", "stop", "start", "stop", "stop",
            ]

    def test_failed_explicit_stop_cancels_watchdog_without_automatic_retry(self):
        callbacks = []
        timer = Mock()

        def timer_factory(delay, callback):
            callbacks.append(callback)
            return timer

        session = CcapiEngine().open_connection(self.camera.origin)
        with patch("open_eos_bridge.ccapi.threading.Timer", side_effect=timer_factory):
            session.start_autofocus("gesture")
            self.camera.reject_release = True
            with self.assertRaises(BridgeError):
                session.stop_autofocus("gesture")
            timer.cancel.assert_called_once()
            before = list(self.camera.commands)
            callbacks[0]()
            assert self.camera.commands == before
            self.camera.reject_release = False
            session.close()


    def test_unknown_status_and_kind_never_become_focused(self):
        for raw in (0x81, 0x3F):
            assert parse_ccapi_focus_info(focus_json(raw)).frames[0].status == "UNKNOWN"

    def test_missing_geometry_magnification_and_non_1x(self):
        for key in ("image", "zoom", "afframe"):
            data = focus_json()
            del data["liveviewdata"][key]
            assert parse_ccapi_focus_info(data) is None
        data = focus_json()
        data["liveviewdata"]["zoom"]["magnification"] = 5
        info = parse_ccapi_focus_info(data)
        assert info.frames == [] and info.magnification == 5 and info.image_aspect_ratio == 1.25
        for bad in (0, -1, True, "1", 1.5, float("inf"), 2147483648):
            data["liveviewdata"]["zoom"]["magnification"] = bad
            assert parse_ccapi_focus_info(data) is None

    def test_clips_crop_and_rejects_empty_or_overflowing_geometry(self):
        data = focus_json()
        frame = data["liveviewdata"]["afframe"][0]
        frame.update(x=0, y=0, width=400, height=400)
        assert parse_ccapi_focus_info(data).frames[0].left == 0
        assert parse_ccapi_focus_info(data).frames[0].top == 0
        for x, width in ((2147483647, 2147483647), (500, -1), (500, 0), (2147483648, 1)):
            frame.update(x=x, width=width)
            assert parse_ccapi_focus_info(data).frames == []

    def test_packet_and_frame_count_bounds_are_exact(self):
        good = packet(focus_json())
        for bad in (good[:-1], good + b"x", good[:2] + b"\x00" + good[3:], good[:-1] + b"\x00",
                    b"x" * (MAX_FOCUS_INFO_BYTES + 1), b"\xff\x00\x01\x00\x00\x00\x00\xff\xff"):
            with self.assertRaises(ValueError):
                parse_ccapi_focus_info_packet(bad)
        data = focus_json()
        data["liveviewdata"]["afframe"] = [{}] * 512
        assert parse_ccapi_focus_info(data).frames == []
        data["liveviewdata"]["afframe"].append({})
        with self.assertRaises(ValueError):
            parse_ccapi_focus_info(data)
        for encoding in ("utf-16", "utf-32"):
            body = json.dumps(focus_json()).encode(encoding)
            non_utf8 = b"\xff\x00\x01" + len(body).to_bytes(4, "big") + body + b"\xff\xff"
            with self.assertRaises(ValueError):
                parse_ccapi_focus_info_packet(non_utf8)

    def test_held_af_positive_is_owned_idempotent_and_independent_of_status(self):
        camera = self.camera
        with TestClient(create_app(ccapi_engine=CcapiEngine())) as client:
            path = connect(client, camera)
            assert client.get(f"{path}/capabilities").json()["heldAutofocusSupported"] is True
            camera.drop_status = True
            for _ in range(2):
                response = client.post(f"{path}/focus/auto/start", json={"ownerId": "gesture-1"})
                assert response.status_code == 200, response.text
                assert response.json() == {"ownerId": "gesture-1", "active": True, "stopUnconfirmed": False}
            assert camera.commands == [("POST", AF_PATH, {"action": "start"})]
            camera.drop_status = False
            status = client.get(f"{path}/status").json()
            assert status["heldAutofocusActive"] is True and status["shutterReleaseUnconfirmed"] is False
            before = list(camera.commands)
            for route, payload in (("/capture/still", None), ("/focus/auto", None),
                                   ("/focus/auto/start", {"ownerId": "gesture-2"}),
                                   ("/focus/auto/stop", {"ownerId": "gesture-2"})):
                assert client.post(f"{path}{route}", json=payload).status_code == 409
            assert camera.commands == before
            camera.drop_status = True
            for _ in range(2):
                stop = client.post(f"{path}/focus/auto/stop", json={"ownerId": "gesture-1"})
                assert stop.json() == {"ownerId": "gesture-1", "active": False, "stopUnconfirmed": False}
            assert camera.commands == [("POST", AF_PATH, {"action": "start"}), ("POST", AF_PATH, {"action": "stop"})]
            assert client.post(f"{path}/focus/auto/start", json={"ownerId": "gesture-1"}).status_code == 409
            camera.drop_status = False
            assert client.post(f"{path}/focus/auto/start", json={"ownerId": "gesture-3"}).status_code == 200
            count = len(camera.commands)
            assert client.post(f"{path}/focus/auto/stop", json={"ownerId": "gesture-1"}).status_code == 200
            assert len(camera.commands) == count and camera.active
            assert client.post(f"{path}/focus/auto/stop", json={"ownerId": "gesture-3"}).status_code == 200

    def test_lost_start_with_confirmed_cleanup_cannot_be_replayed(self):
        camera = self.camera
        with TestClient(create_app(ccapi_engine=CcapiEngine())) as client:
            path = connect(client, camera)
            camera.drop_press = True
            assert client.post(f"{path}/focus/auto/start", json={"ownerId": "gesture"}).status_code == 502
            camera.drop_press = False
            assert client.post(f"{path}/focus/auto/start", json={"ownerId": "gesture"}).status_code == 409
            assert client.post(f"{path}/focus/auto/stop", json={"ownerId": "gesture"}).json()["active"] is False
            assert [body["action"] for _, _, body in camera.commands] == ["start", "stop"]

    def test_exact_stop_operation_survives_discovery_changes(self):
        camera = self.camera
        session = CcapiEngine().open_connection(camera.origin)
        session.start_autofocus("gesture")
        session._operations = {CcapiOperation("POST", "/ccapi/ver110/shooting/control/af")}
        session.stop_autofocus("gesture")
        session.close()
        assert camera.commands == [("POST", AF_PATH, {"action": "start"}), ("POST", AF_PATH, {"action": "stop"})]

    def test_disconnect_cleanup_is_bounded_and_old_session_cannot_mutate_new(self):
        camera = self.camera
        with TestClient(create_app(ccapi_engine=CcapiEngine())) as client:
            old = connect(client, camera)
            client.post(f"{old}/focus/auto/start", json={"ownerId": "old"})
            camera.reject_release = True
            deleted = client.delete(old)
            assert deleted.status_code == 502 and deleted.json()["error"]["code"] == "SHUTTER_RELEASE_UNCONFIRMED"
            assert len(camera.commands) == 2
            camera.reject_release = False
            new = connect(client, camera)
            assert client.post(f"{new}/focus/auto/start", json={"ownerId": "new"}).status_code == 200
            before = list(camera.commands)
            assert client.post(f"{old}/focus/auto/stop", json={"ownerId": "old"}).status_code == 404
            assert client.post(f"{new}/focus/auto/stop", json={"ownerId": "old"}).status_code == 409
            assert camera.commands == before
            assert client.post(f"{new}/focus/auto/stop", json={"ownerId": "new"}).status_code == 200

    def test_stop_waits_for_its_own_inflight_start(self):
        camera = self.camera
        session = CcapiEngine().open_connection(camera.origin)
        entered, finish = Event(), Event()
        command = session._command_ok

        def delayed(operation, payload):
            command(operation, payload)
            if payload == {"action": "start"}:
                entered.set()
                assert finish.wait(5)

        with patch.object(session, "_command_ok", side_effect=delayed), ThreadPoolExecutor(max_workers=2) as executor:
            start = executor.submit(session.start_autofocus, "gesture")
            assert entered.wait(5)
            stop = executor.submit(session.stop_autofocus, "gesture")
            assert not stop.done()
            finish.set()
            assert start.result().active is True
            assert stop.result().active is False
        session.close()
        assert [body["action"] for _, _, body in camera.commands] == ["start", "stop"]

    def test_http_focus_info_requires_active_session_and_exposes_only_normalized_metadata(self):
        camera = self.camera
        with TestClient(create_app(ccapi_engine=CcapiEngine())) as client:
            path = connect(client, camera)
            assert client.get(f"{path}/capabilities").json()["focusInfoSupported"] is True
            before = list(camera.reads)
            assert client.get(f"{path}/liveview/focus-info").status_code == 409
            assert camera.reads == before
            assert client.post(f"{path}/liveview/start", json={"source": "CCAPI_JPEG_POLLING"}).status_code == 200
            assert client.post(f"{path}/focus/auto/start", json={"ownerId": "telemetry"}).status_code == 200
            response = client.get(f"{path}/liveview/focus-info")
            assert response.status_code == 200, response.text
            assert response.headers["cache-control"] == "no-store, max-age=0"
            assert response.json() == {"frames": [{"left": 0.1, "top": 0.1, "right": 0.3, "bottom": 0.3,
                                                 "status": "FOCUSED", "kind": "FACE"}],
                                       "magnification": 1, "imageAspectRatio": 1.25}
            assert camera.reads[-1] == "/ccapi/ver100/shooting/liveview/flipdetail?kind=info"
            for payload in (packet({}), packet({"liveviewdata": {"cameraSecret": "synthetic-private"}})):
                camera.focus_info_body = payload
                assert client.get(f"{path}/liveview/focus-info").json() is None
            for payload in (b"synthetic-private", b"x" * (MAX_FOCUS_INFO_BYTES + 1)):
                camera.focus_info_body = payload
                response = client.get(f"{path}/liveview/focus-info")
                assert response.status_code == 502
                assert response.json()["error"]["code"] == "FOCUS_INFO_UNAVAILABLE"
                assert "synthetic-private" not in response.text and camera.origin not in response.text
            assert client.post(f"{path}/liveview/stop").status_code == 200
            before = list(camera.reads)
            assert client.get(f"{path}/liveview/focus-info").status_code == 409
            assert camera.reads == before

    def test_live_view_teardown_releases_owned_af_and_retains_failed_stop(self):
        camera = self.camera
        with TestClient(create_app(ccapi_engine=CcapiEngine())) as client:
            path = connect(client, camera)
            assert client.post(f"{path}/liveview/start", json={"source": "CCAPI_JPEG_POLLING"}).status_code == 200
            assert client.post(f"{path}/focus/auto/start", json={"ownerId": "gesture"}).status_code == 200
            camera.reject_release = True
            stop = client.post(f"{path}/liveview/stop")
            assert stop.status_code == 502 and stop.json()["error"]["code"] == "SHUTTER_RELEASE_UNCONFIRMED"
            assert client.get(f"{path}/liveview/focus-info").status_code == 409
            camera.reject_release = False
            assert client.post(f"{path}/focus/auto/stop", json={"ownerId": "gesture"}).status_code == 200

    def test_new_routes_authenticate_and_validate_owner_before_camera_io(self):
        camera = self.camera
        with TestClient(create_app(ccapi_engine=CcapiEngine(), token="synthetic-token")) as client:
            client.headers["Authorization"] = "Bearer synthetic-token"
            path = connect(client, camera)
            before = (list(camera.commands), list(camera.reads))
            for payload in ({}, {"ownerId": ""}, {"ownerId": "x" * 129}, {"ownerId": "with spaces"},
                            {"ownerId": 1}, {"ownerId": "valid", "unexpected": True}):
                assert client.post(f"{path}/focus/auto/start", json=payload).status_code == 422
            del client.headers["Authorization"]
            assert client.post(f"{path}/focus/auto/start", json={"ownerId": "gesture"}).status_code == 401
            assert client.post(f"{path}/focus/auto/stop", json={"ownerId": "gesture"}).status_code == 401
            assert client.get(f"{path}/liveview/focus-info").status_code == 401
            assert (camera.commands, camera.reads) == before

    def test_missing_dedicated_af_does_not_advertise_or_fallback(self):
        camera = self.camera
        camera.dedicated_af = False
        session = CcapiEngine(sleeper=lambda _: None).open_connection(camera.origin)
        assert session.capabilities().held_autofocus_supported is False
        before = list(camera.commands)
        with self.assertRaisesRegex(BridgeError, "dedicated held AF"):
            session.start_autofocus("gesture")
        assert camera.commands == before
        session.autofocus()
        assert any(body == {"af": True, "action": "half_press"} for _, _, body in camera.commands)
        session.close()

    def test_held_af_requires_post_and_focus_info_requires_startable_live_view(self):
        session = CcapiEngine().open_connection(self.camera.origin)
        session._operations.discard(CcapiOperation("POST", AF_PATH))
        session._operations.update({CcapiOperation("PUT", AF_PATH), CcapiOperation("GET", AF_PATH)})
        assert session.capabilities().held_autofocus_supported is False
        before = list(self.camera.commands)
        with self.assertRaises(BridgeError):
            session.start_autofocus("gesture")
        assert self.camera.commands == before
        session._operations = {op for op in session._operations if not op.path.endswith("/shooting/liveview")}
        assert session.capabilities().focus_info_supported is False
        session.close()

    def test_focus_info_get_uses_short_timeout_and_body_limit(self):
        session = CcapiEngine().open_connection(self.camera.origin)
        session.start_live_view(LiveViewStartRequest(source="CCAPI_JPEG_POLLING"))
        with patch.object(session.transport, "request", wraps=session.transport.request) as request:
            assert session.live_view_focus_info().frames[0].status == "FOCUSED"
            assert request.call_count == 1
            args, kwargs = request.call_args
            assert args == ("GET", f"{self.camera.origin}/ccapi/ver100/shooting/liveview/flipdetail?kind=info")
            assert kwargs["timeout"] == 2.0 and kwargs["max_bytes"] == MAX_FOCUS_INFO_BYTES
            assert kwargs["body"] is None
        session.close()

    def test_local_engine_does_not_claim_or_execute_optional_focus_contract(self):
        tmp_path = Path(self.temp.name)
        runner = FakeRunner()
        with TestClient(create_app(engine=GPhoto2Engine(runner, capture_directory=tmp_path))) as client:
            path = f"/v1/session/{client.post('/v1/session', json={}).json()['id']}"
            capabilities = client.get(f"{path}/capabilities").json()
            assert capabilities["heldAutofocusSupported"] is False and capabilities["focusInfoSupported"] is False
            before = list(runner.commands)
            assert client.post(f"{path}/focus/auto/start", json={"ownerId": "gesture"}).status_code == 409
            assert client.post(f"{path}/focus/auto/stop", json={"ownerId": "gesture"}).status_code == 409
            assert client.get(f"{path}/liveview/focus-info").status_code == 409
            assert runner.commands == before
