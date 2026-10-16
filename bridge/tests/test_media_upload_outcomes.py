"""Synthetic Bridge/backend acceptance; never physical-camera evidence."""
from __future__ import annotations

import asyncio
import threading
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from starlette.requests import Request

from open_eos_bridge.app import _run_media_upload, create_app
from open_eos_bridge.errors import BridgeError
from open_eos_bridge.gphoto2 import GPhoto2Engine
from open_eos_bridge.models import MediaItem

from .fakes import FakeRunner


def test_upload_module_is_served_before_app_without_relaxing_authentication() -> None:
    with TestClient(create_app(engine=GPhoto2Engine(FakeRunner()), token="synthetic-token")) as client:
        page = client.get("/")
        module = client.get("/app/media-upload.js")
        denied = client.post("/v1/session/synthetic/media?filename=SYNTHETIC.JPG", content=b"four")
    assert page.text.index('/app/media-upload.js') < page.text.index('/app/app.js')
    assert 'id="media-upload-outcome"' in page.text
    assert module.status_code == 200
    assert "observeReceipt" in module.text
    assert denied.status_code == 401


@pytest.mark.parametrize("existing_name", ["SYNTHETIC.JPG", "synthetic.jpg"])
def test_upload_api_same_target_name_and_size_collision_does_not_dispatch(existing_name: str, tmp_path: Path) -> None:
    runner = FakeRunner()
    runner.uploaded_files[("/store_00010001", existing_name)] = b"old!"
    headers = {"Authorization": "Bearer synthetic-token", "Content-Type": "image/jpeg"}
    app = create_app(engine=GPhoto2Engine(runner, capture_directory=tmp_path), token="synthetic-token")
    with TestClient(app) as client:
        created = client.post("/v1/session", headers={"Authorization": "Bearer synthetic-token"}, json={})
        session_id = created.json()["id"]
        response = client.post(
            f"/v1/session/{session_id}/media?filename=SYNTHETIC.JPG", headers=headers, content=b"new!"
        )
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "MEDIA_ALREADY_EXISTS"
    assert runner.uploaded_files[("/store_00010001", existing_name)] == b"old!"
    assert not any("--upload-file" in command for command in runner.commands)


def test_upload_api_receipt_is_bound_to_successful_target_despite_other_folder_match(tmp_path: Path) -> None:
    runner = FakeRunner()
    runner.uploaded_files[("/store_00010001/DCIM/100CANON", "SYNTHETIC.JPG")] = b"old!"
    headers = {"Authorization": "Bearer synthetic-token", "Content-Type": "image/jpeg"}
    app = create_app(engine=GPhoto2Engine(runner, capture_directory=tmp_path), token="synthetic-token")
    with TestClient(app) as client:
        created = client.post("/v1/session", headers={"Authorization": "Bearer synthetic-token"}, json={})
        session_id = created.json()["id"]
        response = client.post(
            f"/v1/session/{session_id}/media?filename=SYNTHETIC.JPG", headers=headers, content=b"new!"
        )
    assert response.status_code == 201
    receipt = response.json()
    assert isinstance(receipt["id"], str) and receipt["id"]
    assert isinstance(receipt["kind"], str) and receipt["kind"]
    assert receipt["name"] == "SYNTHETIC.JPG"
    assert type(receipt["sizeBytes"]) is int and receipt["sizeBytes"] == 4
    assert runner.uploaded_files[("/store_00010001", "SYNTHETIC.JPG")] == b"new!"
    assert runner.uploaded_files[("/store_00010001/DCIM/100CANON", "SYNTHETIC.JPG")] == b"old!"
    assert sum("--upload-file" in command for command in runner.commands) == 1


def test_pre_dispatch_cancellation_does_not_write_or_reconcile_existing_match(tmp_path: Path) -> None:
    runner = FakeRunner()
    runner.uploaded_files[("/store_00010001", "SYNTHETIC.JPG")] = b"old!"
    session = GPhoto2Engine(runner, capture_directory=tmp_path).open()
    source = tmp_path / "payload"
    source.write_bytes(b"new!")
    cancelled = threading.Event()
    cancelled.set()
    with pytest.raises(BridgeError) as failure:
        session.upload_media("SYNTHETIC.JPG", source, 4, "image/jpeg", cancelled)
    assert failure.value.code == "UPLOAD_CANCELLED"
    assert not any("--upload-file" in command for command in runner.commands)
    assert runner.uploaded_files[("/store_00010001", "SYNTHETIC.JPG")] == b"old!"


def test_server_disconnect_preserves_a_real_successful_worker_receipt(tmp_path: Path) -> None:
    expected = MediaItem(id="synthetic:committed", name="SYNTHETIC.JPG", kind="photo", sizeBytes=4)

    def upload(filename, source, size_bytes, content_type, cancelled):
        assert filename == expected.name and source.read_bytes() == b"new!" and size_bytes == 4
        assert content_type == "image/jpeg"
        assert cancelled.wait(timeout=2), "The synthetic HTTP disconnect must reach the worker"
        return expected

    async def disconnected() -> dict[str, str]:
        return {"type": "http.disconnect"}

    async def exercise() -> MediaItem:
        request = Request({"type": "http", "method": "POST", "path": "/"}, disconnected)
        source = tmp_path / "payload"
        source.write_bytes(b"new!")
        return await _run_media_upload(request, upload, "SYNTHETIC.JPG", source, 4, "image/jpeg")

    assert asyncio.run(exercise()) is expected
