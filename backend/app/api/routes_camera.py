from fastapi import APIRouter

from app.camera.models import (
    CameraCapabilities,
    CameraConnectRequest,
    CameraInfo,
    CameraStatus,
    ExposureUpdate,
    FocusRequest,
    FocusResult,
    RecordStatus,
    WhiteBalanceUpdate,
)
from app.session.camera_session import camera_session

router = APIRouter(tags=["camera"])


@router.post("/connect")
async def connect(payload: CameraConnectRequest) -> CameraInfo:
    return await camera_session.connect(payload)


@router.get("/status")
async def status() -> CameraStatus:
    return await camera_session.client.get_status()


@router.get("/capabilities")
async def capabilities() -> CameraCapabilities:
    return await camera_session.client.get_capabilities()


@router.patch("/exposure")
async def set_exposure(payload: ExposureUpdate) -> CameraStatus:
    return await camera_session.client.set_exposure(payload)


@router.patch("/white-balance")
async def set_white_balance(payload: WhiteBalanceUpdate) -> CameraStatus:
    return await camera_session.client.set_white_balance(payload.white_balance)


@router.post("/record/start")
async def start_recording() -> RecordStatus:
    status = await camera_session.client.start_recording()
    return RecordStatus(ok=True, recording=status.recording)


@router.post("/record/stop")
async def stop_recording() -> RecordStatus:
    status = await camera_session.client.stop_recording()
    return RecordStatus(ok=True, recording=status.recording)


@router.post("/focus/tap")
async def tap_focus(payload: FocusRequest) -> FocusResult:
    return await camera_session.client.tap_focus(payload.x, payload.y)
