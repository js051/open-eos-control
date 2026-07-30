from __future__ import annotations

import argparse
import shutil
import sys
from collections.abc import Sequence
from pathlib import Path

from open_eos_bridge import __version__

BUNDLE_NAME = "open-eos-control-bridge"


def version_tuple(version: str) -> tuple[int, int, int, int]:
    parts = version.split(".")
    if len(parts) != 3 or any(not part.isdigit() for part in parts):
        raise ValueError(f"Expected a semantic X.Y.Z version, got {version!r}.")
    values = tuple(int(part) for part in parts)
    if any(value > 65535 for value in values):
        raise ValueError("Windows version components must not exceed 65535.")
    return values[0], values[1], values[2], 0


def windows_version_resource(version: str) -> str:
    numeric = version_tuple(version)
    return f"""VSVersionInfo(
  ffi=FixedFileInfo(
    filevers={numeric!r},
    prodvers={numeric!r},
    mask=0x3f,
    flags=0x0,
    OS=0x40004,
    fileType=0x1,
    subtype=0x0,
    date=(0, 0)
  ),
  kids=[
    StringFileInfo([
      StringTable(
        '040904B0',
        [
          StringStruct('CompanyName', 'Open EOS Control contributors'),
          StringStruct('FileDescription', 'Open EOS Control Desktop Bridge'),
          StringStruct('FileVersion', '{version}'),
          StringStruct('InternalName', '{BUNDLE_NAME}'),
          StringStruct('LegalCopyright', 'Apache-2.0'),
          StringStruct('OriginalFilename', '{BUNDLE_NAME}.exe'),
          StringStruct('ProductName', 'Open EOS Control'),
          StringStruct('ProductVersion', '{version}')
        ]
      )
    ]),
    VarFileInfo([VarStruct('Translation', [1033, 1200])])
  ]
)
"""


def pyinstaller_arguments(
    *,
    bridge_root: Path,
    output_dir: Path,
    work_dir: Path,
    version_file: Path,
) -> list[str]:
    entrypoint = bridge_root / "scripts" / "standalone_entry.py"
    icon = bridge_root / "open_eos_bridge" / "static" / "app-icon.png"
    return [
        str(entrypoint),
        "--clean",
        "--noconfirm",
        "--onefile",
        "--console",
        "--noupx",
        "--name",
        BUNDLE_NAME,
        "--paths",
        str(bridge_root),
        "--collect-data",
        "open_eos_bridge",
        "--collect-all",
        "av",
        "--collect-submodules",
        "uvicorn",
        "--icon",
        str(icon),
        "--version-file",
        str(version_file),
        "--distpath",
        str(output_dir),
        "--workpath",
        str(work_dir / "build"),
        "--specpath",
        str(work_dir / "spec"),
    ]


def build(output_dir: Path, work_dir: Path) -> Path:
    if sys.platform != "win32":
        raise SystemExit("The Windows standalone bundle must be built on Windows.")

    try:
        import PyInstaller.__main__
    except ImportError as error:
        raise SystemExit("Install the bridge bundle dependencies before building.") from error

    bridge_root = Path(__file__).resolve().parents[1]
    output_dir = output_dir.resolve()
    work_dir = work_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    work_dir.mkdir(parents=True, exist_ok=True)
    version_file = work_dir / "windows-version.txt"
    version_file.write_text(windows_version_resource(__version__), encoding="utf-8", newline="\n")

    PyInstaller.__main__.run(
        pyinstaller_arguments(
            bridge_root=bridge_root,
            output_dir=output_dir,
            work_dir=work_dir,
            version_file=version_file,
        )
    )

    generated = output_dir / f"{BUNDLE_NAME}.exe"
    artifact = output_dir / f"{BUNDLE_NAME}-windows-x64-{__version__}.exe"
    if not generated.is_file():
        raise SystemExit(f"PyInstaller did not create {generated}.")
    if artifact.exists():
        artifact.unlink()
    shutil.move(str(generated), artifact)
    return artifact


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Build the Windows standalone Desktop Bridge executable.")
    parser.add_argument("--output-dir", type=Path, default=Path("dist"))
    parser.add_argument("--work-dir", type=Path, default=Path("build/standalone"))
    return parser


def main(argv: Sequence[str] | None = None) -> None:
    args = build_argument_parser().parse_args(argv)
    print(build(args.output_dir, args.work_dir))


if __name__ == "__main__":
    main()
