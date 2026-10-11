from __future__ import annotations

from enum import StrEnum


class ConnectionFailureReason(StrEnum):
    """Bounded camera discovery evidence; never addresses, credentials, or response text."""

    AUTHENTICATION_REJECTED = "AUTHENTICATION_REJECTED"
    TLS_ERROR = "TLS_ERROR"
    HOST_NOT_FOUND = "HOST_NOT_FOUND"
    TIMEOUT = "TIMEOUT"
    UNREACHABLE = "UNREACHABLE"
    HTTP_ERROR = "HTTP_ERROR"
    DISCOVERY_FAILED = "DISCOVERY_FAILED"
    ENDPOINT_NOT_FOUND = "ENDPOINT_NOT_FOUND"


class BridgeError(Exception):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        status_code: int = 400,
        feature: str | None = None,
        engine: str | None = None,
        connection_reason: ConnectionFailureReason | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
        self.feature = feature
        self.engine = engine
        self.connection_reason = connection_reason if isinstance(connection_reason, ConnectionFailureReason) else None


def unsupported(feature: str, engine: str, message: str | None = None) -> BridgeError:
    return BridgeError(
        "UNSUPPORTED_FEATURE",
        message or f"{feature} is not supported by the {engine} engine for this camera.",
        status_code=409,
        feature=feature,
        engine=engine,
    )
