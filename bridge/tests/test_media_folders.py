import pytest

from open_eos_bridge.media_folders import camera_folder_fields
from open_eos_bridge.models import MediaItem


@pytest.mark.parametrize(
    "fields",
    [
        {},
        {"folderId": None, "folderLabel": None},
        {"folderId": "folder:one"},
        {"folderLabel": "card1/100CANON"},
        {"folderId": 17, "folderLabel": "card1/100CANON"},
        {"folderId": "folder:one", "folderLabel": {"path": "card1/100CANON"}},
        {"folderId": [], "folderLabel": True},
        {"folderId": " ", "folderLabel": "card1/100CANON"},
        {"folderId": "folder:one", "folderLabel": "\u2003"},
        {"folderId": "x" * 4097, "folderLabel": "card1/100CANON"},
        {"folderId": "folder:one", "folderLabel": "x" * 1025},
        {"folderId": "\U0001f4f7" * 2049, "folderLabel": "card1/100CANON"},
        {"folderId": "folder:one", "folderLabel": "\U0001f4f7" * 513},
        {"folderId": "folder:\x00one", "folderLabel": "card1/100CANON"},
        {"folderId": "folder:one", "folderLabel": "card1/\n100CANON"},
        {"folderId": "folder:\x7fone", "folderLabel": "card1/100CANON"},
        {"folderId": "folder:one", "folderLabel": "card1/\x9f100CANON"},
    ],
)
def test_unknown_or_malformed_folder_metadata_does_not_reject_media(fields: dict) -> None:
    item = MediaItem(id="opaque-item", name="IMG_0001.JPG", kind="image", **fields)

    wire = item.model_dump(mode="json", by_alias=True)
    assert wire["id"] == "opaque-item"
    assert wire["name"] == "IMG_0001.JPG"
    assert wire["folderId"] is None
    assert wire["folderLabel"] is None


def test_folder_pair_accepts_exact_limits_and_keeps_ids_opaque() -> None:
    item = MediaItem(
        id="opaque-item", name="IMG_0001.JPG", kind="image",
        folder_id="x" * 4096, folder_label="相" * 1024,
    )

    wire = item.model_dump(mode="json", by_alias=True)
    assert wire["folderId"] == "x" * 4096
    assert wire["folderLabel"] == "相" * 1024


def test_folder_pair_counts_supplementary_characters_like_native_clients() -> None:
    item = MediaItem(
        id="opaque-item", name="IMG_0001.JPG", kind="image",
        folder_id="\U0001f4f7" * 2048, folder_label="\U0001f4f7" * 512,
    )
    assert item.folder_id == "\U0001f4f7" * 2048
    assert item.folder_label == "\U0001f4f7" * 512


def test_camera_folder_identifier_is_stable_and_separates_camera_paths_and_providers() -> None:
    first = camera_folder_fields("gphoto2-folder", "/store_a/DCIM/100CANON", "store_a/DCIM/100CANON")
    second = camera_folder_fields("gphoto2-folder", "/store_b/DCIM/100CANON", "store_b/DCIM/100CANON")
    assert first == camera_folder_fields("gphoto2-folder", "/store_a/DCIM/100CANON", "store_a/DCIM/100CANON")
    assert first["folder_id"] != second["folder_id"]
    assert "store_a" not in first["folder_id"]
    assert first["folder_id"] != camera_folder_fields("ccapi-folder", "/store_a/DCIM/100CANON", "same")["folder_id"]


@pytest.mark.parametrize("folder", ["", "x" * 4097, "/card1/\x00bad", "/card1/\ud800"])
def test_invalid_camera_folder_provenance_remains_optional(folder: str) -> None:
    assert camera_folder_fields("camera-folder", folder, "100CANON") == {}
