from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.api import routes_camera, routes_health, routes_liveview
from app.camera.errors import CameraError
from app.config import settings


def create_app() -> FastAPI:
    app = FastAPI(title="Open EOS Control API")

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.exception_handler(CameraError)
    async def camera_error_handler(_request: Request, exc: CameraError) -> JSONResponse:
        return JSONResponse(
            status_code=exc.status_code,
            content={
                "ok": False,
                "error": {
                    "code": exc.code,
                    "message": exc.message,
                    "recoverable": exc.recoverable,
                },
            },
        )

    app.include_router(routes_health.router, prefix="/api")
    app.include_router(routes_camera.router, prefix="/api/camera")
    app.include_router(routes_liveview.router, prefix="/api/liveview")

    return app


app = create_app()


@app.get("/")
async def root() -> dict[str, str]:
    return {"service": settings.service_name}
