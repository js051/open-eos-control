from __future__ import annotations

import json
from pathlib import Path

import pytest

from open_eos_bridge.media_metadata import declared_media_mime, media_response_content_type
from open_eos_bridge.models import MediaItem

FIXTURE = json.loads((Path(__file__).parents[2] / 'contracts/media-type/v1/media-type-vectors.json').read_text())


@pytest.mark.parametrize('row', FIXTURE['mimeAliasCases'], ids=lambda row: row['id'])
def test_raw_mime_alias_contract(row: dict[str, object]) -> None:
    if row['expectedValid']:
        assert declared_media_mime(row['fields']) == row['expectedValue']
    else:
        with pytest.raises(ValueError):
            declared_media_mime(row['fields'])


def test_optional_mime_serializes_truthful_absence_without_changing_default() -> None:
    absent = MediaItem(id='synthetic', name='FRAME.JPG', kind='image', content_type=None)
    assert absent.model_dump(by_alias=True)['contentType'] is None
    assert MediaItem(id='synthetic', name='FRAME.JPG', kind='image').content_type == 'application/octet-stream'


@pytest.mark.parametrize('value', [None, '', ' ', 'image/', 'image/jpeg\n', 'image/jpeg; x=\x85', 'image/JPEK'])
def test_http_header_fallback_does_not_rewrite_metadata(value: str | None) -> None:
    item = MediaItem(id='synthetic', name='FRAME.JPG', kind='image', content_type=value)
    assert media_response_content_type(item.content_type) == 'application/octet-stream'
    assert item.content_type == value


def test_http_header_uses_declared_type_without_unsafe_parameters() -> None:
    assert media_response_content_type(' IMAGE/JPEG; quality=90') == 'IMAGE/JPEG'
