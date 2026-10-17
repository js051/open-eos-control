"""Real ASGI/session/provider lifecycle with synthetic camera I/O only."""
from __future__ import annotations

import asyncio
import importlib
import threading
from contextlib import nullcontext
from pathlib import Path
from unittest.mock import Mock

import httpx
import pytest

from open_eos_bridge.app import create_app
from open_eos_bridge.gphoto2 import GPhoto2Engine
from open_eos_bridge.models import SessionCreateRequest

from .fakes import FakeRunner

AUTH = {"Authorization": "Bearer synthetic-token"}
UPLOAD_HEADERS = {**AUTH, "Content-Type": "image/jpeg", "Content-Length": "4"}


def application(runner, tmp_path):
    engine = GPhoto2Engine(runner, capture_directory=tmp_path)
    app = create_app(engine=engine, edsdk_engine=engine, token="synthetic-token")
    manager = app.state.session_manager
    identifier = manager.create(SessionCreateRequest()).id
    return app, manager, identifier, manager.get(identifier)


async def reached(event):
    assert await asyncio.wait_for(asyncio.to_thread(event.wait, 2), 3), "Lifecycle boundary was not reached"


def observe_worker(monkeypatch):
    module = importlib.import_module("open_eos_bridge.app")
    original = module._run_media_upload
    observation = {}
    entered = threading.Event()

    async def run(request, upload, *arguments):
        def worker(filename, source, size, content_type, cancelled):
            observation.update(source=source, cancelled=cancelled)
            entered.set()
            return upload(filename, source, size, content_type, cancelled)

        return await original(request, worker, *arguments)

    monkeypatch.setattr(module, "_run_media_upload", run)
    return observation, entered


@pytest.mark.parametrize("retirement", ["draining", "closed", "replacement", "reused-id"])
def test_staged_body_cannot_dispatch_after_session_retirement(retirement, tmp_path, monkeypatch):
    if retirement == "reused-id":
        monkeypatch.setattr("open_eos_bridge.sessions.secrets.token_urlsafe", lambda _: "synthetic-session")
    runner = FakeRunner()
    app, manager, identifier, session = application(runner, tmp_path)
    retired = threading.Event()
    guard = manager._bulb_requests_for(identifier)
    finish_retirement = guard.finish_retirement

    def finish(operation):
        retired.set()  # Registration was removed; provider cleanup may still be queued.
        finish_retirement(operation)

    monkeypatch.setattr(guard, "finish_retirement", finish)

    async def exercise():
        receiving, complete_body = asyncio.Event(), asyncio.Event()

        async def body():
            receiving.set()
            await complete_body.wait()
            yield b"new!"

        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://synthetic") as client:
            upload = asyncio.create_task(client.post(
                f"/v1/session/{identifier}/media?filename=SYNTHETIC.JPG", headers=UPLOAD_HEADERS, content=body(),
            ))
            await asyncio.wait_for(receiving.wait(), 3)
            admission = manager.camera_mutation(identifier) if retirement == "draining" else nullcontext()
            with admission:
                deleting = asyncio.create_task(client.delete(f"/v1/session/{identifier}", headers=AUTH))
                await reached(retired)
                assert not manager.is_current(identifier, session)
                if retirement == "draining":
                    assert not session._closed and not deleting.done()
                else:
                    assert (await asyncio.wait_for(deleting, 3)).status_code == 204
                    assert session._closed
                    if retirement in {"replacement", "reused-id"}:
                        replacement = manager.create(SessionCreateRequest())
                        assert (replacement.id == identifier) == (retirement == "reused-id")
                complete_body.set()
                response = await asyncio.wait_for(upload, 3)
                assert response.status_code == 404
                assert response.json()["error"]["code"] == "SESSION_NOT_FOUND"
                assert not any("--upload-file" in command for command in runner.commands)
            assert (await asyncio.wait_for(deleting, 3)).status_code == 204

    try:
        asyncio.run(exercise())
    finally:
        manager.close_all()


def test_cancelled_upload_queued_on_admission_never_dispatches_and_keeps_payload_until_drained(tmp_path, monkeypatch):
    runner = FakeRunner()
    app, manager, identifier, session = application(runner, tmp_path)
    provider_upload = Mock(wraps=session.upload_media)
    monkeypatch.setattr(session, "upload_media", provider_upload)
    observation, worker_entered = observe_worker(monkeypatch)
    guard = manager._bulb_requests_for(identifier)
    original_acquire = guard._acquire_execution
    queued = threading.Event()

    async def exercise():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://synthetic") as client:
            with manager.camera_mutation(identifier):
                def acquire():
                    queued.set()
                    original_acquire()

                monkeypatch.setattr(guard, "_acquire_execution", acquire)
                upload = asyncio.create_task(client.post(
                    f"/v1/session/{identifier}/media?filename=SYNTHETIC.JPG",
                    headers=UPLOAD_HEADERS, content=b"new!",
                ))
                await reached(worker_entered)
                await reached(queued)
                upload.cancel()
                await reached(observation["cancelled"])
                assert not upload.done()
                assert observation["source"].read_bytes() == b"new!"
                assert not any("--upload-file" in command for command in runner.commands)
            with pytest.raises(asyncio.CancelledError):
                await asyncio.wait_for(upload, 3)
            assert not observation["source"].exists()
            assert not any("--upload-file" in command for command in runner.commands)
            provider_upload.assert_not_called()

    try:
        asyncio.run(exercise())
    finally:
        manager.close_all()


def test_upload_already_queued_on_original_guard_is_fenced_when_retirement_begins(tmp_path, monkeypatch):
    runner = FakeRunner()
    app, manager, identifier, session = application(runner, tmp_path)
    provider_upload = Mock(wraps=session.upload_media)
    monkeypatch.setattr(session, "upload_media", provider_upload)
    guard = manager._bulb_requests_for(identifier)
    original_acquire, original_retire = guard._acquire_execution, guard.finish_retirement
    queued, retired = threading.Event(), threading.Event()

    def retire(operation):
        retired.set()
        original_retire(operation)

    monkeypatch.setattr(guard, "finish_retirement", retire)

    async def exercise():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://synthetic") as client:
            with manager.camera_mutation(identifier):
                def acquire():
                    queued.set()
                    original_acquire()

                monkeypatch.setattr(guard, "_acquire_execution", acquire)
                posting = asyncio.create_task(client.post(
                    f"/v1/session/{identifier}/media?filename=SYNTHETIC.JPG",
                    headers=UPLOAD_HEADERS, content=b"new!",
                ))
                await reached(queued)
                deleting = asyncio.create_task(client.delete(f"/v1/session/{identifier}", headers=AUTH))
                await reached(retired)
                assert not manager.is_current(identifier, session) and not deleting.done()
            response = await asyncio.wait_for(posting, 3)
            assert response.status_code == 409
            assert response.json()["error"]["code"] == "BULB_SESSION_RETIRED"
            assert (await asyncio.wait_for(deleting, 3)).status_code == 204
            provider_upload.assert_not_called()
            assert not any("--upload-file" in command for command in runner.commands)

    try:
        asyncio.run(exercise())
    finally:
        manager.close_all()


@pytest.mark.parametrize("cancel_http", [False, True])
def test_admitted_upload_finishes_before_close_with_payload_and_commit_readback_retained(
    cancel_http, tmp_path, monkeypatch,
):
    committed, finish_upload, terminal, retired = (threading.Event() for _ in range(4))

    class CommitRunner(FakeRunner):
        def run_cancellable(self, arguments, *, timeout, cancelled):
            result = super().run_cancellable(arguments, timeout=timeout, cancelled=cancelled)
            committed.set()
            assert finish_upload.wait(3), "The synthetic committed upload was not released"
            assert Path(arguments[-1]).read_bytes() == b"new!"
            return result

    runner = CommitRunner()
    app, manager, identifier, session = application(runner, tmp_path)
    observation, _ = observe_worker(monkeypatch)
    original_upload, original_close = session.upload_media, session.close
    guard = manager._bulb_requests_for(identifier)
    original_retire = guard.finish_retirement

    def upload(*args):
        try:
            return original_upload(*args)
        finally:
            assert args[1].exists()
            terminal.set()

    def close():
        assert terminal.is_set(), "Session close overtook admitted upload completion"
        original_close()

    def retire(operation):
        retired.set()
        original_retire(operation)

    monkeypatch.setattr(session, "upload_media", upload)
    monkeypatch.setattr(session, "close", close)
    monkeypatch.setattr(guard, "finish_retirement", retire)

    async def exercise():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://synthetic") as client:
            posting = asyncio.create_task(client.post(
                f"/v1/session/{identifier}/media?filename=SYNTHETIC.JPG",
                headers=UPLOAD_HEADERS, content=b"new!",
            ))
            await reached(committed)
            deleting = asyncio.create_task(client.delete(f"/v1/session/{identifier}", headers=AUTH))
            await reached(retired)
            try:
                assert not manager.is_current(identifier, session)
                assert not deleting.done() and not session._closed
                if cancel_http:
                    posting.cancel()
                    await reached(observation["cancelled"])
                    assert not posting.done()
                assert observation["source"].read_bytes() == b"new!"
            finally:
                finish_upload.set()
            if cancel_http:
                with pytest.raises(asyncio.CancelledError):
                    await asyncio.wait_for(posting, 3)
            else:
                response = await asyncio.wait_for(posting, 3)
                assert response.status_code == 201
                assert response.json()["name"] == "SYNTHETIC.JPG" and response.json()["sizeBytes"] == 4
            assert (await asyncio.wait_for(deleting, 3)).status_code == 204
            assert terminal.is_set() and session._closed
            assert not observation["source"].exists()
            assert runner.uploaded_files[("/store_00010001", "SYNTHETIC.JPG")] == b"new!"
            assert sum("--upload-file" in command for command in runner.commands) == 1

    try:
        asyncio.run(exercise())
    finally:
        finish_upload.set()
        manager.close_all()
