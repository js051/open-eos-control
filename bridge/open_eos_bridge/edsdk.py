from __future__ import annotations

import threading

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
        self._open_lock = threading.RLock()
        self._cleanup_state_lock = threading.Lock()
        # Keep even malformed/None responses: absence of a usable close operation
        # cannot prove that the provider released the native resources it opened.
        self._pending_cleanup: list[object] = []
        self._cleanup_in_progress = False

    def health(self) -> tuple[bool, str | None, str | None]:
        with self._cleanup_state_lock:
            if self._pending_cleanup:
                return False, None, "The EDSDK provider has an unconfirmed rejected-session cleanup."
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
        self._require_no_pending_cleanup()
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
        self._require_no_pending_cleanup(retry=True)
        with self._open_lock:
            self._require_no_pending_cleanup()
            return self._open(camera_id, profile_hint)

    def _open(self, camera_id: str | None, profile_hint: str | None) -> CameraEngineSession:
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
            if camera_id is not None and session.camera.id != camera_id:
                raise self._invalid_response("camera session does not match the requested camera")
        except Exception:
            with self._cleanup_state_lock:
                self._pending_cleanup.append(session)
                self._cleanup_in_progress = True
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
        closed = False
        try:
            close = getattr(session, "close", None)
            if not callable(close):
                return
            close()
            closed = True
        except Exception:
            # Preserve both the original validation error and the exact owner.
            # Native exception messages can contain private provider details.
            return
        finally:
            with self._cleanup_state_lock:
                if closed and self._pending_cleanup and self._pending_cleanup[0] is session:
                    self._pending_cleanup.clear()
                self._cleanup_in_progress = False

    def _require_no_pending_cleanup(self, *, retry: bool = False) -> None:
        with self._cleanup_state_lock:
            if not self._pending_cleanup:
                return
            if not retry or self._cleanup_in_progress:
                raise self._cleanup_pending()
            session = self._pending_cleanup[0]
            self._cleanup_in_progress = True
        # Native cleanup may block. Health and admission must still be able to
        # report the retained owner; only this caller may retry its exact close.
        self._close_invalid_session(session)
        with self._cleanup_state_lock:
            if self._pending_cleanup:
                raise self._cleanup_pending()

    def _cleanup_pending(self) -> BridgeError:
        return BridgeError(
            "CAMERA_BUSY",
            "The EDSDK provider's rejected session has not closed. "
            "Reconnecting can retry cleanup but cannot recover a missing close operation; "
            "provider repair and a Bridge restart may be required.",
            status_code=409,
            engine=self.name,
        )

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
