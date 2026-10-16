"""Per-request cancellation; never forwards an old request to a new session."""

import threading
from collections.abc import Callable

from .errors import BridgeError


class MediaCancellation:
    def __init__(self) -> None:
        self._event = threading.Event()
        self._lock = threading.Lock()
        self._closers: list[Callable[[], None]] = []

    def check(self) -> None:
        if self._event.is_set():
            raise BridgeError("MEDIA_DOWNLOAD_CANCELLED", "Media download preparation was cancelled.", status_code=409)

    def register(self, close: Callable[[], None]) -> None:
        with self._lock:
            cancelled = self._event.is_set()
            if not cancelled:
                self._closers.append(close)
        if cancelled:
            close()
            self.check()

    def unregister(self, close: Callable[[], None]) -> None:
        with self._lock:
            if close in self._closers:
                self._closers.remove(close)

    def cancel(self) -> None:
        with self._lock:
            self._event.set()
            closers, self._closers = self._closers, []
        for close in closers:
            close()
