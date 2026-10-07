"""Synthetic Canon-shaped media faults for the PC capture/delivery browser journey.

The PC still uses the real Bridge and CCAPI transport. Only the camera peer changes
what its card exposes and deliberately interrupts an original-file response.
This test entry point is not part of the simulator's installed ``main`` module.
"""

from io import BytesIO

from fastapi import HTTPException, Request
from fastapi.responses import JSONResponse, Response, StreamingResponse
from PIL import Image

import main

app = main.app
OLD_A = "SYNTHETIC_OLD_A.JPG"
OLD_B = "SYNTHETIC_OLD_B.JPG"
fixture = {
    "mode": "old",
    "short_original": False,
    "listing_reads": 0,
    "original_reads": 0,
    "thumbnail_reads": 0,
    "preview_reads": 0,
}


def jpeg(width: int, height: int, color: tuple[int, int, int]) -> bytes:
    output = BytesIO()
    Image.new("RGB", (width, height), color=color).save(output, format="JPEG", quality=90)
    return output.getvalue()


REPRESENTATIONS = {
    "thumbnail": jpeg(32, 24, (200, 30, 30)),
    "display": jpeg(160, 120, (30, 200, 30)),
    "original": jpeg(640, 480, (30, 30, 200)),
}


@app.post("/__test/capture-review", include_in_schema=False)
async def configure_capture_review(payload: dict[str, object]) -> dict[str, object]:
    if payload.get("reset") is True:
        await main.reset_test_state()
        main.state["media"] = [
            {"id": name, "name": name, "kind": "image", "capture_time": "2026-10-07T10:00:00Z"}
            for name in (OLD_A, OLD_B)
        ]
        fixture.update(mode="old", short_original=False, listing_reads=0, original_reads=0,
                       thumbnail_reads=0, preview_reads=0)
    if "mode" in payload:
        if payload["mode"] not in {"old", "reordered", "new-second", "new-first", "unavailable"}:
            raise HTTPException(status_code=422, detail="Unknown synthetic listing mode")
        fixture["mode"] = payload["mode"]
    if "short_original" in payload:
        fixture["short_original"] = payload["short_original"] is True
    return dict(fixture)


@app.get("/__test/capture-review", include_in_schema=False)
async def capture_review_state() -> dict[str, object]:
    return {**fixture, "capture_count": main.state["capture_count"],
            "shutter_af_requests": list(main.state["canonical_shutter_af_requests"])}


@app.get("/__test/representation/{kind}", include_in_schema=False)
async def expected_representation(kind: str) -> Response:
    return Response(content=REPRESENTATIONS[kind], media_type="image/jpeg")


@app.middleware("http")
async def synthetic_media_visibility(request: Request, call_next):
    if request.method != "GET":
        return await call_next(request)
    resource = request.url.path
    if resource == "/ccapi/ver100/contents" and request.query_params.get("kind") != "number":
        fixture["listing_reads"] += 1
        if fixture["mode"] == "unavailable":
            return JSONResponse(status_code=503, content={"message": "Synthetic card listing unavailable"})
        names = [OLD_A, OLD_B]
        if main.state["capture_count"]:
            latest = f"SIM_{main.state['capture_count'] + 2:04d}.JPG"
            mode = fixture["mode"]
            if mode == "reordered":
                names = [OLD_B, OLD_A]
            elif mode == "new-second":
                names = [OLD_A, latest, OLD_B]
            elif mode == "new-first":
                names = [latest, OLD_A, OLD_B]
        return JSONResponse(content={"path": [main.canonical_media_path(name) for name in names]})
    if resource.startswith("/ccapi/ver100/contents/card1/100CANON/"):
        kind = request.query_params.get("kind")
        if kind == "info":
            return await call_next(request)
        representation = kind if kind in {"thumbnail", "display"} else "original"
        counter = {"thumbnail": "thumbnail_reads", "display": "preview_reads", "original": "original_reads"}
        fixture[counter[representation]] += 1
        content = REPRESENTATIONS[representation]
        if representation == "original" and fixture["short_original"]:
            async def interrupted_body():
                yield content[: len(content) // 2]
                raise RuntimeError("Synthetic original stream disconnected before its declared length")

            return StreamingResponse(interrupted_body(), media_type="image/jpeg",
                                     headers={"content-length": str(len(content))})
        return Response(content=content, media_type="image/jpeg")
    return await call_next(request)
