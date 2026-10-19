"""Real API/session/urllib DELETE receipt, synthetic existing item; no camera/network/files."""

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path.cwd() / "bridge"))
from email.message import Message
from io import BytesIO
from unittest.mock import Mock, patch
from urllib.parse import quote, urlsplit

from fastapi.testclient import TestClient

from open_eos_bridge.app import create_app
from open_eos_bridge.ccapi import CcapiEngine, CcapiResponse, UrllibCcapiTransport
from tests.test_ccapi import FakeCcapiTransport


class Peer(FakeCcapiTransport):
    def __init__(self, status, headers, apply):
        super().__init__()
        self.code, self.extra, self.apply = status, headers, apply
        self.exists = True
        self.deletes = []
        self.http = UrllibCcapiTransport()

    def request(self, method, url, **kwargs):
        parsed = urlsplit(url)
        if method == "DELETE" and "/contents/" in parsed.path:
            self.deletes.append([method, parsed.path])
            if self.apply:
                self.exists = False
            raw = BytesIO(b"")
            raw.status, raw.headers = self.code, Message()
            for key, value in self.extra.items():
                raw.headers[key] = value
            opener = Mock()
            opener.open.return_value = raw
            with (
                patch("open_eos_bridge.ccapi.urlopen", return_value=raw),
                patch("open_eos_bridge.ccapi.build_opener", return_value=opener),
            ):
                return self.http.request(method, url, **kwargs)
        if (
            method == "GET"
            and parsed.path == "/ccapi/ver100/contents"
            and parsed.query == "page=1&order=desc"
            and not self.exists
        ):
            return CcapiResponse(200, {"content-type": "application/json"}, b'{"path":[]}')
        return super().request(method, url, **kwargs)


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--phase", choices=("baseline", "current"), required=True)
args = parser.parse_args()

cases = [
    ("range_204", 204, {"Content-Range": "bytes 0-0/1"}, False),
    ("empty_range_204", 204, {"Content-Range": ""}, False),
    (
        "mixed_case_range_204",
        204,
        {"cOnTeNt-RaNgE": "bytes */1", "Content-Length": "0"},
        False,
    ),
    ("complete_204", 204, {}, True),
    ("complete_length0_204", 204, {"Content-Length": "0"}, True),
    ("partial_206", 206, {}, False),
    ("refusal_403", 403, {}, False),
]
for name, code, headers, apply in cases:
    peer = Peer(code, headers, apply)
    app = create_app(ccapi_engine=CcapiEngine(lambda *_, peer=peer: peer))
    with TestClient(app) as client:
        created = client.post(
            "/v1/session",
            json={"engine": "ccapi", "ccapiUrl": "http://synthetic.invalid:8080"},
        )
        assert created.status_code == 201, created.text
        session_id = created.json()["id"]
        base = "/v1/session/" + session_id
        listing = client.get(base + "/media")
        assert listing.status_code == 200, listing.text
        item = listing.json()["items"][0]
        session = app.state.session_manager.get(session_id)
        assert item["id"] in session._media_cache
        result = client.delete(base + "/media/" + quote(item["id"], safe=""))
        cache_removed = item["id"] not in session._media_cache
        assert len(peer.deletes) == 1
        confirmed = code == 204 and (
            args.phase == "baseline" or not any(key.casefold() == "content-range" for key in headers)
        )
        expected = 204 if confirmed else 403 if code == 403 else 502
        assert result.status_code == expected, result.text
        assert cache_removed == confirmed
        fresh = client.get(base + "/media")
        fresh_present = item["id"] in [x["id"] for x in fresh.json()["items"]]
        assert fresh_present == (not apply), fresh.text
        assert len(peer.deletes) == 1
        print(
            json.dumps(
                {
                    "variant": name,
                    "camera_status": code,
                    "camera_headers": headers,
                    "synthetic_delete_applied": apply,
                    "api_status": result.status_code,
                    "api_headers": dict(result.headers),
                    "api_body": result.text,
                    "item": item,
                    "cache_removed_before_fresh_read": cache_removed,
                    "synthetic_item_exists": peer.exists,
                    "fresh_listing_contains_item": fresh_present,
                    "mutation_count_after_read": len(peer.deletes),
                },
                separators=(",", ":"),
            )
        )
