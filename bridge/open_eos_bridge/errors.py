from __future__ import annotations


class BridgeError(Exception):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        status_code: int = 400,
        feature: str | None = None,
        engine: str | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
        self.feature = feature
        self.engine = engine


def unsupported(feature: str, engine: str, message: str | None = None) -> BridgeError:
    return BridgeError(
        "UNSUPPORTED_FEATURE",
        message or f"{feature} is not supported by the {engine} engine for this camera.",
        status_code=409,
        feature=feature,
        engine=engine,
    )
