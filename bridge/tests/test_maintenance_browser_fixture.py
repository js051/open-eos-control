"""True API retirement against the explicitly synthetic maintenance provider."""
import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.gphoto2 import GPhoto2Engine

from .fakes import FakeRunner
from .maintenance_browser_server import make_app


class MaintenanceBrowserFixtureTests(unittest.TestCase):
    def test_exact_local_retirement_releases_owner_without_camera_commands(self):
        with tempfile.TemporaryDirectory() as directory:
            app = make_app(Path(directory))
            with TestClient(app) as client:
                identifier = client.post('/v1/session', json={}).json()['id']
                session = app.state.session_manager.get(identifier)
                before = list(session.runner.commands)
                response = client.delete(f'/v1/session/{identifier}/maintenance/control/session')
                self.assertEqual(response.status_code, 204)
                self.assertEqual(response.content, b'')
                self.assertEqual(session.runner.commands, before)
                self.assertTrue(session._closed)
                repeated = client.delete(f'/v1/session/{identifier}/maintenance/control/session')
                self.assertEqual(repeated.status_code, 404)
                self.assertEqual(client.post('/v1/session', json={}).status_code, 201)

    def test_pending_obligations_refuse_local_retirement_without_sending_stop(self):
        for attribute, value in [('_short_control_release', ('a', 'b', 'c')),
                                 ('_bulb_release_values', ('a', 'b')),
                                 ('_live_view_active', True), ('_live_view_stream', object())]:
            with self.subTest(attribute=attribute), tempfile.TemporaryDirectory() as directory:
                app = make_app(Path(directory))
                with TestClient(app) as client:
                    identifier = client.post('/v1/session', json={}).json()['id']
                    session = app.state.session_manager.get(identifier)
                    original = getattr(session._session, attribute)
                    setattr(session._session, attribute, value)
                    before = list(session.runner.commands)
                    try:
                        response = client.delete(f'/v1/session/{identifier}/maintenance/control/session')
                        self.assertEqual(response.status_code, 409)
                        self.assertEqual(response.json()['error']['code'], 'SYNTHETIC_RETIRE_OBLIGATION')
                        self.assertEqual(session.runner.commands, before)
                        self.assertFalse(session._closed)
                        self.assertIs(app.state.session_manager.get(identifier), session)
                        self.assertEqual(client.post('/v1/session', json={}).status_code, 409)
                    finally:
                        setattr(session._session, attribute, original)

    def test_ordinary_gphoto_provider_still_rejects_unsupported_retirement(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = FakeRunner()
            app = create_app(engine=GPhoto2Engine(runner, capture_directory=Path(directory)))
            with TestClient(app) as client:
                identifier = client.post('/v1/session', json={}).json()['id']
                before = list(runner.commands)
                response = client.delete(f'/v1/session/{identifier}/maintenance/control/session')
                self.assertEqual(response.status_code, 409)
                self.assertEqual(response.json()['error']['code'], 'UNSUPPORTED_MAINTENANCE_RETIRE')
                self.assertEqual(runner.commands, before)


if __name__ == '__main__':
    unittest.main()
