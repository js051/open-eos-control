from __future__ import annotations

import os
from collections.abc import Callable

import uvicorn

from .app import create_app

LOOPBACK_HOSTS = {"127.0.0.1", "::1", "localhost"}


def bridge_settings(
    *,
    host: str | None = None,
    port: int | None = None,
    token: str | None = None,
) -> tuple[str, int, str | None]:
    resolved_host = host or os.environ.get("OPEN_EOS_BRIDGE_HOST", "127.0.0.1")
    raw_port = str(port) if port is not None else os.environ.get("OPEN_EOS_BRIDGE_PORT", "18181")
    try:
        resolved_port = int(raw_port)
    except ValueError as error:
        raise SystemExit("OPEN_EOS_BRIDGE_PORT must be an integer from 1 to 65535.") from error
    if not 1 <= resolved_port <= 65535:
        raise SystemExit("OPEN_EOS_BRIDGE_PORT must be an integer from 1 to 65535.")

    resolved_token = token if token is not None else os.environ.get("OPEN_EOS_BRIDGE_TOKEN")
    if resolved_host not in LOOPBACK_HOSTS and not resolved_token:
        raise SystemExit("OPEN_EOS_BRIDGE_TOKEN is required when binding beyond loopback.")
    return resolved_host, resolved_port, resolved_token


def run(
    *,
    host: str | None = None,
    port: int | None = None,
    token: str | None = None,
    on_ready: Callable[[str, int], None] | None = None,
) -> None:
    resolved_host, resolved_port, resolved_token = bridge_settings(host=host, port=port, token=token)
    if on_ready is not None:
        on_ready(resolved_host, resolved_port)
    uvicorn.run(
        create_app(token=resolved_token),
        host=resolved_host,
        port=resolved_port,
        log_level="info",
    )


if __name__ == "__main__":
    run()
