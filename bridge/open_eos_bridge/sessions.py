from __future__ import annotations

import secrets
import threading

from .engine import CameraEngineSession, NetworkCameraEngine
from .engine_registry import LocalEngineRegistry
from .errors import BridgeError
from .models import CameraDescriptor, EngineName, SessionCreated, SessionCreateRequest


class SessionManager:
    def __init__(
        self,
        local_engines: LocalEngineRegistry,
        network_engine: NetworkCameraEngine | None = None,
    ) -> None:
        self.local_engines = local_engines
        self.network_engine = network_engine
        self._sessions: dict[str, CameraEngineSession] = {}
        self._camera_sessions: dict[str, str] = {}
        self._lock = threading.RLock()
        self._open_lock = threading.Lock()

    def discover(self) -> list[CameraDescriptor]:
        return self.local_engines.discover()

    def create(self, request: SessionCreateRequest) -> SessionCreated:
        use_ccapi = request.engine == EngineName.CCAPI or (
            request.engine == EngineName.AUTO and bool(request.ccapi_url)
        )

        if use_ccapi:
            if self.network_engine is None:
                raise BridgeError(
                    "ENGINE_UNAVAILABLE",
                    "The CCAPI network engine is unavailable.",
                    status_code=503,
                    engine=EngineName.CCAPI.value,
                )
            if not request.ccapi_url:
                raise BridgeError(
                    "CCAPI_URL_REQUIRED",
                    "Provide the camera CCAPI base URL.",
                    status_code=422,
                    engine=EngineName.CCAPI.value,
                )
            session = self.network_engine.open_connection(
                request.ccapi_url,
                request.ccapi_username or "",
                request.ccapi_password or "",
            )
        else:
            with self._open_lock:
                engine_name = self.local_engines.resolve_name(request.engine, request.camera_id)
                self._require_compatible_local_engine(engine_name)
                session = self.local_engines.open(engine_name, request.camera_id, request.profile_hint)
                try:
                    return self._register(session)
                except Exception:
                    session.close()
                    raise

        try:
            return self._register(session)
        except Exception:
            session.close()
            raise

    def _register(self, session: CameraEngineSession) -> SessionCreated:
        camera_key = f"{session.engine_name}:{session.camera.id}"
        with self._lock:
            self._require_compatible_local_engine(session.engine_name)
            existing = self._camera_sessions.get(camera_key)
            if existing is not None:
                raise BridgeError(
                    "CAMERA_BUSY",
                    f"Camera already belongs to bridge session {existing}.",
                    status_code=409,
                    engine=session.engine_name,
                )
            session_id = secrets.token_urlsafe(18)
            self._sessions[session_id] = session
            self._camera_sessions[camera_key] = session_id
        return SessionCreated(id=session_id, engine=session.engine_name, camera=session.camera)

    def _require_compatible_local_engine(self, engine_name: str) -> None:
        if engine_name == EngineName.CCAPI.value:
            return
        with self._lock:
            active_local_engines = {
                session.engine_name
                for session in self._sessions.values()
                if session.engine_name != EngineName.CCAPI.value
            }
            if active_local_engines and engine_name not in active_local_engines:
                raise BridgeError(
                    "CAMERA_BUSY",
                    "Another local USB engine already owns a camera session.",
                    status_code=409,
                    engine=engine_name,
                )

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
                self._camera_sessions.pop(f"{session.engine_name}:{session.camera.id}", None)
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
