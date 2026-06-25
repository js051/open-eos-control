from abc import ABC, abstractmethod

from app.camera.models import (
    CameraCapabilities,
    CameraInfo,
    CameraStatus,
    ExposureUpdate,
    FocusResult,
)


class CameraClient(ABC):
    @abstractmethod
    async def connect(self) -> CameraInfo:
        raise NotImplementedError

    @abstractmethod
    async def get_status(self) -> CameraStatus:
        raise NotImplementedError

    @abstractmethod
    async def get_capabilities(self) -> CameraCapabilities:
        raise NotImplementedError

    @abstractmethod
    async def set_exposure(self, exposure: ExposureUpdate) -> CameraStatus:
        raise NotImplementedError

    @abstractmethod
    async def set_white_balance(self, value: str) -> CameraStatus:
        raise NotImplementedError

    @abstractmethod
    async def start_recording(self) -> CameraStatus:
        raise NotImplementedError

    @abstractmethod
    async def stop_recording(self) -> CameraStatus:
        raise NotImplementedError

    @abstractmethod
    async def tap_focus(self, x: float, y: float) -> FocusResult:
        raise NotImplementedError

    @abstractmethod
    async def get_liveview_frame(self) -> bytes:
        raise NotImplementedError
