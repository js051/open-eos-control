from __future__ import annotations

import argparse
import os
import threading
import time
import urllib.error
import urllib.request
import webbrowser
from collections.abc import Callable, Sequence

from . import __version__
from .main import LOOPBACK_HOSTS, run

DEFAULT_READY_ATTEMPTS = 80
DEFAULT_READY_DELAY_SECONDS = 0.1


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="open-eos-control-bridge",
        description="Run the Open EOS Control Desktop Bridge and its local browser control UI.",
    )
    parser.add_argument("--host", help="Override OPEN_EOS_BRIDGE_HOST.")
    parser.add_argument("--port", type=int, help="Override OPEN_EOS_BRIDGE_PORT.")
    parser.add_argument(
        "--no-browser",
        action="store_true",
        help="Do not open the local control UI in the default browser.",
    )
    parser.add_argument("--version", action="version", version=f"%(prog)s {__version__}")
    return parser


def browser_url(host: str, port: int) -> str | None:
    if host not in LOOPBACK_HOSTS:
        return None
    display_host = f"[{host}]" if ":" in host else host
    return f"http://{display_host}:{port}/"


def environment_browser_enabled(value: str | None) -> bool:
    if value is None or not value.strip():
        return True
    normalized = value.strip().casefold()
    if normalized in {"1", "true", "yes", "on"}:
        return True
    if normalized in {"0", "false", "no", "off"}:
        return False
    raise SystemExit("OPEN_EOS_BRIDGE_OPEN_BROWSER must be true or false.")


def wait_for_bridge_and_open(
    url: str,
    *,
    opener: Callable[[str], object] = webbrowser.open,
    request: Callable[..., object] = urllib.request.urlopen,
    sleep: Callable[[float], None] = time.sleep,
    attempts: int = DEFAULT_READY_ATTEMPTS,
    delay_seconds: float = DEFAULT_READY_DELAY_SECONDS,
) -> bool:
    for _ in range(attempts):
        try:
            with request(url, timeout=0.5) as response:
                if getattr(response, "status", None) == 200:
                    opener(url)
                    return True
        except (OSError, TimeoutError, urllib.error.URLError):
            pass
        sleep(delay_seconds)
    return False


def schedule_browser_open(host: str, port: int) -> None:
    url = browser_url(host, port)
    if url is None:
        return
    threading.Thread(
        target=wait_for_bridge_and_open,
        args=(url,),
        name="open-eos-control-browser",
        daemon=True,
    ).start()


def main(argv: Sequence[str] | None = None) -> None:
    args = build_argument_parser().parse_args(argv)
    open_browser = not args.no_browser and environment_browser_enabled(
        os.environ.get("OPEN_EOS_BRIDGE_OPEN_BROWSER")
    )
    run(
        host=args.host,
        port=args.port,
        on_ready=schedule_browser_open if open_browser else None,
    )


if __name__ == "__main__":
    main()
