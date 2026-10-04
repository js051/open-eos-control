"""Independent HTTP failure peer; deliberately does not import the simulator or CCAPI implementation."""
from __future__ import annotations

import json
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MANUAL_PATH = "/ccapi/ver100/shooting/control/shutterbutton/manual"


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
                if (peer.drop_status or peer.status_failures) and self.path.endswith("/devicestatus/batterylist"):
                    peer.status_failures = max(0, peer.status_failures - 1)
                    self.disconnect()
                elif self.path == "/__test/state":
                    self.respond(200, {"commands": peer.commands, "active": peer.active})
                elif self.path == "/ccapi":
                    self.respond(200, {"ver100": [
                        {"path": "/deviceinformation", "get": True},
                        {"path": "/shooting/settings", "get": True},
                        {"path": "/shooting/settings/shootingmode", "put": True},
                        {"path": "/shooting/control/shutterbutton/manual", "put": True},
                        {"path": "/shooting/control/shutterbutton", "post": True},
                        {"path": "/shooting/control/recbutton", "post": True},
                    ]})
                elif self.path.endswith("/deviceinformation"):
                    self.respond(200, {"productname": "Synthetic Bulb Peer"})
                elif self.path.endswith("/shooting/settings"):
                    self.respond(200, {"shootingmode": {"value": peer.mode, "ability": ["Bulb", "Manual"]}})
                else:
                    self.respond(404, {"message": "Not supported by synthetic peer"})

            def do_PUT(self) -> None:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
                if self.path == "/__test/config":
                    for key in (
                        "drop_press", "reject_release", "mode", "partial_press_response", "drop_status",
                        "status_failure_after_release",
                    ):
                        if key in body:
                            setattr(peer, key, body[key])
                    self.respond(200, {})
                    return
                peer.commands.append((self.command, self.path, body))
                if self.path == MANUAL_PATH:
                    if body.get("action") == "full_press":
                        peer.active = True
                        if peer.drop_press:
                            if peer.partial_press_response:
                                self.send_response(200)
                                self.send_header("Content-Length", "100")
                                self.end_headers()
                                self.wfile.write(b"{")
                                self.wfile.flush()
                            self.disconnect()
                            return
                    elif body.get("action") == "release":
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
