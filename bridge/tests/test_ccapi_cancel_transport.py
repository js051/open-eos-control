"""Request-scoped urllib cancellation uses real loopback sockets and no camera."""
import socket
import threading
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import Mock

import pytest

from open_eos_bridge.ccapi import UrllibCcapiTransport, _CcapiDownloadAbort, _CcapiDownloadBody
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.media_cancellation import MediaCancellation


@contextmanager
def redirect_peer():
    entered, release = threading.Event(), threading.Event()
    requests = []

    class Peer(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            requests.append(self.path)
            try:
                if self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", "/original")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                self.send_response(200)
                self.send_header("Content-Length", "8")
                self.end_headers()
                self.wfile.write(b"x")
                self.wfile.flush()
                entered.set()
                assert release.wait(5), "fixture body barrier unreleased"
                self.wfile.write(b"1234567")
            except (BrokenPipeError, ConnectionResetError):
                pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Peer)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", entered, release, requests
    finally:
        release.set()
        server.shutdown()
        server.server_close()
        thread.join(2)


def test_redirected_body_is_cancelled_without_waiting_for_peer():
    cancellation = MediaCancellation()
    finished, reading = threading.Event(), threading.Event()
    errors = []
    with redirect_peer() as (origin, entered, release, requests):
        response = UrllibCcapiTransport().open_cancellable_stream(
            "GET", origin + "/redirect", cancellation=cancellation, timeout=4,
        )

        def read():
            try:
                reading.set()
                response.body.read(8)
            except Exception as error:
                errors.append(error)
            finally:
                response.close()
                finished.set()

        worker = threading.Thread(target=read, daemon=True)
        worker.start()
        try:
            assert entered.wait(1) and reading.wait(1)
            cancellation.cancel()
            assert finished.wait(1), "cancel waited for the stalled peer"
            assert not release.is_set()
            assert len(errors) == 1 and isinstance(errors[0], BridgeError)
            assert errors[0].code == "MEDIA_DOWNLOAD_CANCELLED"
            assert requests == ["/redirect", "/original"]
            assert not cancellation._closers
        finally:
            release.set()
            worker.join(2)


def test_single_original_redirect_is_not_followed():
    cancellation = MediaCancellation()
    with redirect_peer() as (origin, entered, _release, requests):
        response = UrllibCcapiTransport().open_cancellable_stream(
            "GET", origin + "/redirect", cancellation=cancellation, follow_redirects=False,
        )
        try:
            assert response.status == 302
            assert requests == ["/redirect"]
            assert not entered.is_set()
        finally:
            response.close()
        assert not cancellation._closers


def test_socket_registered_after_cancel_is_shut_down():
    cancellation = MediaCancellation()
    owner = _CcapiDownloadAbort(cancellation)
    cancellation.cancel()
    left, right = socket.socketpair()
    try:
        right.settimeout(1)
        with pytest.raises(BridgeError, match="cancelled"):
            owner.watch(left)
        assert right.recv(1) == b""
    finally:
        owner.release()
        left.close()
        right.close()


def test_released_owner_cannot_interrupt_later_request():
    old_cancellation, new_cancellation = MediaCancellation(), MediaCancellation()
    old_owner, new_owner = _CcapiDownloadAbort(old_cancellation), _CcapiDownloadAbort(new_cancellation)
    old_socket, old_peer = socket.socketpair()
    new_socket, new_peer = socket.socketpair()
    try:
        old_owner.watch(old_socket)
        old_owner.release()
        old_socket.close()
        new_owner.watch(new_socket)
        old_cancellation.cancel()
        new_peer.settimeout(1)
        new_socket.sendall(b"new")
        assert new_peer.recv(3) == b"new"
        assert not old_cancellation._closers
    finally:
        new_owner.release()
        old_socket.close()
        old_peer.close()
        new_socket.close()
        new_peer.close()


def test_redirect_watch_retires_closed_socket():
    cancellation = MediaCancellation()
    owner = _CcapiDownloadAbort(cancellation)
    old_socket, old_peer = socket.socketpair()
    new_socket, new_peer = socket.socketpair()
    try:
        owner.watch(old_socket)
        old_socket.close()
        owner.watch(new_socket)
        assert owner._sockets == [new_socket]
        cancellation.cancel()
        new_peer.settimeout(1)
        assert new_peer.recv(1) == b""
    finally:
        owner.release()
        old_socket.close()
        old_peer.close()
        new_socket.close()
        new_peer.close()


def test_body_close_exception_still_retires_cancellation_callback():
    cancellation = MediaCancellation()
    owner = _CcapiDownloadAbort(cancellation)
    body = Mock()
    body.close.side_effect = OSError("synthetic close failure")
    wrapped = _CcapiDownloadBody(body, owner)
    with pytest.raises(OSError, match="synthetic close failure"):
        wrapped.close()
    assert not cancellation._closers
    cancellation.cancel()
