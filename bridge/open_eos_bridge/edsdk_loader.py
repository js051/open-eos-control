from __future__ import annotations

import re
from collections.abc import Iterable
from dataclasses import dataclass
from importlib import metadata
from typing import Protocol, cast

from .edsdk_contract import (
    EDSDK_PROVIDER_API_VERSION,
    EDSDK_PROVIDER_ENTRY_POINT_GROUP,
    EdsdkProvider,
)


class ProviderEntryPoint(Protocol):
    name: str

    def load(self) -> object: ...


@dataclass(frozen=True)
class EdsdkProviderLoadResult:
    provider: EdsdkProvider | None
    detail: str


_PROVIDER_METADATA = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.+ -]{0,127}\Z")


def load_edsdk_provider(
    entry_points: Iterable[ProviderEntryPoint] | None = None,
) -> EdsdkProviderLoadResult:
    candidates = list(entry_points) if entry_points is not None else list(
        metadata.entry_points().select(group=EDSDK_PROVIDER_ENTRY_POINT_GROUP)
    )
    if not candidates:
        return EdsdkProviderLoadResult(
            provider=None,
            detail=(
                "No licensed EDSDK provider is installed. Install one locally through the "
                f"'{EDSDK_PROVIDER_ENTRY_POINT_GROUP}' Python entry-point group."
            ),
        )
    if len(candidates) != 1:
        return EdsdkProviderLoadResult(
            provider=None,
            detail="More than one EDSDK provider is installed; keep exactly one enabled.",
        )

    candidate = candidates[0]
    try:
        factory = candidate.load()
        if not callable(factory):
            return _invalid_provider("entry point is not a zero-argument provider factory")
        provider = factory()
    except Exception as error:  # A local provider must not prevent the Bridge from starting.
        return EdsdkProviderLoadResult(
            provider=None,
            detail=f"The licensed EDSDK provider could not be loaded ({type(error).__name__}).",
        )

    try:
        required = ("api_version", "provider_name", "provider_version", "health", "discover", "open")
        missing = [name for name in required if not hasattr(provider, name)]
        if missing:
            return _invalid_provider(f"provider contract is missing {', '.join(missing)}")
        non_callable = [name for name in ("health", "discover", "open") if not callable(getattr(provider, name))]
        if non_callable:
            return _invalid_provider(f"provider methods are not callable: {', '.join(non_callable)}")
        if getattr(provider, "api_version", None) != EDSDK_PROVIDER_API_VERSION:
            return _invalid_provider(
                f"provider API version must be {EDSDK_PROVIDER_API_VERSION}"
            )
        if not _bounded_text(getattr(provider, "provider_name", None)):
            return _invalid_provider("provider name is invalid")
        if not _bounded_text(getattr(provider, "provider_version", None)):
            return _invalid_provider("provider version is invalid")
    except Exception as error:
        return EdsdkProviderLoadResult(
            provider=None,
            detail=f"The EDSDK provider contract could not be inspected ({type(error).__name__}).",
        )
    return EdsdkProviderLoadResult(provider=cast(EdsdkProvider, provider), detail="Provider loaded.")


def _bounded_text(value: object) -> bool:
    return isinstance(value, str) and _PROVIDER_METADATA.fullmatch(value) is not None


def _invalid_provider(reason: str) -> EdsdkProviderLoadResult:
    return EdsdkProviderLoadResult(
        provider=None,
        detail=f"The installed EDSDK provider is incompatible: {reason}.",
    )
