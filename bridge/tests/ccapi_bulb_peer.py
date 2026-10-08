"""Independent HTTP failure peer; deliberately does not import the simulator or CCAPI implementation."""
from __future__ import annotations

import io
import json
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from PIL import Image

MANUAL_PATH = "/ccapi/ver100/shooting/control/shutterbutton/manual"
AF_PATH = "/ccapi/ver100/shooting/control/af"
DIRECT_PATH = "/ccapi/ver100/shooting/control/shutterbutton"


class BulbPeer:
    def __init__(self) -> None:
        self.commands: list[tuple[str, str, dict]] = []
        self.partial_press_response = False
        self.drop_status = False
        self.drop_release = False
        self.status_failure_after_release = False
        self.status_failures = 0
        self.drop_press = True
        self.reject_release = True
        self.active = False
        self.mode = "Bulb"
        self.manual_method: str | None = "PUT"
        self.direct_shutter = True
        self.dedicated_af = False
        self.media_enabled = False
        self.media_ready = False
        self.capture_count = 0
        self.reads: list[str] = []
        image = io.BytesIO()
        Image.new("RGB", (32, 24), "navy").save(image, format="JPEG")
        self.jpeg = image.getvalue()
        peer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args) -> None:
                pass

            def respond(self, status: int, value: object) -> None:
                body = json.dumps(value).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def disconnect(self) -> None:
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()

            def do_GET(self) -> None:
                path = urlsplit(self.path).path
                if not self.path.startswith("/__test/"):
                    peer.reads.append(self.path)
                if (peer.drop_status or peer.status_failures) and self.path.endswith("/devicestatus/batterylist"):
                    peer.status_failures = max(0, peer.status_failures - 1)
                    self.disconnect()
                elif self.path == "/__test/state":
                    self.respond(200, {"commands": peer.commands, "active": peer.active,
                                       "captureCount": peer.capture_count, "reads": peer.reads})
                elif self.path == "/ccapi":
                    operations = [
                        {"path": "/deviceinformation", "get": True},
                        {"path": "/shooting/settings", "get": True},
                        {"path": "/shooting/settings/shootingmode", "put": True},
                        {"path": "/shooting/control/recbutton", "post": True},
                    ]
                    if peer.manual_method:
                        operations.append({"path": "/shooting/control/shutterbutton/manual",
                                           peer.manual_method.lower(): True})
                    if peer.direct_shutter:
                        operations.append({"path": "/shooting/control/shutterbutton", "post": True})
                    if peer.dedicated_af:
                        operations.append({"path": "/shooting/control/af", "post": True})
                    if peer.media_enabled:
                        operations.append({"path": "/contents", "get": True})
                    self.respond(200, {"ver100": operations})
                elif self.path.endswith("/deviceinformation"):
                    self.respond(200, {"productname": "Synthetic Bulb Peer"})
                elif self.path.endswith("/shooting/settings"):
                    self.respond(200, {"shootingmode": {"value": peer.mode, "ability": ["Bulb", "Manual"]}})
                elif peer.media_enabled and path.endswith("/contents"):
                    names = ["SYNTHETIC_OLD.JPG"]
                    if peer.media_ready and peer.capture_count:
                        names.insert(0, "SYNTHETIC_NEW.JPG")
                    self.respond(200, {"path": [f"/ccapi/ver100/contents/{name}" for name in names]})
                elif peer.media_enabled and path.endswith(".JPG"):
                    self.send_response(200)
                    self.send_header("Content-Type", "image/jpeg")
                    self.send_header("Content-Length", str(len(peer.jpeg)))
                    self.end_headers()
                    self.wfile.write(peer.jpeg)
                else:
                    self.respond(404, {"message": "Not supported by synthetic peer"})

            def do_PUT(self) -> None:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
                if self.path == "/__test/config":
                    for key in (
                        "drop_press", "reject_release", "mode", "partial_press_response", "drop_status",
                        "status_failure_after_release", "direct_shutter", "dedicated_af", "drop_release",
                        "media_enabled", "media_ready", "capture_count", "manual_method",
                    ):
                        if key in body:
                            setattr(peer, key, body[key])
                    self.respond(200, {})
                    return
                peer.commands.append((self.command, self.path, body))
                if self.path in (MANUAL_PATH, AF_PATH, DIRECT_PATH):
                    if body.get("action") in ("full_press", "half_press", "start") or self.path == DIRECT_PATH:
                        peer.active = True
                        if body.get("action") == "full_press" or self.path == DIRECT_PATH:
                            peer.capture_count += 1
                        if peer.drop_press:
                            if peer.partial_press_response:
                                self.send_response(200)
                                self.send_header("Content-Length", "100")
                                self.end_headers()
                                self.wfile.write(b"{")
                                self.wfile.flush()
                            self.disconnect()
                            return
                        if self.path == DIRECT_PATH:
                            peer.active = False
                            if peer.status_failure_after_release:
                                peer.status_failures = 1
                    elif body.get("action") in ("release", "stop"):
                        if peer.reject_release:
                            self.respond(503, {"message": "Synthetic release unavailable"})
                            return
                        peer.active = False
                        if peer.status_failure_after_release:
                            peer.status_failures = 1
                        if peer.drop_release:
                            self.disconnect()
                            return
                self.respond(200, {})

            do_POST = do_PUT

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.origin = f"http://127.0.0.1:{self.server.server_port}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
