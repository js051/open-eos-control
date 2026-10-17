from __future__ import annotations

import secrets
import threading
from contextlib import contextmanager

from .bulb import BulbRequests
from .engine import CameraEngineSession, NetworkCameraEngine
from .engine_registry import LocalEngineRegistry
from .errors import BridgeError
from .maintenance_live_view import MaintenanceLiveViewRequests
from .models import BulbOwnerResult, CameraDescriptor, CameraStatus, EngineName, SessionCreated, SessionCreateRequest


class SessionManager:
    def __init__(
        self,
        local_engines: LocalEngineRegistry,
        network_engine: NetworkCameraEngine | None = None,
    ) -> None:
        self.local_engines = local_engines
        self.network_engine = network_engine
        self._sessions: dict[str, CameraEngineSession] = {}
        self._bulb_requests: dict[str, BulbRequests] = {}
        self._maintenance_live_view: dict[str, MaintenanceLiveViewRequests] = {}
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
            self._bulb_requests[session_id] = BulbRequests(session)
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

    def is_current(self, session_id: str, session: CameraEngineSession) -> bool:
        """Bind asynchronous work to the exact registered object, even if an ID is reused."""
        with self._lock:
            return self._sessions.get(session_id) is session

    def _bulb_requests_for(self, session_id: str) -> BulbRequests:
        with self._lock:
            session = self.get(session_id)
            guard = self._bulb_requests.get(session_id)
            if guard is None or guard.session is not session:
                if guard is not None:
                    guard.retire()
                guard = self._bulb_requests[session_id] = BulbRequests(session)
            return guard

    def maintenance_live_view(self, session_id: str) -> MaintenanceLiveViewRequests:
        with self._lock:
            camera = self._bulb_requests_for(session_id)
            controls = self._maintenance_live_view.get(session_id)
            if controls is None or controls.session is not camera.session:
                if controls is not None:
                    controls.retire()
                controls = self._maintenance_live_view[session_id] = MaintenanceLiveViewRequests(camera)
            return controls

    def _retire_controls(self, session_id: str) -> None:
        controls = self._maintenance_live_view.get(session_id)
        if controls is not None:
            controls.retire()

    def start_bulb_exposure(self, session_id: str, owner_id: str | None = None) -> CameraStatus | BulbOwnerResult:
        return self._bulb_requests_for(session_id).start(owner_id)

    def stop_bulb_exposure(self, session_id: str, owner_id: str | None = None) -> CameraStatus | BulbOwnerResult:
        return self._bulb_requests_for(session_id).stop(owner_id)

    @contextmanager
    def camera_mutation(self, session_id: str):
        """Bind a new camera control to this exact session and Bulb admission."""
        with self._bulb_requests_for(session_id).mutation() as session:
            yield session

    def delete(self, session_id: str) -> None:
        guard = self._bulb_requests_for(session_id)
        with guard.retirement_lock:
            with self._lock:
                if not self.is_current(session_id, guard.session):
                    raise BridgeError("SESSION_NOT_FOUND", "Camera session was not found.", status_code=404)
                guard.retire()
                self._retire_controls(session_id)
                self._maintenance_live_view.pop(session_id, None)
                session = self._sessions.pop(session_id)
                self._bulb_requests.pop(session_id, None)
            try:
                guard.finish_retirement(session.close)
            finally:
                # Keep the camera reserved until this exact session's final cleanup ends.
                with self._lock:
                    self._camera_sessions.pop(f"{session.engine_name}:{session.camera.id}", None)

    def retire_maintenance(self, session_id: str) -> None:
        guard = self._bulb_requests_for(session_id)
        with guard.retirement_lock:
            # Keep registration while local retirement is in flight. Unlike close,
            # a refused retirement may leave a camera Stop obligation to resolve.
            with self._lock:
                if not self.is_current(session_id, guard.session):
                    raise BridgeError("SESSION_NOT_FOUND", "Camera session was not found.", status_code=404)
                session = guard.session
                retire = getattr(session, "maintenance_retire", None)
                if not callable(retire):
                    raise BridgeError("UNSUPPORTED_MAINTENANCE_RETIRE", "Local-only retirement is unavailable.",
                                      status_code=409, engine=session.engine_name)
                guard.retire()
                self._retire_controls(session_id)
            try:
                guard.finish_retirement(retire)
            except BaseException:
                with self._lock:
                    if self.is_current(session_id, session):
                        guard.resume_after_failed_retirement()
                        controls = self._maintenance_live_view.get(session_id)
                        if controls is not None:
                            controls.resume_after_failed_retirement()
                raise
            with self._lock:
                if self.is_current(session_id, session):
                    self._sessions.pop(session_id, None)
                    self._bulb_requests.pop(session_id, None)
                    self._maintenance_live_view.pop(session_id, None)
                    self._camera_sessions.pop(f"{session.engine_name}:{session.camera.id}", None)

    def close_all(self) -> None:
        with self._lock:
            sessions = [(identifier, session, self._bulb_requests_for(identifier))
                        for identifier, session in self._sessions.items()]
            for identifier, _, guard in sessions:
                guard.retire()
                self._retire_controls(identifier)
            self._sessions.clear()
            self._bulb_requests.clear()
            self._maintenance_live_view.clear()
        failure = None
        for identifier, session, guard in sessions:
            try:
                with guard.retirement_lock:
                    guard.finish_retirement(session.close)
            except Exception as error:
                # Provider and subprocess cleanup can raise ordinary OS errors.
                # Drain every session, then preserve the first reported failure.
                failure = failure or error
            finally:
                with self._lock:
                    key = f"{session.engine_name}:{session.camera.id}"
                    if self._camera_sessions.get(key) == identifier:
                        self._camera_sessions.pop(key, None)
        if failure is not None:
            raise failure
