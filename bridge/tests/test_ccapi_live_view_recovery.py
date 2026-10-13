from __future__ import annotations

import asyncio
import json
import unittest
from io import BytesIO
from urllib.parse import urlsplit

from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine, CcapiResponse, CcapiStreamResponse
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.models import LiveViewStartRequest
from open_eos_bridge.rtp import RtpError


PREFIX = "/ccapi/ver100/shooting/liveview"
JPEG = b"\xff\xd8synthetic-live-view\xff\xd9"
SDP = b"v=0\nm=video 12000 RTP/AVP 96\na=rtpmap:96 H264/90000\n"
SOURCES = ("CCAPI_JPEG_POLLING", "CCAPI_MULTIPART", "CCAPI_RTP")


class Receiver:
    def __init__(self) -> None:
        self.ready_error: BaseException | None = None
        self.start_error: BaseException | None = None
        self.close_error: Exception | None = None
        self.closed = False
        self.source_url = "rtp://192.0.2.2:12000"

    def set_target_fps(self, _fps: int) -> None:
        pass

    def start(self) -> None:
        if self.start_error:
            raise self.start_error

    def wait_until_ready(self, timeout: float) -> None:
        if self.ready_error:
            raise self.ready_error

    def close(self) -> None:
        self.closed = True
        if self.close_error:
            raise self.close_error


class LiveViewTransport:
    """Synthetic camera protocol responses; production lifecycle logic is not patched."""

    def __init__(self, *, post_stop: bool = False) -> None:
        self.commands: list[tuple[str, str, object]] = []
        self.reads: list[str] = []
        self.post_stop = post_stop
        self.sources = set(SOURCES)
        self.failed_stops: set[str] = set()
        self.start_error: BaseException | None = None
        self.stop_error: BaseException | None = None
        self.bad_frame = False
        self.unsupported_frame_size = False
        self.bad_stream = False
        self.stream_error: BaseException | None = None
        self.stream_close_status = 200
        self.stream_bodies: list[BytesIO] = []
        self.error_body: BytesIO | None = None
        self.closed = False

    def discovery(self, version: str = "ver100") -> dict[str, object]:
        operations = [{"path": "/deviceinformation", "get": True}]
        if self.sources & {"CCAPI_JPEG_POLLING", "CCAPI_MULTIPART"}:
            operations.append({"path": "/shooting/liveview", "post": True, "delete": not self.post_stop})
        if "CCAPI_JPEG_POLLING" in self.sources:
            operations.append({"path": "/shooting/liveview/flip", "get": True})
        if "CCAPI_MULTIPART" in self.sources:
            operations.append({"path": "/shooting/liveview/multipart", "get": True, "delete": True})
        if "CCAPI_RTP" in self.sources:
            operations.extend([
                {"path": "/shooting/liveview/rtp", "post": True},
                {"path": "/shooting/liveview/rtpsessiondesc", "get": True},
            ])
        return {version: operations}

    def request(self, method: str, url: str, *, body=None, **_kwargs) -> CcapiResponse:
        path = urlsplit(url).path
        payload = json.loads(body) if body is not None else None
        if method == "GET":
            self.reads.append(path)
            if path == "/ccapi":
                return self.json_response(self.discovery())
            if path.endswith("/deviceinformation"):
                return self.json_response({"productname": "Synthetic Camera"})
            if path.endswith("/rtpsessiondesc"):
                return CcapiResponse(200, {}, SDP)
            if path.endswith("/flip"):
                if self.unsupported_frame_size:
                    return self.json_response({"message": "Mode not supported"}, status=503)
                return CcapiResponse(200, {"content-type": "image/jpeg"}, b"bad" if self.bad_frame else JPEG)
            return self.json_response({}, status=404)
        self.commands.append((method, path, payload))
        if path.endswith("/multipart"):
            return self.json_response({}, status=self.stream_close_status)
        stopping = method == "DELETE" or (payload or {}).get("liveviewsize") == "off"
        stopping = stopping or (payload or {}).get("action") == "stop"
        if stopping and self.stop_error:
            raise self.stop_error
        if stopping and path in self.failed_stops:
            raise BridgeError("CCAPI_UNREACHABLE", "Synthetic lost stop acknowledgement", status_code=502)
        if not stopping and self.start_error:
            raise self.start_error
        return self.json_response({})

    def open_stream(self, method: str, url: str, **_kwargs) -> CcapiStreamResponse:
        self.reads.append(urlsplit(url).path)
        if self.stream_error:
            raise self.stream_error
        if self.error_body is not None:
            self.stream_bodies.append(self.error_body)
            return CcapiStreamResponse(503, {}, self.error_body)
        content = b"invalid" if self.bad_stream else (
            b"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
            + str(len(JPEG)).encode() + b"\r\n\r\n" + JPEG + b"\r\n--frame--\r\n"
        )
        stream = BytesIO(content)
        self.stream_bodies.append(stream)
        return CcapiStreamResponse(200, {"content-type": "multipart/x-mixed-replace; boundary=frame"}, stream)

    def close(self) -> None:
        self.closed = True

    @staticmethod
    def json_response(payload: object, *, status: int = 200) -> CcapiResponse:
        return CcapiResponse(status, {"content-type": "application/json"}, json.dumps(payload).encode())


class LiveViewStopRecoveryTests(unittest.TestCase):
    def create_session(self, *, post_stop: bool = False, sources=SOURCES):
        transport = LiveViewTransport(post_stop=post_stop)
        transport.sources = set(sources)
        receiver = Receiver()
        session = CcapiEngine(
            transport_factory=lambda _username, _password: transport,
            rtp_session_factory=lambda _description, _destination: receiver,
            route_resolver=lambda _url: "192.0.2.2",
            sleeper=lambda _seconds: None,
        ).open_connection("http://192.0.2.1:8080")
        self.addCleanup(self.close_session, session, transport, receiver)
        return session, transport, receiver

    @staticmethod
    def close_session(session, transport, receiver) -> None:
        transport.failed_stops.clear()
        transport.stop_error = None
        receiver.close_error = None
        session.close()

    @staticmethod
    def stop_path(source: str) -> str:
        return PREFIX + "/rtp" if source == "CCAPI_RTP" else PREFIX

    def assert_stop_error(self, action) -> BridgeError:
        with self.assertRaises(BridgeError) as raised:
            action()
        self.assertEqual(raised.exception.code, "LIVE_VIEW_STOP_UNCONFIRMED")
        return raised.exception

    def test_auto_never_falls_back_after_unconfirmed_rtp_cleanup(self) -> None:
        session, transport, receiver = self.create_session()
        receiver.ready_error = RtpError("Synthetic no decoded frame")
        transport.failed_stops.add(PREFIX + "/rtp")
        self.assert_stop_error(lambda: session.start_live_view(LiveViewStartRequest(source="AUTO")))
        self.assertEqual([path for _, path, _ in transport.commands], [PREFIX + "/rtp"] * 2)
        self.assertTrue(receiver.closed)

    def test_auto_never_falls_back_after_unconfirmed_multipart_cleanup(self) -> None:
        session, transport, _receiver = self.create_session(sources=SOURCES[:2])
        transport.bad_stream = True
        transport.failed_stops.add(PREFIX)
        self.assert_stop_error(lambda: session.start_live_view(LiveViewStartRequest(source="AUTO")))
        self.assertEqual([method for method, path, _ in transport.commands if path == PREFIX], ["POST", "DELETE"])
        self.assertTrue(all(body.closed for body in transport.stream_bodies))

    def test_start_ack_loss_preserves_stop_before_dispatch_for_every_source(self) -> None:
        for source in SOURCES:
            with self.subTest(source=source):
                session, transport, _receiver = self.create_session()
                transport.start_error = BridgeError("CCAPI_UNREACHABLE", "Synthetic lost start acknowledgement")
                transport.failed_stops.add(self.stop_path(source))
                self.assert_stop_error(lambda: session.start_live_view(LiveViewStartRequest(source=source)))
                self.assertEqual(len([path for _, path, _ in transport.commands if path == self.stop_path(source)]), 2)
                before = list(transport.commands)
                self.assert_stop_error(lambda: session.start_live_view(LiveViewStartRequest(source="AUTO")))
                self.assertEqual(transport.commands, before)
                transport.failed_stops.clear()
                session.stop_live_view()
                self.assertEqual(len(transport.commands), len(before) + (2 if source == "CCAPI_MULTIPART" else 1))

    def test_failed_stop_retries_original_operation_after_rediscovery(self) -> None:
        for post_stop in (False, True):
            for source in SOURCES:
                with self.subTest(source=source, post_stop=post_stop):
                    session, transport, receiver = self.create_session(post_stop=post_stop)
                    session.start_live_view(LiveViewStartRequest(source=source))
                    transport.failed_stops.add(self.stop_path(source))
                    self.assert_stop_error(session.stop_live_view)
                    failed = list(transport.commands)
                    session._parse_discovery(transport.discovery("ver200"), source="synthetic rediscovery")
                    self.assert_stop_error(session.stop_live_view)
                    retry = transport.commands[len(failed):]
                    self.assertEqual(retry, failed[-len(retry):])
                    self.assertEqual(retry[-1][1], self.stop_path(source))
                    self.assertFalse(session.live_view_active)
                    if source == "CCAPI_RTP":
                        self.assertTrue(receiver.closed)
                    if source == "CCAPI_MULTIPART":
                        self.assertTrue(all(body.closed for body in transport.stream_bodies))
                    transport.failed_stops.clear()
                    session.stop_live_view()
                    before = list(transport.commands)
                    session.stop_live_view()
                    self.assertEqual(transport.commands, before)

    def test_failed_jpeg_size_cleanup_never_restarts_or_retries_stop_implicitly(self) -> None:
        session, transport, _receiver = self.create_session()
        transport.unsupported_frame_size = True
        transport.failed_stops.add(PREFIX)
        self.assert_stop_error(lambda: session.start_live_view(LiveViewStartRequest(source=SOURCES[0])))
        self.assertEqual(transport.commands, [
            ("POST", PREFIX, {"cameradisplay": "on", "liveviewsize": "medium"}),
            ("DELETE", PREFIX, None),
        ])

    def test_close_retries_failed_start_stop_once_and_does_not_transfer_it(self) -> None:
        for source in SOURCES:
            with self.subTest(source=source):
                session, transport, _receiver = self.create_session()
                transport.start_error = BridgeError("CCAPI_UNREACHABLE", "Synthetic lost start acknowledgement")
                transport.failed_stops.add(self.stop_path(source))
                self.assert_stop_error(lambda: session.start_live_view(LiveViewStartRequest(source=source)))
                before = len(transport.commands)
                self.assert_stop_error(session.close)
                self.assertTrue(transport.closed)
                self.assertEqual(len(transport.commands), before + (2 if source == "CCAPI_MULTIPART" else 1))
                session.close()
                new_session, new_transport, _ = self.create_session()
                new_session.stop_live_view()
                self.assertEqual(new_transport.commands, [])

    def test_successful_cleanup_allows_auto_fallback(self) -> None:
        session, transport, receiver = self.create_session()
        receiver.ready_error = RtpError("Synthetic no frame")
        transport.bad_stream = True
        session.start_live_view(LiveViewStartRequest(source="AUTO"))
        self.assertEqual(session.live_view_source, "CCAPI_JPEG_POLLING")
        self.assertEqual([path for _, path, _ in transport.commands], [
            PREFIX + "/rtp", PREFIX + "/rtp", PREFIX, PREFIX + "/multipart", PREFIX, PREFIX,
        ])

    def test_local_rtp_prepare_failure_has_no_remote_stop_obligation(self) -> None:
        session, transport, receiver = self.create_session()
        receiver.start_error = RtpError("Synthetic UDP bind failure")
        session.start_live_view(LiveViewStartRequest(source="AUTO"))
        self.assertTrue(receiver.closed)
        self.assertEqual(session.live_view_source, "CCAPI_MULTIPART")
        self.assertFalse(any(path.endswith("/rtp") for _, path, _ in transport.commands))

    def test_unsupported_start_and_idle_stop_send_no_camera_command(self) -> None:
        session, transport, _receiver = self.create_session(sources=())
        with self.assertRaises(BridgeError):
            session.start_live_view(LiveViewStartRequest(source="AUTO"))
        session.stop_live_view()
        session.close()
        self.assertEqual(transport.commands, [])

    def test_cancellation_cleans_up_without_auto_fallback(self) -> None:
        for source in SOURCES:
            with self.subTest(source=source):
                session, transport, receiver = self.create_session(sources=(source,))
                transport.start_error = asyncio.CancelledError()
                with self.assertRaises(asyncio.CancelledError):
                    session.start_live_view(LiveViewStartRequest(source="AUTO"))
                self.assertEqual(transport.commands[-1][1], self.stop_path(source))
                self.assertTrue(transport.commands[-1][0] == "DELETE" or
                                transport.commands[-1][2] == {"action": "stop", "ipaddress": ""})
                if source == "CCAPI_RTP":
                    self.assertTrue(receiver.closed)
                before = list(transport.commands)
                session.stop_live_view()
                self.assertEqual(transport.commands, before)

    def test_failed_stop_status_is_distinct_from_active_video_and_clears_only_on_ack(self) -> None:
        for source in SOURCES:
            with self.subTest(source=source):
                session, transport, _receiver = self.create_session()
                session.start_live_view(LiveViewStartRequest(source=source))
                self.assertFalse(session.status().model_dump(by_alias=True)["liveViewStopUnconfirmed"])
                transport.failed_stops.add(self.stop_path(source))
                self.assert_stop_error(session.stop_live_view)
                self.assertTrue(session.status().model_dump(by_alias=True)["liveViewStopUnconfirmed"])
                self.assertTrue(session.live_view_stop_unconfirmed)
                self.assertEqual(session.live_view_source, source)
                with self.assertRaises(BridgeError) as frame_error:
                    session.live_view_frame()
                self.assertEqual(frame_error.exception.code, "LIVE_VIEW_NOT_STARTED")
                before = list(transport.commands)
                for requested in (*SOURCES, "AUTO"):
                    self.assert_stop_error(lambda: session.start_live_view(LiveViewStartRequest(source=requested)))
                self.assert_stop_error(session.sleep_camera)
                self.assert_stop_error(lambda: session.clean_sensor(False))
                self.assertEqual(transport.commands, before)
                transport.failed_stops.clear()
                session.stop_live_view()
                self.assertFalse(session.status().model_dump(by_alias=True)["liveViewStopUnconfirmed"])
                self.assertIsNone(session.live_view_source)
                session.start_live_view(LiveViewStartRequest(source=source))
                self.assertTrue(session.live_view_active)

    def test_healthy_active_start_requires_explicit_stop_without_mutations(self) -> None:
        for source in SOURCES:
            with self.subTest(source=source):
                session, transport, _receiver = self.create_session()
                session.start_live_view(LiveViewStartRequest(source=source))
                before = list(transport.commands)
                with self.assertRaises(BridgeError) as error:
                    session.start_live_view(LiveViewStartRequest(source="AUTO", fps=30, size="LARGE"))
                self.assertEqual(error.exception.code, "LIVE_VIEW_STOP_REQUIRED")
                self.assertTrue(session.live_view_active)
                self.assertFalse(session.live_view_stop_unconfirmed)
                self.assertEqual(transport.commands, before)

    def test_successful_compensation_for_start_ack_loss_clears_owner(self) -> None:
        for source in SOURCES:
            with self.subTest(source=source):
                session, transport, _receiver = self.create_session()
                transport.start_error = BridgeError("CCAPI_UNREACHABLE", "Synthetic lost start acknowledgement")
                with self.assertRaises(BridgeError) as error:
                    session.start_live_view(LiveViewStartRequest(source=source))
                self.assertEqual(error.exception.code, "CCAPI_UNREACHABLE")
                self.assertFalse(session.live_view_active)
                self.assertFalse(session.live_view_stop_unconfirmed)
                before = list(transport.commands)
                session.stop_live_view()
                self.assertEqual(transport.commands, before)
                transport.start_error = None
                session.start_live_view(LiveViewStartRequest(source=source))
                self.assertTrue(session.live_view_active)

    def test_cancelled_start_with_failed_cleanup_keeps_stop_and_never_falls_back(self) -> None:
        for source in SOURCES:
            with self.subTest(source=source):
                session, transport, receiver = self.create_session(sources=(source,))
                transport.start_error = asyncio.CancelledError()
                transport.failed_stops.add(self.stop_path(source))
                with self.assertRaises(asyncio.CancelledError):
                    session.start_live_view(LiveViewStartRequest(source="AUTO"))
                self.assertTrue(session.live_view_stop_unconfirmed)
                self.assertEqual(session.live_view_source, source)
                self.assertFalse(session.live_view_active)
                before = list(transport.commands)
                self.assert_stop_error(lambda: session.start_live_view(LiveViewStartRequest(source="AUTO")))
                self.assertEqual(transport.commands, before)
                if source == "CCAPI_RTP":
                    self.assertTrue(receiver.closed)
                transport.failed_stops.clear()
                session.stop_live_view()
                self.assertFalse(session.live_view_stop_unconfirmed)

    def test_cancelled_explicit_stop_keeps_original_stop_for_retry(self) -> None:
        for source in SOURCES:
            with self.subTest(source=source):
                session, transport, _receiver = self.create_session()
                session.start_live_view(LiveViewStartRequest(source=source))
                transport.stop_error = asyncio.CancelledError()
                with self.assertRaises(asyncio.CancelledError):
                    session.stop_live_view()
                self.assertTrue(session.live_view_stop_unconfirmed)
                stop = transport.commands[-1]
                transport.stop_error = None
                session.stop_live_view()
                self.assertEqual(transport.commands[-1], stop)
                self.assertFalse(session.live_view_stop_unconfirmed)

    def test_cancelled_rtp_preparation_has_no_remote_stop_obligation(self) -> None:
        session, transport, receiver = self.create_session()
        receiver.start_error = asyncio.CancelledError()
        with self.assertRaises(asyncio.CancelledError):
            session.start_live_view(LiveViewStartRequest(source="AUTO"))
        self.assertTrue(receiver.closed)
        session.stop_live_view()
        self.assertEqual(transport.commands, [])

    def test_receiver_close_failure_still_attempts_camera_stop(self) -> None:
        session, transport, receiver = self.create_session()
        session.start_live_view(LiveViewStartRequest(source="CCAPI_RTP"))
        receiver.close_error = OSError("Synthetic receiver close failure")
        session.stop_live_view()
        self.assertEqual(transport.commands[-1], ("POST", PREFIX + "/rtp", {"action": "stop", "ipaddress": ""}))
        self.assertFalse(session.live_view_stop_unconfirmed)
        self.assertIsNone(session._rtp_session)

    def test_multipart_error_body_is_closed_even_when_read_is_cancelled(self) -> None:
        class CancelledBody(BytesIO):
            def read(self, size=-1):
                raise asyncio.CancelledError()

        session, transport, _receiver = self.create_session(sources=SOURCES[:2])
        transport.error_body = CancelledBody()
        with self.assertRaises(asyncio.CancelledError):
            session.start_live_view(LiveViewStartRequest(source="AUTO"))
        self.assertTrue(transport.error_body.closed)
        self.assertEqual(transport.commands[-1], ("DELETE", PREFIX, None))
        self.assertFalse(session.live_view_stop_unconfirmed)

    def test_multipart_stream_delete_failure_does_not_hide_acknowledged_general_stop(self) -> None:
        session, transport, _receiver = self.create_session(post_stop=True)
        session.start_live_view(LiveViewStartRequest(source="CCAPI_MULTIPART"))
        transport.stream_close_status = 503
        session.stop_live_view()
        self.assertEqual(transport.commands[-2:], [
            ("DELETE", PREFIX + "/multipart", None),
            ("POST", PREFIX, {"cameradisplay": "on", "liveviewsize": "off"}),
        ])
        self.assertFalse(session.live_view_stop_unconfirmed)
        self.assertTrue(all(body.closed for body in transport.stream_bodies))

    def test_first_jpeg_frame_failure_keeps_original_error_after_successful_cleanup(self) -> None:
        session, transport, _receiver = self.create_session()
        transport.bad_frame = True
        with self.assertRaises(BridgeError) as error:
            session.start_live_view(LiveViewStartRequest(source="CCAPI_JPEG_POLLING"))
        self.assertEqual(error.exception.code, "INVALID_LIVE_VIEW_FRAME")
        self.assertFalse(session.live_view_stop_unconfirmed)
        self.assertEqual(transport.commands[-1], ("DELETE", PREFIX, None))


class LiveViewRecoveryRouteTests(unittest.TestCase):
    def setUp(self) -> None:
        self.transports: list[LiveViewTransport] = []

        def transport_factory(_username, _password):
            transport = LiveViewTransport()
            self.transports.append(transport)
            return transport

        self.client = TestClient(create_app(ccapi_engine=CcapiEngine(
            transport_factory=transport_factory,
            rtp_session_factory=lambda _description, _destination: Receiver(),
            route_resolver=lambda _url: "192.0.2.2",
            sleeper=lambda _seconds: None,
        )))
        self.client.__enter__()
        self.connect()

    def connect(self) -> None:
        created = self.client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": "http://192.0.2.1:8080"})
        self.assertEqual(created.status_code, 201, created.text)
        self.session_path = "/v1/session/" + created.json()["id"]
        self.transport = self.transports[-1]

    def tearDown(self) -> None:
        for transport in self.transports:
            transport.failed_stops.clear()
        self.client.__exit__(None, None, None)

    def test_lost_start_ack_has_visible_stop_only_recovery_with_explicit_ack(self) -> None:
        self.transport.start_error = BridgeError("CCAPI_UNREACHABLE", "Synthetic lost start acknowledgement")
        self.transport.failed_stops.add(PREFIX)
        started = self.client.post(self.session_path + "/liveview/start", json={"source": "CCAPI_JPEG_POLLING"})
        self.assertEqual(started.status_code, 409)
        self.assertEqual(started.json()["error"]["code"], "LIVE_VIEW_STOP_UNCONFIRMED")
        status = self.client.get(self.session_path + "/status")
        self.assertIs(status.json()["liveViewStopUnconfirmed"], True)
        self.assertEqual(self.client.post(self.session_path + "/liveview/stop").status_code, 409)
        before = list(self.transport.commands)
        blocked = self.client.post(self.session_path + "/liveview/start", json={"source": "AUTO"})
        self.assertEqual(blocked.json()["error"]["code"], "LIVE_VIEW_STOP_UNCONFIRMED")
        self.assertEqual(self.transport.commands, before)
        self.transport.failed_stops.clear()
        stopped = self.client.post(self.session_path + "/liveview/stop")
        self.assertEqual(stopped.status_code, 200)
        self.assertIs(stopped.json()["active"], False)
        self.assertIs(stopped.json()["liveViewStopUnconfirmed"], False)
        self.assertIs(self.client.get(self.session_path + "/status").json()["liveViewStopUnconfirmed"], False)
        self.assertEqual(self.transport.commands[-1], ("DELETE", PREFIX, None))

    def test_disconnect_reports_unconfirmed_stop_and_does_not_move_it_to_new_session(self) -> None:
        started = self.client.post(self.session_path + "/liveview/start", json={"source": "CCAPI_RTP"})
        self.assertEqual(started.status_code, 200)
        old_path, old_transport = self.session_path, self.transport
        old_transport.failed_stops.add(PREFIX + "/rtp")
        disconnected = self.client.delete(old_path)
        self.assertEqual(disconnected.status_code, 409)
        self.assertEqual(disconnected.json()["error"]["code"], "LIVE_VIEW_STOP_UNCONFIRMED")
        self.assertIn("camera", disconnected.json()["error"]["message"])
        self.assertTrue(old_transport.closed)
        self.connect()
        before = list(old_transport.commands)
        stale = self.client.post(old_path + "/liveview/stop")
        self.assertEqual(stale.status_code, 404)
        self.assertEqual(self.transport.commands, [])
        self.assertEqual(old_transport.commands, before)
        stopped = self.client.post(self.session_path + "/liveview/stop")
        self.assertIs(stopped.json()["liveViewStopUnconfirmed"], False)
        self.assertEqual(self.transport.commands, [])


if __name__ == "__main__":
    unittest.main()
