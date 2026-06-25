from app.camera.base import CameraClient
from app.camera.ccapi_client import CcapiCameraClient
from app.camera.fake_client import FakeCameraClient
from app.camera.models import CameraConnectRequest, CameraInfo
from app.config import settings


class CameraSession:
    def __init__(self) -> None:
        self.client: CameraClient = FakeCameraClient()

    async def connect(self, payload: CameraConnectRequest) -> CameraInfo:
        base_url = str(payload.base_url or settings.default_camera_url)
        if payload.use_fake or settings.use_fake_camera:
            self.client = FakeCameraClient()
        else:
            self.client = CcapiCameraClient(base_url, timeout_seconds=settings.request_timeout_seconds)
        return await self.client.connect()


camera_session = CameraSession()
