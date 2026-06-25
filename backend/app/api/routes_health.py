from fastapi import APIRouter

from app.config import settings

router = APIRouter(tags=["health"])


@router.get("/health")
async def health() -> dict[str, bool | str]:
    return {"ok": True, "service": settings.service_name}
