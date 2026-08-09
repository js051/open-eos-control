from __future__ import annotations

from contextlib import suppress

from .edsdk_contract import EdsdkProvider
from .edsdk_loader import EdsdkProviderLoadResult, load_edsdk_provider
from .engine import CameraEngineSession
from .errors import BridgeError
from .models import CameraDescriptor, EngineName


class EdsdkEngine:
    name = EngineName.EDSDK.value

    def __init__(
        self,
        provider: EdsdkProvider | None = None,
        *,
        load_result: EdsdkProviderLoadResult | None = None,
    ) -> None:
        if provider is not None and load_result is not None:
            raise ValueError("Provide either an EDSDK provider or a load result, not both.")
        self._load_result = load_result or (
            EdsdkProviderLoadResult(provider=provider, detail="Provider injected.")
            if provider is not None
            else load_edsdk_provider()
        )

    def health(self) -> tuple[bool, str | None, str | None]:
        provider = self._load_result.provider
        if provider is None:
            return False, None, self._load_result.detail
        try:
            available, _ = provider.health()
        except Exception as error:
            return False, None, f"The licensed EDSDK provider health check failed ({type(error).__name__})."
        version = f"{provider.provider_name} {provider.provider_version}".strip()
        detail = (
            "Licensed EDSDK provider ready."
            if available
            else "The licensed EDSDK provider reported unavailable."
        )
        return bool(available), version, detail

    def discover(self) -> list[CameraDescriptor]:
        provider = self._require_provider()
        try:
            cameras = provider.discover()
            if not isinstance(cameras, list):
                raise self._invalid_response("camera discovery did not return a list")
            for camera in cameras:
                self._validate_camera(camera)
        except BridgeError:
            raise
        except Exception as error:
            raise self._provider_failure("discover cameras", error) from error
        return cameras

    def open(
        self,
        camera_id: str | None = None,
        profile_hint: str | None = None,
    ) -> CameraEngineSession:
        provider = self._require_provider()
        try:
            session = provider.open(camera_id, profile_hint)
        except BridgeError:
            raise
        except Exception as error:
            raise self._provider_failure("open the camera", error) from error
        try:
            if not callable(getattr(session, "close", None)):
                raise self._invalid_response("camera session has no close operation")
            self._validate_camera(session.camera)
            if session.engine_name != self.name:
                raise self._invalid_response("camera session belongs to a different engine")
        except Exception:
            self._close_invalid_session(session)
            raise
        return session

    def _require_provider(self) -> EdsdkProvider:
        provider = self._load_result.provider
        if provider is None:
            raise BridgeError(
                "ENGINE_UNAVAILABLE",
                self._load_result.detail,
                status_code=501,
                engine=self.name,
            )
        available, _, detail = self.health()
        if not available:
            raise BridgeError(
                "ENGINE_UNAVAILABLE",
                detail or "The licensed EDSDK provider is unavailable.",
                status_code=503,
                engine=self.name,
            )
        return provider

    def _validate_camera(self, camera: CameraDescriptor) -> None:
        if not isinstance(camera, CameraDescriptor):
            raise self._invalid_response("camera descriptor has the wrong type")
        if camera.engine != self.name or not camera.id.startswith("edsdk-"):
            raise self._invalid_response("camera descriptor has an invalid engine or ID")

    def _close_invalid_session(self, session: object) -> None:
        close = getattr(session, "close", None)
        if callable(close):
            with suppress(Exception):
                close()

    def _invalid_response(self, reason: str) -> BridgeError:
        return BridgeError(
            "INVALID_PROVIDER_RESPONSE",
            f"The EDSDK provider returned an invalid response: {reason}.",
            status_code=502,
            engine=self.name,
        )

    def _provider_failure(self, action: str, error: Exception) -> BridgeError:
        return BridgeError(
            "ENGINE_COMMAND_FAILED",
            f"The licensed EDSDK provider could not {action} ({type(error).__name__}).",
            status_code=502,
            engine=self.name,
        )
