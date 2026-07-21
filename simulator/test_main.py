from fastapi.testclient import TestClient

from main import app, state


client = TestClient(app)


def setup_function() -> None:
    state["capture_count"] = 0
    state["half_pressed"] = False
    state["media"] = [
        {
            "id": "SIM_0002.PNG",
            "name": "SIM_0002.PNG",
            "kind": "image",
            "capture_time": "2026-07-21T10:00:02+08:00",
        },
        {
            "id": "SIM_0001.PNG",
            "name": "SIM_0001.PNG",
            "kind": "image",
            "capture_time": "2026-07-21T10:00:01+08:00",
        },
    ]


def test_half_press_and_release_are_stateful() -> None:
    half_press = client.post("/ccapi/shutter/half-press", json={})
    release = client.post("/ccapi/shutter/release", json={})

    assert half_press.status_code == 200
    assert half_press.json()["half_pressed"] is True
    assert release.status_code == 200
    assert release.json()["half_pressed"] is False
    assert state["half_pressed"] is False


def test_capture_adds_a_downloadable_media_item() -> None:
    capture = client.post("/ccapi/capture/still", json={"af": True})
    media = client.get("/ccapi/media")
    added = media.json()["items"][0]
    download = client.get(f"/ccapi/media/{added['id']}")

    assert capture.status_code == 200
    assert capture.json()["capture_count"] == 1
    assert added["name"] == "SIM_0003.PNG"
    assert added["size_bytes"] > 0
    assert download.status_code == 200
    assert download.headers["content-type"] == "image/png"
    assert download.content.startswith(b"\x89PNG\r\n\x1a\n")


def test_unknown_media_returns_not_found() -> None:
    response = client.get("/ccapi/media/DOES_NOT_EXIST.PNG")

    assert response.status_code == 404
