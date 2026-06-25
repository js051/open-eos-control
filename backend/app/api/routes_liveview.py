from fastapi import APIRouter, Response

from app.session.camera_session import camera_session

router = APIRouter(tags=["liveview"])


@router.get("/frame")
async def frame() -> Response:
    frame_bytes = await camera_session.client.get_liveview_frame()
    return Response(content=frame_bytes, media_type="image/svg+xml")
