from __future__ import annotations

from unittest.mock import patch

import pytest

from open_eos_bridge.standalone import (
    browser_url,
    environment_browser_enabled,
    main,
    wait_for_bridge_and_open,
)


class FakeResponse:
    def __init__(self, status: int) -> None:
        self.status = status

    def __enter__(self) -> FakeResponse:
        return self

    def __exit__(self, *_: object) -> None:
        return None


def test_browser_url_is_limited_to_loopback() -> None:
    assert browser_url("127.0.0.1", 18181) == "http://127.0.0.1:18181/"
    assert browser_url("localhost", 18181) == "http://localhost:18181/"
    assert browser_url("::1", 18181) == "http://[::1]:18181/"
    assert browser_url("0.0.0.0", 18181) is None
    assert browser_url("192.168.1.10", 18181) is None


@pytest.mark.parametrize("value", [None, "", "1", "true", "YES", "on"])
def test_environment_browser_enabled_accepts_true_values(value: str | None) -> None:
    assert environment_browser_enabled(value) is True


@pytest.mark.parametrize("value", ["0", "false", "NO", "off"])
def test_environment_browser_enabled_accepts_false_values(value: str) -> None:
    assert environment_browser_enabled(value) is False


def test_environment_browser_enabled_rejects_ambiguous_value() -> None:
    with pytest.raises(SystemExit, match="must be true or false"):
        environment_browser_enabled("sometimes")


def test_wait_for_bridge_opens_only_after_success() -> None:
    statuses = iter([OSError("not ready"), FakeResponse(503), FakeResponse(200)])
    opened: list[str] = []
    sleeps: list[float] = []

    def request(*_: object, **__: object) -> FakeResponse:
        value = next(statuses)
        if isinstance(value, Exception):
            raise value
        return value

    assert wait_for_bridge_and_open(
        "http://127.0.0.1:18181/",
        opener=opened.append,
        request=request,
        sleep=sleeps.append,
        attempts=3,
        delay_seconds=0.25,
    )
    assert opened == ["http://127.0.0.1:18181/"]
    assert sleeps == [0.25, 0.25]


def test_wait_for_bridge_never_opens_after_timeout() -> None:
    opened: list[str] = []

    assert not wait_for_bridge_and_open(
        "http://127.0.0.1:18181/",
        opener=opened.append,
        request=lambda *_args, **_kwargs: (_ for _ in ()).throw(OSError("not ready")),
        sleep=lambda _: None,
        attempts=2,
    )
    assert opened == []


def test_standalone_main_passes_safe_overrides_and_can_disable_browser(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("OPEN_EOS_BRIDGE_OPEN_BROWSER", "1")

    with patch("open_eos_bridge.standalone.run") as run_bridge:
        main(["--host", "localhost", "--port", "19181", "--no-browser"])

    run_bridge.assert_called_once_with(host="localhost", port=19181, on_ready=None)


def test_standalone_rejects_non_loopback_without_environment_token(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("OPEN_EOS_BRIDGE_TOKEN", raising=False)

    with pytest.raises(SystemExit, match="OPEN_EOS_BRIDGE_TOKEN"):
        main(["--host", "0.0.0.0", "--no-browser"])


def test_standalone_does_not_accept_process_list_visible_token_argument() -> None:
    with pytest.raises(SystemExit):
        main(["--token", "must-not-be-accepted"])
