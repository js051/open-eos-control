from __future__ import annotations

from pathlib import Path

import pytest

from scripts.build_standalone import BUNDLE_NAME, pyinstaller_arguments, version_tuple, windows_version_resource


def test_version_tuple_accepts_semantic_product_version() -> None:
    assert version_tuple("0.1.3") == (0, 1, 3, 0)


@pytest.mark.parametrize("value", ["1.2", "1.2.3.4", "1.x.3", "65536.0.0"])
def test_version_tuple_rejects_invalid_windows_version(value: str) -> None:
    with pytest.raises(ValueError):
        version_tuple(value)


def test_windows_version_resource_contains_product_identity() -> None:
    resource = windows_version_resource("0.1.3")

    assert "filevers=(0, 1, 3, 0)" in resource
    assert "ProductName', 'Open EOS Control'" in resource
    assert f"OriginalFilename', '{BUNDLE_NAME}.exe'" in resource


def test_pyinstaller_arguments_bundle_runtime_static_data_and_version(tmp_path: Path) -> None:
    bridge_root = tmp_path / "bridge"
    output_dir = tmp_path / "dist"
    work_dir = tmp_path / "work"
    version_file = work_dir / "windows-version.txt"
    arguments = pyinstaller_arguments(
        bridge_root=bridge_root,
        output_dir=output_dir,
        work_dir=work_dir,
        version_file=version_file,
    )

    assert str(bridge_root / "scripts" / "standalone_entry.py") == arguments[0]
    assert "--onefile" in arguments
    assert "--console" in arguments
    assert arguments[arguments.index("--collect-data") + 1] == "open_eos_bridge"
    assert arguments[arguments.index("--collect-all") + 1] == "av"
    assert arguments[arguments.index("--collect-submodules") + 1] == "uvicorn"
    assert arguments[arguments.index("--version-file") + 1] == str(version_file)
