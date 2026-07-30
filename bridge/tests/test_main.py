from __future__ import annotations

from unittest.mock import patch

import pytest

from open_eos_bridge.main import bridge_settings, run


def test_bridge_settings_validate_port_and_non_loopback_token(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("OPEN_EOS_BRIDGE_TOKEN", raising=False)

    assert bridge_settings(host="127.0.0.1", port=18181) == ("127.0.0.1", 18181, None)

    with pytest.raises(SystemExit, match="OPEN_EOS_BRIDGE_TOKEN"):
        bridge_settings(host="0.0.0.0", port=18181)
    with pytest.raises(SystemExit, match="1 to 65535"):
        bridge_settings(host="127.0.0.1", port=0)
    with pytest.raises(SystemExit, match="1 to 65535"):
        bridge_settings(host="127.0.0.1", port=70000)


def test_run_schedules_ready_callback_without_exposing_token() -> None:
    ready: list[tuple[str, int]] = []

    with (
        patch("open_eos_bridge.main.create_app", return_value="app") as create_app,
        patch("open_eos_bridge.main.uvicorn.run") as uvicorn_run,
    ):
        run(
            host="127.0.0.1",
            port=19181,
            token="memory-only-token",
            on_ready=lambda host, port: ready.append((host, port)),
        )

    assert ready == [("127.0.0.1", 19181)]
    create_app.assert_called_once_with(token="memory-only-token")
    uvicorn_run.assert_called_once_with(
        "app",
        host="127.0.0.1",
        port=19181,
        log_level="info",
    )
