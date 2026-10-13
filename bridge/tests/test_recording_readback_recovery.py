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
        self.assertEqual(response.json()["error"]["code"], "CCAPI_UNREACHABLE")
        self.assertEqual(len(self.peer.commands), 1)
