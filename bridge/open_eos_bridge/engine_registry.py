from __future__ import annotations

from collections.abc import Iterable

from .engine import CameraEngine, CameraEngineSession
from .errors import BridgeError
from .models import CameraDescriptor, EngineHealth, EngineName


class LocalEngineRegistry:
    def __init__(
        self,
        engines: Iterable[CameraEngine],
        *,
        default_engine: str = EngineName.LIBGPHOTO2.value,
    ) -> None:
        self._engines: dict[str, CameraEngine] = {}
        for engine in engines:
            if engine.name in self._engines:
                raise ValueError(f"Duplicate local camera engine '{engine.name}'.")
            self._engines[engine.name] = engine
        if default_engine not in self._engines:
            raise ValueError(f"Default local camera engine '{default_engine}' is not registered.")
        self.default_engine = default_engine

    @property
    def engines(self) -> tuple[CameraEngine, ...]:
        return tuple(self._engines.values())

    def health(self) -> dict[str, EngineHealth]:
        result: dict[str, EngineHealth] = {}
        for engine in self.engines:
            available, version, detail = engine.health()
            result[engine.name] = EngineHealth(available=available, version=version, detail=detail)
        return result

    def discover(self) -> list[CameraDescriptor]:
        cameras: list[CameraDescriptor] = []
        failures: list[BridgeError] = []
        available_engines = 0
        for engine in self.engines:
            available, _, _ = engine.health()
            if not available:
                continue
            available_engines += 1
            try:
                cameras.extend(engine.discover())
            except BridgeError as error:
                failures.append(error)
        if cameras or available_engines:
            if not cameras and failures:
                raise failures[0]
            return cameras
        raise BridgeError(
            "ENGINE_UNAVAILABLE",
            "No local camera engine is available.",
            status_code=503,
        )

    def resolve_name(self, requested: EngineName, camera_id: str | None = None) -> str:
        if requested == EngineName.AUTO:
            return self._engine_for_camera(camera_id) if camera_id else self.default_engine
        if requested not in {EngineName.LIBGPHOTO2, EngineName.EDSDK}:
            raise BridgeError("UNKNOWN_ENGINE", f"Unknown local engine '{requested}'.", status_code=422)
        return requested.value

    def open(
        self,
        engine_name: str,
        camera_id: str | None = None,
        profile_hint: str | None = None,
    ) -> CameraEngineSession:
        engine = self._engines.get(engine_name)
        if engine is None:
            raise BridgeError(
                "ENGINE_UNAVAILABLE",
                f"The '{engine_name}' camera engine is not registered.",
                status_code=501,
                engine=engine_name,
            )
        return engine.open(camera_id, profile_hint)

    def _engine_for_camera(self, camera_id: str) -> str:
        for engine in self.engines:
            available, _, _ = engine.health()
            if not available:
                continue
            try:
                if any(camera.id == camera_id for camera in engine.discover()):
                    return engine.name
            except BridgeError:
                continue
        return self.default_engine
