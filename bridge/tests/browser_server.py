import os
from pathlib import Path

from open_eos_bridge.app import create_app
from open_eos_bridge.gphoto2 import GPhoto2Engine

from .fakes import FakeRunner

_capture_directory = Path(os.environ["OPEN_EOS_BROWSER_CAPTURE_DIR"])
app = create_app(
    engine=GPhoto2Engine(
        FakeRunner(),
        capture_directory=_capture_directory,
    )
)
