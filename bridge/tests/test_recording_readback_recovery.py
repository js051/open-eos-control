from __future__ import annotations

import unittest

from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine

from .ccapi_bulb_peer import BulbPeer


class RecordingReadbackRecoveryTests(unittest.TestCase):
    """Real ASGI/urllib boundary; an independent TCP peer acknowledges REC."""

    def setUp(self) -> None:
        self.peer = BulbPeer()
        self.peer.mode = "Manual"
        self.peer.drop_press = False
        self.peer.reject_release = False
        self.peer.temperature_enabled = True
        self.client = TestClient(create_app(ccapi_engine=CcapiEngine(sleeper=lambda _: None)))
        self.client.__enter__()
        created = self.client.post("/v1/session", json={"engine": "ccapi", "ccapiUrl": self.peer.origin})
        self.assertEqual(created.status_code, 201, created.text)
        self.route = f"/v1/session/{created.json()['id']}"

    def tearDown(self) -> None:
        self.peer.drop_status = False
        self.peer.malformed_status = False
        self.client.__exit__(None, None, None)
        self.peer.close()

    def test_acknowledged_start_with_failed_readback_has_distinct_error_and_read_only_recovery(self) -> None:
        self.assert_readback_failure("start", True)

    def test_acknowledged_stop_with_failed_readback_has_distinct_error_and_read_only_recovery(self) -> None:
        self.assertEqual(self.client.post(f"{self.route}/recording/start").status_code, 200)
        self.peer.commands.clear()
        self.assert_readback_failure("stop", False)

    def assert_readback_failure(self, direction: str, expected: bool) -> None:
        self.peer.drop_status = True
        result = self.client.post(f"{self.route}/recording/{direction}")
        self.assertEqual(result.status_code, 502, result.text)
        self.assertEqual(result.json()["error"]["code"], "RECORDING_STATUS_READBACK_FAILED")
        self.assertEqual(result.json()["error"]["feature"], "VIDEO_RECORDING")
        self.assertEqual(result.json()["error"]["engine"], "ccapi")
        self.assertEqual(self.peer.commands, [
            ("POST", "/ccapi/ver100/shooting/control/recbutton", {"action": direction}),
        ])
        self.peer.drop_status = False
        recovered = self.client.get(f"{self.route}/status")
        self.assertEqual(recovered.status_code, 200, recovered.text)
        self.assertIs(recovered.json()["recording"], expected)
        self.assertEqual(len(self.peer.commands), 1, "Read-only recovery must not replay REC")

    def test_current_recording_start_and_stop_keep_existing_success_contract(self) -> None:
        started = self.client.post(f"{self.route}/recording/start")
        stopped = self.client.post(f"{self.route}/recording/stop")
        self.assertEqual(started.status_code, 200, started.text)
        self.assertIs(started.json()["recording"], True)
        self.assertEqual(stopped.status_code, 200, stopped.text)
        self.assertIs(stopped.json()["recording"], False)
        self.assertEqual([command[2]["action"] for command in self.peer.commands], ["start", "stop"])

    def test_acknowledged_rec_with_malformed_status_is_distinct_from_command_failure(self) -> None:
        self.peer.malformed_status = True
        response = self.client.post(f"{self.route}/recording/start")
        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json()["error"]["code"], "RECORDING_STATUS_READBACK_FAILED")
        self.assertEqual(len(self.peer.commands), 1)

    def test_rejected_rec_is_not_reported_as_acknowledged(self) -> None:
        self.peer.reject_recording = True
        response = self.client.post(f"{self.route}/recording/start")
        self.assertNotEqual(response.status_code, 200)
        self.assertNotEqual(response.json()["error"]["code"], "RECORDING_STATUS_READBACK_FAILED")
        self.assertEqual(len(self.peer.commands), 1)

    def test_lost_rec_response_is_not_reported_as_acknowledged_or_replayed(self) -> None:
        self.peer.drop_recording_response = True
        response = self.client.post(f"{self.route}/recording/start")
        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json()["error"]["code"], "RECORDING_COMMAND_UNCONFIRMED")
        self.assertTrue(self.peer.recording)
        self.assertEqual(len(self.peer.commands), 1)

    def test_applied_start_with_lost_ack_needs_explicit_stop_despite_cached_false(self) -> None:
        self.assertEqual(self.client.post(f"{self.route}/recording/start").status_code, 200)
        self.assertEqual(self.client.post(f"{self.route}/recording/stop").status_code, 200)
        self.assertFalse(self.peer.recording)
        self.peer.commands.clear()
        self.peer.drop_recording_response = True
        response = self.client.post(f"{self.route}/recording/start")
        self.assertEqual(response.status_code, 502, response.text)
        self.assertEqual(response.json()["error"]["code"], "RECORDING_COMMAND_UNCONFIRMED")
        self.assertTrue(self.peer.recording)
        refreshed = self.client.get(f"{self.route}/status")
        self.assertEqual(refreshed.status_code, 200, refreshed.text)
        self.assertIs(refreshed.json()["recording"], False, "Only the last ACK cache is reported")
        self.assertTrue(self.peer.recording, "A cached false must not be confused with peer state")
        self.assertEqual([entry[2]["action"] for entry in self.peer.commands], ["start"])
        self.peer.drop_recording_response = False
        stopped = self.client.post(f"{self.route}/recording/stop")
        self.assertEqual(stopped.status_code, 200, stopped.text)
        self.assertIs(stopped.json()["recording"], False)
        self.assertFalse(self.peer.recording)
        self.assertEqual([entry[2]["action"] for entry in self.peer.commands], ["start", "stop"])

    def test_explicit_rejection_does_not_apply_or_claim_uncertain_command(self) -> None:
        self.peer.reject_recording = True
        response = self.client.post(f"{self.route}/recording/start")
        self.assertEqual(response.status_code, 502, response.text)
        self.assertEqual(response.json()["error"]["code"], "CCAPI_HTTP_ERROR")
        self.assertFalse(self.peer.recording)
        self.assertEqual([entry[2]["action"] for entry in self.peer.commands], ["start"])

    def test_rejection_with_truncated_body_preserves_known_http_status(self) -> None:
        self.assert_rejected_recording_body("truncated")

    def test_rejection_with_oversized_body_preserves_known_http_status(self) -> None:
        self.assert_rejected_recording_body("oversized")

    def test_rejection_with_unreadable_chunked_body_preserves_known_http_status(self) -> None:
        self.assert_rejected_recording_body("invalid_chunk")

    def assert_rejected_recording_body(self, fault: str) -> None:
        for status in (403, 503):
            for direction in ("start", "stop"):
                with self.subTest(status=status, direction=direction):
                    was_recording = direction == "stop"
                    self.establish_recording_state(was_recording)
                    self.peer.reject_recording = True
                    self.peer.recording_rejection_status = status
                    self.peer.recording_response_fault = fault
                    response = self.client.post(f"{self.route}/recording/{direction}")
                    self.assertEqual(response.status_code, 502, response.text)
                    self.assertEqual(response.json()["error"]["code"], "CCAPI_HTTP_ERROR")
                    self.assertIn(f"returned HTTP {status}.", response.json()["error"]["message"])
                    self.assertIs(self.peer.recording, was_recording, "Rejected REC must not change peer state")
                    refreshed = self.client.get(f"{self.route}/status")
                    self.assertEqual(refreshed.status_code, 200, refreshed.text)
                    self.assertIs(refreshed.json()["recording"], was_recording, "Keep the previous ACK cache")
                    self.assertEqual(self.peer.commands, [
                        ("POST", "/ccapi/ver100/shooting/control/recbutton", {"action": direction}),
                    ], "Neither rejection nor status refresh may replay REC")

    def test_incomplete_success_body_still_leaves_recording_command_unconfirmed(self) -> None:
        for fault in ("truncated", "oversized", "invalid_chunk"):
            for direction in ("start", "stop"):
                with self.subTest(fault=fault, direction=direction):
                    self.establish_recording_state(direction == "stop")
                    self.peer.recording_response_fault = fault
                    response = self.client.post(f"{self.route}/recording/{direction}")
                    self.assertEqual(response.status_code, 502, response.text)
                    self.assertEqual(response.json()["error"]["code"], "RECORDING_COMMAND_UNCONFIRMED")
                    self.assertIs(self.peer.recording, direction == "start", "Peer applied REC before its faulty ACK")
                    self.assertEqual(self.peer.commands, [
                        ("POST", "/ccapi/ver100/shooting/control/recbutton", {"action": direction}),
                    ])

    def test_temperature_preflight_failure_preserves_original_error_without_sending_rec(self) -> None:
        self.peer.drop_temperature = True
        self.peer.reads.clear()
        response = self.client.post(f"{self.route}/recording/start")
        self.assertEqual(response.status_code, 502, response.text)
        self.assertEqual(response.json()["error"]["code"], "CCAPI_UNREACHABLE")
        self.assertIn("Could not reach the camera CCAPI endpoint", response.json()["error"]["message"])
        self.assertEqual(self.peer.reads, ["/ccapi/ver100/devicestatus/temperature"])
        self.assertEqual(self.peer.commands, [], "A failed preflight must send zero REC commands")
        self.assertFalse(self.peer.recording)

    def establish_recording_state(self, recording: bool) -> None:
        self.peer.reject_recording = False
        self.peer.recording_response_fault = None
        direction = "start" if recording else "stop"
        response = self.client.post(f"{self.route}/recording/{direction}")
        self.assertEqual(response.status_code, 200, response.text)
        self.assertIs(self.peer.recording, recording)
        self.peer.commands.clear()
