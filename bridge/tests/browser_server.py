import os
from pathlib import Path

from open_eos_bridge.app import create_app
from open_eos_bridge.gphoto2 import GPhoto2Engine

from .fakes import FakeRunner

_capture_directory = Path(os.environ["OPEN_EOS_BROWSER_CAPTURE_DIR"])
runner = FakeRunner()
app = create_app(
    engine=GPhoto2Engine(
        runner,
        capture_directory=_capture_directory,
    )
)


def _sanitized_command(command: tuple[str, ...]) -> list[str]:
    arguments = FakeRunner._without_camera(list(command))
    if "--filename" in arguments:
        filename_index = arguments.index("--filename") + 1
        arguments[filename_index] = "<capture-directory>"
    return arguments


def _control_commands() -> list[list[str]]:
    observed_flags = {
        "--capture-image-and-download",
        "--capture-movie",
        "--capture-preview",
        "--delete-file",
        "--get-file",
        "--get-thumbnail",
        "--set-config-value",
        "--trigger-capture",
    }
    commands = [_sanitized_command(command) for command in runner.commands]
    return [
        command
        for command in commands
        if any(argument in observed_flags for argument in command)
    ]


@app.get("/__test/state", include_in_schema=False)
def browser_test_state() -> dict[str, object]:
    return {
        "commands": _control_commands(),
        "values": {
            path: runner.values[path]
            for path in (
                "/main/imgsettings/iso",
                "/main/capturesettings/autoexposuremode",
                "/main/actions/manualfocusdrive",
                "/main/actions/autofocusdrive",
                "/main/actions/autofocuscancel",
                "/main/actions/viewfinder",
                "/main/actions/eoszoom",
                "/main/actions/eosremoterelease",
                "/main/settings/movierecordtarget",
            )
        },
        "movieStreams": [
            {"closed": stream.closed}
            for stream in runner.movie_streams
        ],
    }
