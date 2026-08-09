from __future__ import annotations

from typing import Protocol

from .engine import CameraEngineSession
from .models import CameraDescriptor

EDSDK_PROVIDER_API_VERSION = 1
EDSDK_PROVIDER_ENTRY_POINT_GROUP = "open_eos_control.edsdk"


class EdsdkProvider(Protocol):
    """SDK-neutral contract implemented by a separately installed licensed provider."""

    api_version: int
    provider_name: str
    provider_version: str

    def health(self) -> tuple[bool, str | None]: ...

    def discover(self) -> list[CameraDescriptor]: ...

    def open(
        self,
        camera_id: str | None = None,
        profile_hint: str | None = None,
    ) -> CameraEngineSession: ...
