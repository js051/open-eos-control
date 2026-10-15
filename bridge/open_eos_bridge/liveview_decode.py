"""Bounded, isolated first-JPEG decode for source admission, not display proof."""
from __future__ import annotations

import multiprocessing
import threading
import time

from .errors import BridgeError

MAX_ENCODED_BYTES = 12 * 1024 * 1024
MAX_PIXELS = 64_000_000
MAX_SIDE = 16384
DECODE_TIMEOUT_SECONDS = 5.0
_SLOT = threading.BoundedSemaphore(1)


def _decode_worker(data: bytes, connection) -> None:
    try:
        from io import BytesIO

        from PIL import Image, ImageFile

        ImageFile.LOAD_TRUNCATED_IMAGES = False
        with Image.open(BytesIO(data)) as image:
            if image.format != 'JPEG' or not (0 < image.width <= MAX_SIDE and 0 < image.height <= MAX_SIDE):
                raise ValueError('invalid JPEG dimensions')
            if image.width * image.height > MAX_PIXELS:
                raise ValueError('JPEG pixel limit')
            image.load()  # Header/cgImage-style existence alone is not decoder output.
        connection.send(True)
    except Exception:
        connection.send(False)
    finally:
        connection.close()


def _reap(process) -> None:
    try:
        process.join()
        process.close()
    finally:
        _SLOT.release()


def validate_first_jpeg(data: bytes) -> None:
    """Do not publish a JPEG/multipart candidate until real decode succeeds."""
    acquired = False
    receiver = sender = process = None
    release_slot = True
    try:
        if (not 4 <= len(data) <= MAX_ENCODED_BYTES or not data.startswith(b'\xff\xd8')
                or not data.endswith(b'\xff\xd9')):
            raise ValueError('invalid JPEG envelope or encoded size')
        acquired = _SLOT.acquire(blocking=False)
        if not acquired:
            raise ValueError('first-frame decoder busy')
        context = multiprocessing.get_context('spawn')
        receiver, sender = context.Pipe(duplex=False)
        process = context.Process(target=_decode_worker, args=(data, sender), daemon=True)
        deadline = time.monotonic() + DECODE_TIMEOUT_SECONDS
        process.start()
        sender.close()
        process.join(max(0, deadline - time.monotonic()))
        if process.is_alive():
            raise ValueError('first-frame decode deadline')
        if process.exitcode != 0 or not receiver.poll() or receiver.recv() is not True:
            raise ValueError('first-frame JPEG decode failed')
    except (ValueError, OSError, EOFError, RuntimeError) as error:
        raise BridgeError('LIVE_VIEW_DECODE_FAILED', 'The first Live View JPEG could not be decoded within its limit.',
                          status_code=502, feature='LIVE_VIEW') from error
    finally:
        if process is not None:
            if process.pid is not None and process.is_alive():
                process.kill()
                process.join(1.0)
            if process.pid is not None and process.is_alive():
                release_slot = False
                threading.Thread(target=_reap, args=(process,), daemon=True).start()
            else:
                process.close()
        if receiver is not None:
            receiver.close()
        if sender is not None:
            sender.close()
        if acquired and release_slot:
            _SLOT.release()
