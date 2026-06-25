class CameraError(Exception):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        status_code: int = 400,
        recoverable: bool = True,
    ) -> None:
        self.code = code
        self.message = message
        self.status_code = status_code
        self.recoverable = recoverable
        super().__init__(message)


class CameraNotConnectedError(CameraError):
    def __init__(self) -> None:
        super().__init__(
            "CAMERA_NOT_CONNECTED",
            "Camera is not connected.",
            status_code=409,
            recoverable=True,
        )


class UnsupportedSettingError(CameraError):
    def __init__(self, value: str) -> None:
        super().__init__(
            "UNSUPPORTED_SETTING",
            f"Camera setting is not supported: {value}",
            status_code=422,
            recoverable=True,
        )
