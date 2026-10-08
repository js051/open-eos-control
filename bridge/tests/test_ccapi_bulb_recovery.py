from __future__ import annotations

import unittest
from unittest.mock import Mock

from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine, CcapiOperation
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.models import CameraDescriptor, CameraStatus
from open_eos_bridge.sessions import SessionManager

from .ccapi_bulb_peer import AF_PATH, DIRECT_PATH, MANUAL_PATH, BulbPeer


class ShortControlRecoveryTests(unittest.TestCase):
    """Production ASGI routes and urllib transport, with an independent TCP camera peer."""

    def setUp(self) -> None:
        self.peer = BulbPeer()
        self.peer.mode = "Manual"
        self.peer.direct_shutter = False
        self.peer.dedicated_af = True
        self.peer.drop_press = False
        self.client = TestClient(create_app(ccapi_engine=CcapiEngine(sleeper=lambda _: None)))
        self.client.__enter__()
        self.connect()

    def connect(self) -> None:
        created = self.client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": self.peer.origin})
        self.assertEqual(created.status_code, 201, created.text)
        self.session_path = f"/v1/session/{created.json()['id']}"

    def tearDown(self) -> None:
        self.peer.reject_release = False
        self.peer.drop_release = False
        self.peer.drop_status = False
        self.client.__exit__(None, None, None)
        self.peer.close()

    def test_failed_short_control_release_blocks_writes_and_retries_only_original_stop(self) -> None:
        for route, operation, press, release, feature in (
            ("/capture/still", MANUAL_PATH, {"af": False, "action": "full_press"},
             {"af": False, "action": "release"}, "STILL_CAPTURE"),
            ("/shutter/half-press", MANUAL_PATH, {"af": True, "action": "half_press"},
             {"af": False, "action": "release"}, "SHUTTER_HALF_PRESS"),
            ("/focus/auto", AF_PATH, {"action": "start"}, {"action": "stop"}, "AUTOFOCUS"),
        ):
            with self.subTest(route=route):
                self.peer.reject_release = True
                start = len(self.peer.commands)
                response = self.client.post(f"{self.session_path}{route}", json={"af": False})
                self.assertEqual(response.status_code, 502, response.text)
                self.assertEqual(response.json()["error"]["code"], "SHUTTER_RELEASE_UNCONFIRMED")
                self.assertEqual(response.json()["error"]["feature"], feature)
                self.assertTrue(self.client.get(f"{self.session_path}/status").json()["shutterReleaseUnconfirmed"])
                self.assertEqual(self.client.post(f"{self.session_path}/capture/still").status_code, 409)
                self.assertEqual(len(self.peer.commands), start + 2)
                self.peer.reject_release = False
                stopped = self.client.post(f"{self.session_path}/bulb/stop")
                self.assertEqual(stopped.status_code, 200, stopped.text)
                self.assertFalse(stopped.json()["shutterReleaseUnconfirmed"])
                method = "POST" if operation == AF_PATH else "PUT"
                self.assertEqual(self.peer.commands[start:], [
                    (method, operation, press), (method, operation, release), (method, operation, release),
                ])
                self.assertFalse(self.peer.active)
                observed = self.client.get(f"{self.session_path}/capabilities").json()["evidence"]["observedFeatures"]
                self.assertNotIn("BULB_EXPOSURE", observed)

    def test_post_only_advertised_manual_short_control_uses_post_for_every_release(self) -> None:
        self.client.delete(self.session_path)
        self.peer.manual_method = "POST"
        self.connect()
        failed = self.client.post(f"{self.session_path}/shutter/half-press")
        self.assertEqual(failed.json()["error"]["feature"], "SHUTTER_HALF_PRESS")
        self.peer.reject_release = False
        self.assertEqual(self.client.post(f"{self.session_path}/bulb/stop").status_code, 200)
        self.assertEqual(self.peer.commands, [
            ("POST", MANUAL_PATH, {"af": True, "action": "half_press"}),
            ("POST", MANUAL_PATH, {"af": False, "action": "release"}),
            ("POST", MANUAL_PATH, {"af": False, "action": "release"}),
        ])

    def test_dedicated_af_recovery_does_not_require_or_observe_bulb_capability(self) -> None:
        self.client.delete(self.session_path)
        self.peer.manual_method = None
        self.connect()
        response = self.client.post(f"{self.session_path}/focus/auto")
        self.assertEqual(response.json()["error"]["feature"], "AUTOFOCUS")
        self.peer.reject_release = False
        self.assertEqual(self.client.post(f"{self.session_path}/bulb/stop").status_code, 200)
        capabilities = self.client.get(f"{self.session_path}/capabilities").json()
        self.assertNotIn("BULB_EXPOSURE", capabilities["supported"])
        self.assertNotIn("BULB_EXPOSURE", capabilities["evidence"]["observedFeatures"])
        self.assertEqual(self.peer.commands, [
            ("POST", AF_PATH, {"action": "start"}),
            ("POST", AF_PATH, {"action": "stop"}),
            ("POST", AF_PATH, {"action": "stop"}),
        ])

    def test_pending_short_stop_blocks_conflicting_writes_but_allows_diagnosis_and_safe_stops(self) -> None:
        self.client.post(f"{self.session_path}/focus/auto")
        before = list(self.peer.commands)
        for route, payload in (
            ("/bulb/start", None), ("/capture/still", None), ("/shutter/half-press", None),
            ("/focus/auto", None), ("/recording/start", None), ("/liveview/start", {}),
            ("/settings/shootingmode", {"value": "Bulb"}),
        ):
            with self.subTest(route=route):
                response = self.client.post(f"{self.session_path}{route}", json=payload)
                self.assertEqual(response.status_code, 409, response.text)
                self.assertEqual(response.json()["error"]["feature"], "AUTOFOCUS")
        self.assertEqual(self.peer.commands, before)
        for route in ("/info", "/status", "/capabilities"):
            self.assertEqual(self.client.get(f"{self.session_path}{route}").status_code, 200)
        self.assertEqual(self.client.post(f"{self.session_path}/recording/stop").status_code, 200)
        self.assertEqual(self.client.post(f"{self.session_path}/liveview/stop").status_code, 200)
        self.assertEqual(self.client.delete(f"{self.session_path}/events").status_code, 204)
        self.assertTrue(self.client.get(f"{self.session_path}/status").json()["shutterReleaseUnconfirmed"])
        self.assertEqual(self.peer.commands[-1][2], {"action": "stop"})

    def test_short_control_disconnect_reports_failed_stop_and_does_not_transfer_it(self) -> None:
        self.client.post(f"{self.session_path}/focus/auto")
        response = self.client.delete(self.session_path)
        self.assertEqual(response.status_code, 502, response.text)
        self.assertEqual(response.json()["error"]["code"], "SHUTTER_RELEASE_UNCONFIRMED")
        self.assertIn("camera", response.json()["error"]["message"].lower())
        commands = list(self.peer.commands)
        self.connect()
        self.assertEqual(self.client.post(f"{self.session_path}/bulb/stop").status_code, 200)
        self.assertEqual(self.peer.commands, commands)

    def test_lost_press_and_lost_release_keep_the_original_short_control_stop(self) -> None:
        self.peer.drop_press = True
        self.peer.reject_release = False
        self.peer.drop_release = True
        response = self.client.post(f"{self.session_path}/focus/auto")
        self.assertEqual(response.json()["error"]["code"], "SHUTTER_RELEASE_UNCONFIRMED")
        self.assertFalse(self.peer.active, "Applied stop without its ACK remains an uncertain client result")
        for _ in range(2):
            response = self.client.post(f"{self.session_path}/bulb/stop")
            self.assertEqual(response.json()["error"]["code"], "SHUTTER_RELEASE_UNCONFIRMED")
            self.assertEqual(self.client.post(f"{self.session_path}/focus/auto").status_code, 409)
        self.peer.drop_release = False
        self.assertEqual(self.client.post(f"{self.session_path}/bulb/stop").status_code, 200)
        count = len(self.peer.commands)
        self.assertEqual(self.client.post(f"{self.session_path}/bulb/stop").status_code, 200)
        self.assertEqual(len(self.peer.commands), count)
        self.assertEqual([body["action"] for _, _, body in self.peer.commands], ["start"] + ["stop"] * 4)

    def test_short_control_retry_ack_is_not_lost_when_its_status_read_fails(self) -> None:
        self.client.post(f"{self.session_path}/shutter/half-press")
        self.peer.reject_release = False
        self.peer.status_failure_after_release = True
        self.assertEqual(self.client.post(f"{self.session_path}/bulb/stop").status_code, 502)
        count = len(self.peer.commands)
        status = self.client.get(f"{self.session_path}/status").json()
        self.assertFalse(status["shutterReleaseUnconfirmed"])
        self.assertFalse(status["bulbExposureActive"])
        self.assertEqual(self.client.post(f"{self.session_path}/bulb/stop").status_code, 200)
        self.assertEqual(len(self.peer.commands), count)

    def test_short_control_close_retries_saved_stop_once_without_a_new_start(self) -> None:
        self.client.post(f"{self.session_path}/focus/auto")
        self.peer.reject_release = False
        self.assertEqual(self.client.delete(self.session_path).status_code, 204)
        self.assertEqual(self.peer.commands, [
            ("POST", AF_PATH, {"action": "start"}),
            ("POST", AF_PATH, {"action": "stop"}),
            ("POST", AF_PATH, {"action": "stop"}),
        ])

    def test_short_control_stop_preserves_original_method_path_and_payload_after_discovery_changes(self) -> None:
        session = CcapiEngine(sleeper=lambda _: None).open_connection(self.peer.origin)
        session._operations.discard(CcapiOperation("PUT", MANUAL_PATH))
        session._operations.add(CcapiOperation("POST", MANUAL_PATH))
        with self.assertRaises(BridgeError):
            session.half_press_shutter()
        session._operations = {CcapiOperation("PUT", "/ccapi/ver110/shooting/control/shutterbutton/manual")}
        self.peer.reject_release = False
        session.stop_bulb_exposure()
        session.close()
        self.assertEqual(self.peer.commands, [
            ("POST", MANUAL_PATH, {"af": True, "action": "half_press"}),
            ("POST", MANUAL_PATH, {"af": False, "action": "release"}),
            ("POST", MANUAL_PATH, {"af": False, "action": "release"}),
        ])

    def test_autofocus_manual_fallback_retains_release_instead_of_sending_af_stop(self) -> None:
        self.client.delete(self.session_path)
        self.peer.dedicated_af = False
        self.connect()
        response = self.client.post(f"{self.session_path}/focus/auto")
        self.assertEqual(response.json()["error"]["code"], "SHUTTER_RELEASE_UNCONFIRMED")
        self.assertEqual(response.json()["error"]["feature"], "AUTOFOCUS")
        self.peer.reject_release = False
        self.assertEqual(self.client.post(f"{self.session_path}/bulb/stop").status_code, 200)
        self.assertEqual(self.peer.commands, [
            ("PUT", MANUAL_PATH, {"af": True, "action": "half_press"}),
            ("PUT", MANUAL_PATH, {"af": False, "action": "release"}),
            ("PUT", MANUAL_PATH, {"af": False, "action": "release"}),
        ])

    def test_acknowledged_manual_capture_readback_failure_has_specific_recoverable_code(self) -> None:
        self.peer.reject_release = False
        self.peer.status_failure_after_release = True
        response = self.client.post(f"{self.session_path}/capture/still", json={"af": False})
        self.assertEqual(response.status_code, 502, response.text)
        self.assertEqual(response.json()["error"]["code"], "CAPTURE_STATUS_READBACK_FAILED")
        self.assertFalse(self.peer.active)
        self.assertEqual([body["action"] for _, _, body in self.peer.commands], ["full_press", "release"])

    def test_unacknowledged_manual_capture_never_claims_readback_only_failure(self) -> None:
        self.peer.reject_release = False
        self.peer.drop_press = True
        response = self.client.post(f"{self.session_path}/capture/still", json={"af": False})
        self.assertEqual(response.status_code, 502, response.text)
        self.assertEqual(response.json()["error"]["code"], "CCAPI_UNREACHABLE")
        self.assertFalse(self.peer.active)

    def test_direct_capture_readback_code_requires_command_acknowledgement(self) -> None:
        self.client.delete(self.session_path)
        self.peer.direct_shutter = True
        self.connect()
        self.peer.status_failure_after_release = True
        response = self.client.post(f"{self.session_path}/capture/still", json={"af": False})
        self.assertEqual(response.json()["error"]["code"], "CAPTURE_STATUS_READBACK_FAILED")
        self.peer.drop_press = True
        response = self.client.post(f"{self.session_path}/capture/still", json={"af": False})
        self.assertEqual(response.json()["error"]["code"], "CCAPI_UNREACHABLE")
        self.assertEqual(self.peer.commands, [("POST", DIRECT_PATH, {"af": False})] * 2)


class BulbRecoveryTests(unittest.TestCase):
    def setUp(self) -> None:
        self.peer = BulbPeer()
        self.client = TestClient(create_app(ccapi_engine=CcapiEngine(sleeper=lambda _: None)))
        self.client.__enter__()
        created = self.client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": self.peer.origin})
        self.assertEqual(created.status_code, 201, created.text)
        self.session_path = f"/v1/session/{created.json()['id']}"

    def tearDown(self) -> None:
        self.peer.reject_release = False
        self.peer.drop_release = False
        self.peer.drop_status = False
        self.client.__exit__(None, None, None)
        self.peer.close()

    def test_lost_press_and_rejected_cleanup_retains_visible_stop_obligation(self) -> None:
        started = self.client.post(f"{self.session_path}/bulb/start")
        self.assertEqual(started.status_code, 502)
        self.assertTrue(self.peer.active)
        self.assertEqual(started.json()["error"]["code"], "SHUTTER_RELEASE_UNCONFIRMED")
        status = self.client.get(f"{self.session_path}/status").json()
        self.assertIsNone(status["bulbExposureActive"])
        self.assertTrue(status["shutterReleaseUnconfirmed"])

    def test_explicit_stop_retries_exact_release_without_resending_press(self) -> None:
        self.client.post(f"{self.session_path}/bulb/start")
        self.peer.reject_release = False
        stopped = self.client.post(f"{self.session_path}/bulb/stop")
        self.assertEqual(stopped.status_code, 200, stopped.text)
        self.assertEqual(self.peer.commands, [
            ("PUT", MANUAL_PATH, {"af": False, "action": "full_press"}),
            ("PUT", MANUAL_PATH, {"af": False, "action": "release"}),
            ("PUT", MANUAL_PATH, {"af": False, "action": "release"}),
        ])
        self.assertFalse(self.peer.active)

    def test_new_start_is_blocked_while_release_is_unconfirmed(self) -> None:
        self.client.post(f"{self.session_path}/bulb/start")
        command_count = len(self.peer.commands)
        retry = self.client.post(f"{self.session_path}/bulb/start")
        self.assertEqual(retry.status_code, 409, retry.text)
        self.assertEqual(len(self.peer.commands), command_count)

    def test_disconnect_reports_failed_release_instead_of_success(self) -> None:
        self.client.post(f"{self.session_path}/bulb/start")
        disconnected = self.client.delete(self.session_path)
        self.assertEqual(disconnected.status_code, 502, disconnected.text)
        self.assertEqual(disconnected.json()["error"]["code"], "SHUTTER_RELEASE_UNCONFIRMED")
        self.assertIn("camera", disconnected.json()["error"]["message"].lower())

    def test_lost_release_reply_keeps_stop_only_even_when_peer_applied_release(self) -> None:
        self.peer.drop_press = False
        self.client.post(f"{self.session_path}/bulb/start")
        self.peer.reject_release = False
        self.peer.drop_release = True
        response = self.client.post(f"{self.session_path}/bulb/stop")
        self.assertEqual(response.json()["error"]["code"], "SHUTTER_RELEASE_UNCONFIRMED")
        self.assertFalse(self.peer.active)
        self.assertTrue(self.client.get(f"{self.session_path}/status").json()["shutterReleaseUnconfirmed"])
        self.assertEqual(self.client.post(f"{self.session_path}/bulb/start").status_code, 409)
        self.peer.drop_release = False
        self.assertEqual(self.client.post(f"{self.session_path}/bulb/stop").status_code, 200)
        self.assertEqual([body["action"] for _, _, body in self.peer.commands], ["full_press", "release", "release"])

    def test_post_only_advertisement_releases_with_original_http_method(self) -> None:
        session = CcapiEngine(sleeper=lambda _: None).open_connection(self.peer.origin)
        session._operations.discard(CcapiOperation("PUT", MANUAL_PATH))
        session._operations.add(CcapiOperation("POST", MANUAL_PATH))
        with self.assertRaises(BridgeError):
            session.start_bulb_exposure()
        self.peer.reject_release = False
        session.stop_bulb_exposure()
        session.close()
        self.assertEqual([method for method, _, _ in self.peer.commands], ["POST", "POST", "POST"])

    def test_partial_press_reply_is_also_unknown(self) -> None:
        self.peer.partial_press_response = True
        response = self.client.post(f"{self.session_path}/bulb/start")
        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json()["error"]["code"], "SHUTTER_RELEASE_UNCONFIRMED")
        self.assertTrue(self.peer.active)
        self.assertEqual(len(self.peer.commands), 2)

    def test_compensated_failed_start_does_not_keep_a_stop_obligation(self) -> None:
        self.peer.reject_release = False
        response = self.client.post(f"{self.session_path}/bulb/start")
        self.assertEqual(response.json()["error"]["code"], "CCAPI_UNREACHABLE")
        status = self.client.get(f"{self.session_path}/status").json()
        self.assertFalse(status["shutterReleaseUnconfirmed"])
        self.assertFalse(status["bulbExposureActive"])
        count = len(self.peer.commands)
        self.assertEqual(self.client.post(f"{self.session_path}/bulb/stop").status_code, 200)
        self.assertEqual(len(self.peer.commands), count)

    def test_repeated_stop_failure_preserves_obligation_and_success_clears_once(self) -> None:
        self.client.post(f"{self.session_path}/bulb/start")
        for _ in range(2):
            response = self.client.post(f"{self.session_path}/bulb/stop")
            self.assertEqual(response.json()["error"]["code"], "SHUTTER_RELEASE_UNCONFIRMED")
            self.assertTrue(self.client.get(f"{self.session_path}/status").json()["shutterReleaseUnconfirmed"])
        self.peer.reject_release = False
        stopped = self.client.post(f"{self.session_path}/bulb/stop").json()
        self.assertFalse(stopped["shutterReleaseUnconfirmed"])
        self.assertFalse(stopped["bulbExposureActive"])
        count = len(self.peer.commands)
        self.client.post(f"{self.session_path}/bulb/stop")
        self.assertEqual(len(self.peer.commands), count)
        observed = self.client.get(f"{self.session_path}/capabilities").json()["evidence"]["observedFeatures"]
        self.assertNotIn("BULB_EXPOSURE", observed)
        self.peer.drop_press = False
        self.assertTrue(self.client.post(f"{self.session_path}/bulb/start").json()["bulbExposureActive"])

    def test_confirmed_start_remains_observed_after_failed_stop_then_recovery(self) -> None:
        self.peer.drop_press = False
        self.assertTrue(self.client.post(f"{self.session_path}/bulb/start").json()["bulbExposureActive"])
        self.assertEqual(len(self.peer.commands), 1)
        # Starting an already confirmed exposure is idempotent.
        self.assertEqual(self.client.post(f"{self.session_path}/bulb/start").status_code, 200)
        self.assertEqual(len(self.peer.commands), 1)
        self.assertEqual(self.client.post(f"{self.session_path}/bulb/stop").status_code, 502)
        status = self.client.get(f"{self.session_path}/status").json()
        self.assertIsNone(status["bulbExposureActive"])
        self.assertTrue(status["shutterReleaseUnconfirmed"])
        self.peer.reject_release = False
        self.assertEqual(self.client.post(f"{self.session_path}/bulb/stop").status_code, 200)
        observed = self.client.get(f"{self.session_path}/capabilities").json()["evidence"]["observedFeatures"]
        self.assertIn("BULB_EXPOSURE", observed)

    def test_pending_release_blocks_other_camera_mutations(self) -> None:
        self.client.post(f"{self.session_path}/bulb/start")
        count = len(self.peer.commands)
        actions = [
            ("POST", "/capture/still", None),
            ("POST", "/shutter/half-press", None),
            ("POST", "/focus/auto", None),
            ("POST", "/recording/start", None),
            ("POST", "/settings/shootingmode", {"value": "Manual"}),
            ("POST", "/clock/sync", None),
            ("POST", "/directories", {"name": "TEST1"}),
            ("PUT", "/file-naming/movie-user-defined", {"value": "TEST1"}),
            ("POST", "/power/sleep", None),
            ("POST", "/maintenance/sensor-cleaning", {"autoPowerOff": False}),
            ("POST", "/liveview/start", {}),
            ("POST", "/focus/drive", {"direction": "near", "step": "SMALL"}),
            ("POST", "/focus/tap", {"x": 0.5, "y": 0.5}),
            ("POST", "/whitebalance/click", {"x": 0.5, "y": 0.5}),
            ("POST", "/liveview/magnification", {"value": 5}),
            ("PUT", "/media/synthetic/protection", {"enabled": True}),
            ("PUT", "/media/synthetic/rating", {"value": 1}),
            ("PUT", "/media/synthetic/rotation", {"degrees": 90}),
            ("PUT", "/media/synthetic/archive", {"enabled": True}),
            ("DELETE", "/media/synthetic", None),
        ]
        for method, path, payload in actions:
            with self.subTest(path=path):
                response = self.client.request(method, f"{self.session_path}{path}", json=payload)
                self.assertEqual(response.status_code, 409, response.text)
                self.assertEqual(response.json()["error"]["code"], "SHUTTER_RELEASE_UNCONFIRMED")
        self.assertEqual(len(self.peer.commands), count)
        # Read-only requests and safe stops stay available.
        self.assertEqual(self.client.get(f"{self.session_path}/status").status_code, 200)
        self.assertEqual(self.client.get(f"{self.session_path}/capabilities").status_code, 200)
        self.assertEqual(self.client.post(f"{self.session_path}/recording/stop").status_code, 200)
        self.assertEqual(self.client.post(f"{self.session_path}/liveview/stop").status_code, 200)

    def test_status_failure_after_confirmed_release_does_not_repeat_release(self) -> None:
        self.client.post(f"{self.session_path}/bulb/start")
        self.peer.reject_release = False
        self.peer.drop_status = True
        self.assertEqual(self.client.post(f"{self.session_path}/bulb/stop").status_code, 502)
        count = len(self.peer.commands)
        self.peer.drop_status = False
        self.assertFalse(self.client.post(f"{self.session_path}/bulb/stop").json()["shutterReleaseUnconfirmed"])
        self.assertEqual(len(self.peer.commands), count)
        self.assertFalse(self.peer.active)

    def test_release_uses_saved_operation_and_is_not_transferred_to_new_session(self) -> None:
        session = CcapiEngine(sleeper=lambda _: None).open_connection(self.peer.origin)
        with self.assertRaises(BridgeError):
            session.start_bulb_exposure()
        session._operations = {CcapiOperation("POST", "/ccapi/ver110/shooting/control/shutterbutton/manual")}
        self.peer.reject_release = False
        session.stop_bulb_exposure()
        self.assertEqual(self.peer.commands[-1], ("PUT", MANUAL_PATH, {"af": False, "action": "release"}))
        session.close()
        self.peer.reject_release = True
        self.client.post(f"{self.session_path}/bulb/start")
        self.assertEqual(self.client.delete(self.session_path).status_code, 502)
        self.assertEqual(self.client.post(f"{self.session_path}/bulb/stop").status_code, 404)
        count = len(self.peer.commands)
        created = self.client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": self.peer.origin}).json()
        new_path = f"/v1/session/{created['id']}"
        self.assertFalse(self.client.get(f"{new_path}/status").json()["shutterReleaseUnconfirmed"])
        self.assertEqual(self.client.post(f"{new_path}/bulb/stop").status_code, 200)
        self.assertEqual(self.client.delete(new_path).status_code, 204)
        self.assertEqual(len(self.peer.commands), count)

    def test_additive_status_contract_defaults_false_for_legacy_payload(self) -> None:
        wire = self.client.get(f"{self.session_path}/status").json()
        legacy = {key: value for key, value in wire.items() if key != "shutterReleaseUnconfirmed"}
        self.assertFalse(CameraStatus.model_validate(legacy).shutter_release_unconfirmed)
        self.assertEqual({key: value for key, value in wire.items() if key in legacy}, legacy)
        schema = self.client.get("/openapi.json").json()["components"]["schemas"]["CameraStatus"]
        self.assertNotIn("shutterReleaseUnconfirmed", schema.get("required", []))
        self.assertEqual(schema["properties"]["shutterReleaseUnconfirmed"]["default"], False)


class BulbShutdownTests(unittest.TestCase):
    def test_delete_reserves_camera_until_final_release_finishes(self) -> None:
        manager = SessionManager(Mock())
        camera = CameraDescriptor(id="synthetic-camera", model="Synthetic", port="http://127.0.0.1", engine="ccapi")
        closing = Mock(engine_name="ccapi", camera=camera)
        replacement = Mock(engine_name="ccapi", camera=camera)
        created = manager._register(closing)

        def during_close() -> None:
            with self.assertRaises(BridgeError) as rejected:
                manager._register(replacement)
            self.assertEqual(rejected.exception.code, "CAMERA_BUSY")
            raise BridgeError("SHUTTER_RELEASE_UNCONFIRMED", "Check camera")

        closing.close.side_effect = during_close
        with self.assertRaises(BridgeError):
            manager.delete(created.id)
        self.assertFalse(manager._camera_sessions)
        next_session = manager._register(replacement)
        self.assertNotEqual(created.id, next_session.id)
        self.assertIs(manager.get(next_session.id), replacement)

    def test_close_all_cleans_remaining_sessions_after_release_failure(self) -> None:
        manager = SessionManager(Mock())
        failed = Mock()
        failed.close.side_effect = BridgeError("SHUTTER_RELEASE_UNCONFIRMED", "Check camera")
        healthy = Mock()
        manager._sessions = {"failed": failed, "healthy": healthy}
        with self.assertRaises(BridgeError):
            manager.close_all()
        failed.close.assert_called_once()
        healthy.close.assert_called_once()
        self.assertFalse(manager._sessions)


if __name__ == "__main__":
    unittest.main()
