"""Real local JPEG bytes; no camera, network, or decode mocks."""
import base64
import json
import threading
from io import BytesIO
from pathlib import Path

VECTORS = json.loads((Path(__file__).parents[2] / "contracts/jpeg-original/v1/jpeg-original-vectors.json").read_text())
JPEG = base64.b64decode(next(row for row in VECTORS["byteVectors"] if row["id"] == "rgb-baseline")["base64"])


def with_comment(data: bytes, size: int) -> bytes:
    chunks = []
    while size:
        length = min(size, 60000)
        chunks.append(b"\xff\xfe" + (length + 2).to_bytes(2, "big") + b"x" * length)
        size -= length
    return data[:2] + b"".join(chunks) + data[2:]


class OpenLiveViewStream(BytesIO):
    """A valid ongoing stream waits for deliberate close, not a closing boundary."""
    def __init__(self, data):
        super().__init__(data)
        self.stopped = threading.Event()

    def readline(self, size=-1):
        result = super().readline(size)
        if not result:
            self.stopped.wait(10)  # fixture-only safety limit; no camera or timer retry
        return result

    def close(self):
        self.stopped.set()
        super().close()
