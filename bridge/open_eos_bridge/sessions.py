from __future__ import annotations

import secrets
import threading

from .engine import CameraEngine, CameraEngineSession
from .errors import BridgeError
from .models import EngineName, SessionCreated, SessionCreateRequest


class SessionManager:
    def __init__(self, engine: CameraEngine) -> None:
        self.engine = engine
        self._sessions: dict[str, CameraEngineSession] = {}
        self._camera_sessions: dict[str, str] = {}
        self._lock = threading.RLock()

    def create(self, request: SessionCreateRequest) -> SessionCreated:
        if request.engine == EngineName.EDSDK:
            raise BridgeError(
                "ENGINE_UNAVAILABLE",
                "The optional Canon EDSDK adapter is not installed in this open-source build.",
                status_code=501,
                engine=EngineName.EDSDK.value,
            )
        if request.engine not in {EngineName.AUTO, EngineName.LIBGPHOTO2}:
            raise BridgeError("UNKNOWN_ENGINE", f"Unknown engine '{request.engine}'.", status_code=422)

        session = self.engine.open(request.camera_id, request.profile_hint)
        with self._lock:
            existing = self._camera_sessions.get(session.camera.id)
            if existing is not None:
                session.close()
                raise BridgeError(
                    "CAMERA_BUSY",
                    f"Camera already belongs to bridge session {existing}.",
                    status_code=409,
                    engine=session.engine_name,
                )
            session_id = secrets.token_urlsafe(18)
            self._sessions[session_id] = session
            self._camera_sessions[session.camera.id] = session_id
        return SessionCreated(id=session_id, engine=session.engine_name, camera=session.camera)

    def get(self, session_id: str) -> CameraEngineSession:
        with self._lock:
            session = self._sessions.get(session_id)
        if session is None:
            raise BridgeError("SESSION_NOT_FOUND", "Camera session was not found.", status_code=404)
        return session

    def delete(self, session_id: str) -> None:
        with self._lock:
            session = self._sessions.pop(session_id, None)
            if session is not None:
                self._camera_sessions.pop(session.camera.id, None)
        if session is None:
            raise BridgeError("SESSION_NOT_FOUND", "Camera session was not found.", status_code=404)
        session.close()

    def close_all(self) -> None:
        with self._lock:
            sessions = list(self._sessions.values())
            self._sessions.clear()
            self._camera_sessions.clear()
        for session in sessions:
            session.close()
