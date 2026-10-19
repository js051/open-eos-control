"""Synthetic maintenance peer; no production gphoto maintenance support is implied."""
from __future__ import annotations

import os
from pathlib import Path

from open_eos_bridge.app import create_app
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import GPhoto2Engine

from .fakes import FakeRunner


class SyntheticMaintenanceSession:
    """Delegate ordinary fake-camera operations; admit only idle local retirement."""

    def __init__(self, session):
        self._session = session

    def __getattr__(self, name):
        return getattr(self._session, name)

    def maintenance_retire(self):
        session = self._session
        with session._lock:
            if (session._short_control_release is not None or session._bulb_release_values is not None
                    or session._live_view_active or session._live_view_stream is not None):
                raise BridgeError("SYNTHETIC_RETIRE_OBLIGATION", "Synthetic camera is not idle.", status_code=409)
            before = len(session.runner.commands)
            session.close()
            if len(session.runner.commands) != before:
                raise AssertionError("Synthetic local retirement issued a camera command")


class SyntheticMaintenanceEngine(GPhoto2Engine):
    def __init__(self, capture_directory):
        # This fixture can never construct a subprocess-backed real-camera runner.
        super().__init__(FakeRunner(), capture_directory=capture_directory)

    def open(self, camera_id=None, profile_hint=None):
        return SyntheticMaintenanceSession(super().open(camera_id, profile_hint))


def make_app(capture_directory):
    return create_app(engine=SyntheticMaintenanceEngine(capture_directory))


# Uvicorn factory keeps importing the classes usable in direct fixture tests.
def application():
    return make_app(Path(os.environ["OPEN_EOS_BROWSER_CAPTURE_DIR"]))
