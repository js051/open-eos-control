from __future__ import annotations

import hmac
import os
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import quote

from fastapi import APIRouter, Depends, FastAPI, Header, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles

from . import __version__
from .ccapi import CcapiEngine
from .engine import CameraEngine, NetworkCameraEngine
from .errors import BridgeError
from .gphoto2 import GPhoto2Engine
from .models import (
    CameraCapabilities,
    CameraEvent,
    CameraInfo,
    CameraList,
    CameraStatus,
    EngineHealth,
    ErrorDetail,
    ErrorResponse,
    FocusDriveRequest,
    FocusResult,
    HealthResponse,
    LiveViewMagnificationRequest,
    LiveViewMagnificationResult,
    LiveViewStartRequest,
    LiveViewState,
    MediaList,
    SessionCreated,
    SessionCreateRequest,
    SettingUpdate,
    TapFocusRequest,
)
from .sessions import SessionManager

LOOPBACK_CLIENTS = {"127.0.0.1", "::1", "testclient"}
STATIC_DIRECTORY = Path(__file__).with_name("static")
UI_HEADERS = {
    "Cache-Control": "no-cache",
    "Content-Security-Policy": (
        "default-src 'self'; img-src 'self' blob:; connect-src 'self'; "
        "style-src 'self'; script-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"
    ),
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
}


def create_app(
    *,
    engine: CameraEngine | None = None,
    ccapi_engine: NetworkCameraEngine | None = None,
    token: str | None = None,
) -> FastAPI:
    camera_engine = engine or GPhoto2Engine()
    network_engine = ccapi_engine or CcapiEngine()
    configured_token = token if token is not None else os.environ.get("OPEN_EOS_BRIDGE_TOKEN")
    manager = SessionManager(camera_engine, network_engine)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        manager.close_all()

    application = FastAPI(
        title="Open EOS Control Desktop Bridge",
        version=__version__,
        lifespan=lifespan,
    )
    application.state.session_manager = manager

    @application.get("/", include_in_schema=False, response_class=FileResponse)
    def desktop_control() -> FileResponse:
        return FileResponse(STATIC_DIRECTORY / "index.html", headers=UI_HEADERS)

    @application.get("/favicon.ico", include_in_schema=False, response_class=FileResponse)
    def favicon() -> FileResponse:
        return FileResponse(
            STATIC_DIRECTORY / "app-icon.png",
            media_type="image/png",
            headers={"Cache-Control": "public, max-age=86400"},
        )

    @application.exception_handler(BridgeError)
    async def bridge_error_handler(_: Request, error: BridgeError) -> JSONResponse:
        payload = ErrorResponse(
            error=ErrorDetail(
                code=error.code,
                message=error.message,
                feature=error.feature,
                engine=error.engine,
            )
        )
        return JSONResponse(
            status_code=error.status_code,
            content=payload.model_dump(mode="json", by_alias=True, exclude_none=True),
            headers={"WWW-Authenticate": "Bearer"} if error.status_code == 401 else None,
        )

    @application.exception_handler(RequestValidationError)
    async def validation_error_handler(_: Request, error: RequestValidationError) -> JSONResponse:
        details = []
        for issue in error.errors()[:10]:
            location = ".".join(str(part) for part in issue.get("loc", ()))
            details.append(f"{location}: {issue.get('msg', 'invalid value')}")
        payload = ErrorResponse(
            error=ErrorDetail(
                code="INVALID_REQUEST",
                message="; ".join(details)[:2000] or "The request is invalid.",
            )
        )
        return JSONResponse(
            status_code=422,
            content=payload.model_dump(mode="json", by_alias=True, exclude_none=True),
        )

    def authorize(request: Request, authorization: str | None = Header(default=None)) -> None:
        client_host = request.client.host if request.client else ""
        if configured_token:
            scheme, _, credential = (authorization or "").partition(" ")
            if scheme.casefold() != "bearer" or not hmac.compare_digest(credential, configured_token):
                raise BridgeError(
                    "AUTHENTICATION_REQUIRED",
                    "Provide the configured desktop bridge Bearer token.",
                    status_code=401,
                )
            return
        if client_host not in LOOPBACK_CLIENTS:
            raise BridgeError(
                "LOOPBACK_ONLY",
                "Set OPEN_EOS_BRIDGE_TOKEN before accepting non-loopback clients.",
                status_code=403,
            )

    @application.get("/health", response_model=HealthResponse)
    def health() -> HealthResponse:
        available, engine_version, detail = camera_engine.health()
        network_available, network_version, network_detail = network_engine.health()
        return HealthResponse(
            version=__version__,
            auth_required=bool(configured_token),
            loopback_only=not bool(configured_token),
            engines={
                camera_engine.name: EngineHealth(
                    available=available,
                    version=engine_version,
                    detail=detail,
                ),
                network_engine.name: EngineHealth(
                    available=network_available,
                    version=network_version,
                    detail=network_detail,
                ),
            },
        )

    router = APIRouter(prefix="/v1", dependencies=[Depends(authorize)])

    @router.get("/cameras", response_model=CameraList)
    def cameras() -> CameraList:
        return CameraList(cameras=camera_engine.discover())

    @router.post("/session", response_model=SessionCreated, status_code=201)
    def create_session(payload: SessionCreateRequest) -> SessionCreated:
        return manager.create(payload)

    @router.get("/session/{session_id}/info", response_model=CameraInfo)
    def camera_info(session_id: str) -> CameraInfo:
        return manager.get(session_id).info()

    @router.get("/session/{session_id}/status", response_model=CameraStatus)
    def camera_status(session_id: str) -> CameraStatus:
        return manager.get(session_id).status()

    @router.get("/session/{session_id}/capabilities", response_model=CameraCapabilities)
    def camera_capabilities(session_id: str) -> CameraCapabilities:
        return manager.get(session_id).capabilities()

    @router.get("/session/{session_id}/events", response_model=CameraEvent)
    def camera_events(session_id: str) -> CameraEvent:
        return manager.get(session_id).poll_event()

    @router.delete("/session/{session_id}/events", status_code=204)
    def stop_camera_events(session_id: str) -> Response:
        manager.get(session_id).stop_event_polling()
        return Response(status_code=204)

    @router.post("/session/{session_id}/settings/{key}", response_model=CameraStatus)
    def set_camera_setting(session_id: str, key: str, payload: SettingUpdate) -> CameraStatus:
        return manager.get(session_id).set_setting(key, payload.value)

    @router.post("/session/{session_id}/capture/still", response_model=CameraStatus)
    def capture_still(session_id: str) -> CameraStatus:
        return manager.get(session_id).capture_still()

    @router.post("/session/{session_id}/bulb/start", response_model=CameraStatus)
    def start_bulb_exposure(session_id: str) -> CameraStatus:
        return manager.get(session_id).start_bulb_exposure()

    @router.post("/session/{session_id}/bulb/stop", response_model=CameraStatus)
    def stop_bulb_exposure(session_id: str) -> CameraStatus:
        return manager.get(session_id).stop_bulb_exposure()

    @router.post("/session/{session_id}/shutter/half-press", response_model=CameraStatus)
    def half_press_shutter(session_id: str) -> CameraStatus:
        return manager.get(session_id).half_press_shutter()

    @router.post("/session/{session_id}/focus/auto", response_model=CameraStatus)
    def autofocus(session_id: str) -> CameraStatus:
        return manager.get(session_id).autofocus()

    @router.post("/session/{session_id}/recording/start", response_model=CameraStatus)
    def start_recording(session_id: str) -> CameraStatus:
        return manager.get(session_id).start_recording()

    @router.post("/session/{session_id}/recording/stop", response_model=CameraStatus)
    def stop_recording(session_id: str) -> CameraStatus:
        return manager.get(session_id).stop_recording()

    @router.post("/session/{session_id}/focus/tap", response_model=FocusResult, response_model_exclude_none=True)
    def tap_focus(session_id: str, payload: TapFocusRequest) -> FocusResult:
        return manager.get(session_id).tap_focus(payload.x, payload.y)

    @router.post("/session/{session_id}/whitebalance/click", response_model=CameraStatus)
    def click_white_balance(session_id: str, payload: TapFocusRequest) -> CameraStatus:
        return manager.get(session_id).click_white_balance(payload.x, payload.y)

    @router.post("/session/{session_id}/focus/drive", response_model=FocusResult, response_model_exclude_none=True)
    def drive_focus(session_id: str, payload: FocusDriveRequest) -> FocusResult:
        return manager.get(session_id).drive_focus(payload.direction, payload.step)

    @router.post("/session/{session_id}/liveview/start", response_model=LiveViewState)
    def start_live_view(session_id: str, payload: LiveViewStartRequest) -> LiveViewState:
        session = manager.get(session_id)
        session.start_live_view(payload)
        requested_fps = getattr(session, "requested_fps", min(payload.fps, 5))
        source = getattr(session, "live_view_source", payload.source)
        return LiveViewState(active=True, requested_fps=requested_fps, source=source)

    @router.post("/session/{session_id}/liveview/stop", response_model=LiveViewState)
    def stop_live_view(session_id: str) -> LiveViewState:
        manager.get(session_id).stop_live_view()
        return LiveViewState(active=False)

    @router.post(
        "/session/{session_id}/liveview/magnification",
        response_model=LiveViewMagnificationResult,
    )
    def set_live_view_magnification(
        session_id: str,
        payload: LiveViewMagnificationRequest,
    ) -> LiveViewMagnificationResult:
        return manager.get(session_id).set_live_view_magnification(payload.value)

    @router.get("/session/{session_id}/liveview/frame")
    def live_view_frame(session_id: str) -> Response:
        frame = manager.get(session_id).live_view_frame()
        return Response(
            content=frame,
            media_type="image/jpeg",
            headers={"Cache-Control": "no-store, max-age=0"},
        )

    @router.get("/session/{session_id}/media", response_model=MediaList)
    def media(session_id: str) -> MediaList:
        return MediaList(items=manager.get(session_id).list_media())

    @router.get("/session/{session_id}/media/{media_id}/thumbnail")
    def media_thumbnail(session_id: str, media_id: str) -> Response:
        content, content_type = manager.get(session_id).media_thumbnail(media_id)
        return Response(
            content=content,
            media_type=content_type,
            headers={"Cache-Control": "private, no-store, max-age=0"},
        )

    @router.get("/session/{session_id}/media/{media_id}/preview")
    def media_preview(session_id: str, media_id: str) -> Response:
        content, content_type = manager.get(session_id).media_preview(media_id)
        return Response(
            content=content,
            media_type=content_type,
            headers={"Cache-Control": "private, no-store, max-age=0"},
        )

    @router.get("/session/{session_id}/media/{media_id}")
    def download_media(session_id: str, media_id: str) -> StreamingResponse:
        item, chunks = manager.get(session_id).download_media(media_id)
        headers = {
            "Content-Disposition": f"attachment; filename*=UTF-8''{quote(item.name)}",
            "Cache-Control": "no-store",
        }
        if item.size_bytes > 0:
            headers["Content-Length"] = str(item.size_bytes)
        return StreamingResponse(chunks, media_type=item.content_type, headers=headers)

    @router.delete("/session/{session_id}/media/{media_id}", status_code=204)
    def delete_media(session_id: str, media_id: str) -> Response:
        manager.get(session_id).delete_media(media_id)
        return Response(status_code=204)

    @router.delete("/session/{session_id}", status_code=204)
    def delete_session(session_id: str) -> Response:
        manager.delete(session_id)
        return Response(status_code=204)

    application.include_router(router)
    application.mount("/app", StaticFiles(directory=STATIC_DIRECTORY), name="desktop-control-assets")
    return application


app = create_app()
