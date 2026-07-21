from __future__ import annotations

import os

import uvicorn

from .app import create_app


def run() -> None:
    host = os.environ.get("OPEN_EOS_BRIDGE_HOST", "127.0.0.1")
    port = int(os.environ.get("OPEN_EOS_BRIDGE_PORT", "18181"))
    token = os.environ.get("OPEN_EOS_BRIDGE_TOKEN")
    if host not in {"127.0.0.1", "::1", "localhost"} and not token:
        raise SystemExit("OPEN_EOS_BRIDGE_TOKEN is required when binding beyond loopback.")
    uvicorn.run(create_app(token=token), host=host, port=port, log_level="info")


if __name__ == "__main__":
    run()
