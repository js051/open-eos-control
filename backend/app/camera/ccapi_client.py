import httpx

from app.camera.base import CameraClient
from app.camera.errors import CameraError
from app.camera.fake_client import FakeCameraClient
from app.camera.models import (
    CameraCapabilities,
    CameraInfo,
    CameraStatus,
    ExposureUpdate,
    FocusResult,
)


class CcapiCameraClient(CameraClient):
    """Temporary adapter boundary for verified Canon CCAPI endpoints.

    The fake-backed fallback keeps the repo runnable until the R6 Mark III endpoint map is
    validated against Canon's CCAPI reference and a real body.
    """

    def __init__(self, base_url: str, *, timeout_seconds: float = 5.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds
        self._fallback = FakeCameraClient()

    async def _request(self, method: str, path: str, **kwargs: object) -> httpx.Response:
        try:
            async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
                response = await client.request(method, f"{self.base_url}{path}", **kwargs)
                response.raise_for_status()
                return response
        except httpx.TimeoutException as exc:
            raise CameraError(
                "CAMERA_TIMEOUT",
                "Camera did not respond within 5 seconds.",
                status_code=504,
            ) from exc
        except httpx.HTTPError as exc:
            raise CameraError(
                "UNKNOWN_CCAPI_ERROR",
                f"Camera request failed: {exc}",
                status_code=502,
            ) from exc

    async def connect(self) -> CameraInfo:
        return await self._fallback.connect()

    async def get_status(self) -> CameraStatus:
        return await self._fallback.get_status()

    async def get_capabilities(self) -> CameraCapabilities:
        return await self._fallback.get_capabilities()

    async def set_exposure(self, exposure: ExposureUpdate) -> CameraStatus:
        return await self._fallback.set_exposure(exposure)

    async def set_white_balance(self, value: str) -> CameraStatus:
        return await self._fallback.set_white_balance(value)

    async def start_recording(self) -> CameraStatus:
        return await self._fallback.start_recording()

    async def stop_recording(self) -> CameraStatus:
        return await self._fallback.stop_recording()

    async def tap_focus(self, x: float, y: float) -> FocusResult:
        return await self._fallback.tap_focus(x, y)

    async def get_liveview_frame(self) -> bytes:
        return await self._fallback.get_liveview_frame()
