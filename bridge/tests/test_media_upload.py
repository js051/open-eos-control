import pytest

from open_eos_bridge.errors import BridgeError
from open_eos_bridge.media_upload import MAX_UPLOAD_BYTES, validate_upload_request


def test_upload_validation_accepts_photo_video_and_raw_content_types() -> None:
    assert validate_upload_request("photo.JPG", "image/jpeg", "12")[2] == 12
    assert validate_upload_request("movie.MOV", "video/quicktime", "12")[2] == 12
    assert validate_upload_request("raw.CR3", "application/octet-stream", "12")[1] == "image/x-canon-cr3"


@pytest.mark.parametrize(
    ("filename", "expected_content_type"),
    [("photo.JPG", "image/jpeg"), ("movie.MOV", "video/quicktime")],
)
def test_upload_validation_infers_generic_picker_content_type(filename: str, expected_content_type: str) -> None:
    assert validate_upload_request(filename, "application/octet-stream", "12")[1] == expected_content_type
    assert validate_upload_request(filename, None, "12")[1] == expected_content_type


@pytest.mark.parametrize("filename", ["../photo.JPG", "folder/photo.JPG", "photo.exe", "CON.JPG", "photo.JPG\x00"])
def test_upload_validation_rejects_unsafe_or_unrecognized_names(filename: str) -> None:
    with pytest.raises(BridgeError) as failure:
        validate_upload_request(filename, "image/jpeg", "12")
    assert failure.value.code in {"INVALID_UPLOAD_FILENAME", "UNSUPPORTED_UPLOAD_FORMAT"}


def test_upload_validation_rejects_bad_length_type_and_mime() -> None:
    for content_length in (None, "not-a-number", "0", str(MAX_UPLOAD_BYTES + 1)):
        with pytest.raises(BridgeError):
            validate_upload_request("photo.JPG", "image/jpeg", content_length)
    with pytest.raises(BridgeError, match="Content-Type"):
        validate_upload_request("photo.JPG", "text/plain", "12")
