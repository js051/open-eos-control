"""Offline synthetic-fixture diagnosis, not Android/GPU or physical-camera validation.

Uses existing FFmpeg/ffprobe only. Does not install tools or launch an emulator.
Pass the saved android-device2-results.json to compare the terminal native samples.
"""

import argparse
import base64
import hashlib
import json
import math
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "android/app/src/androidTest/java/dev/openeos/control/data/NativeRtpLutRenderingTest.kt"
POSITIONS = ((16, 12), (48, 12), (16, 36), (48, 36))
EXPECTED_SHA256 = "b48b0a7e9a6a54e6b3f34298ef8cbde86034fe1ed850bb3035f1d35c43d88fdf"
GFXSTREAM_SOURCE = (
    "https://android.googlesource.com/platform/hardware/google/gfxstream/+/"
    "d047a57228332d995d36600792fa9ccc26cf8ae6/host/gl/YUVConverter.cpp"
)


def bytes_rgb(values):
    return [min(255, max(0, math.floor(value + 0.5))) for value in values]


def bt709_rgb(y, cb, cr):
    luma = (y - 16) * 255 / 219
    cb = (cb - 128) * 255 / 224
    cr = (cr - 128) * 255 / 224
    kr, kb = 0.2126, 0.0722
    kg = 1 - kr - kb
    return bytes_rgb((
        luma + 2 * (1 - kr) * cr,
        luma - 2 * kb * (1 - kb) / kg * cb - 2 * kr * (1 - kr) / kg * cr,
        luma + 2 * (1 - kb) * cb,
    ))


def gfxstream_default_rgb(y, cb, cr):
    # The fixed offsets differ slightly from exact 16/255 and 128/255.
    # Preserve those coefficients: they explain all twelve observed bytes exactly.
    y, cb, cr = y / 255 - 0.0625, cb / 255 - 0.5, cr / 255 - 0.5
    return bytes_rgb(255 * value for value in (
        1.1643835616438356 * y + 1.5960267857142856 * cr,
        1.1643835616438356 * y - 0.39176229009491365 * cb - 0.8129676472377708 * cr,
        1.1643835616438356 * y + 2.017232142857143 * cb,
    ))


def diagnose(results_path):
    source = FIXTURE.read_text()
    encoded = re.search(r'Base64.getDecoder\(\).decode\("([A-Za-z0-9+/=]+)"\)', source)
    if encoded is None:
        raise ValueError("Missing bounded synthetic fixture")
    payload = base64.b64decode(encoded.group(1), validate=True)
    digest = hashlib.sha256(payload).hexdigest()
    if digest != EXPECTED_SHA256:
        raise ValueError("The embedded fixture changed; re-establish the diagnosis")
    expected_block = source.split("val expected = arrayOf(", 1)[1].split("for (", 1)[0]
    expected = [
        [int(value.strip()) for value in match.split(",")]
        for match in re.findall(r"intArrayOf\(([\d, ]+)\)", expected_block)
    ]
    if len(expected) != 4 or any(len(sample) != 3 for sample in expected):
        raise ValueError("Unexpected fixture expectation shape")
    with tempfile.TemporaryDirectory(prefix="eos-rtp-color-") as directory:
        stream = Path(directory) / "synthetic.h264"
        stream.write_bytes(payload)
        metadata = json.loads(subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries",
             "stream=width,height,pix_fmt,color_space,color_transfer,color_primaries,color_range",
             "-of", "json", str(stream)],
            check=True, capture_output=True, text=True, timeout=15,
        ).stdout)["streams"][0]
        planes = subprocess.run(
            ["ffmpeg", "-v", "error", "-threads", "1", "-i", str(stream),
             "-frames:v", "1", "-pix_fmt", "yuv420p", "-f", "rawvideo", "-"],
            check=True, capture_output=True, timeout=15,
        ).stdout
    if metadata != {
        "width": 64, "height": 48, "pix_fmt": "yuv420p", "color_range": "tv",
        "color_space": "bt709", "color_transfer": "bt709", "color_primaries": "bt709",
    } or len(planes) != 4608:
        raise ValueError("Fixture is not the established 64x48 limited BT.709 image")
    samples = []
    for (x, y), expected_rgb in zip(POSITIONS, expected, strict=True):
        yuv = [planes[y * 64 + x], planes[3072 + y // 2 * 32 + x // 2],
               planes[3840 + y // 2 * 32 + x // 2]]
        samples.append({
            "xy": [x, y], "decodedYcbcr8": yuv, "originalExpectedRgb8": expected_rgb,
            "bt709Rgb8": bt709_rgb(*yuv), "gfxstreamDefaultBt601Rgb8": gfxstream_default_rgb(*yuv),
        })
    recorded = json.loads(results_path.read_text())
    native = {}
    for api in ("34", "36"):
        case = next(case for case in recorded[api]["cases"] if case["name"] ==
                    "dev.openeos.control.data.NativeRtpLutRenderingTest.realH264ToGpuSurfacePreservesRowsAndPostLutScopes")
        match = re.search(r"identity phase=captured.*?decoder=([^ ]+) format=\[([^\]]+)\].*?rgb=\[([^\]]+)\]",
                          case["failure"])
        if match is None:
            raise ValueError(f"API {api} lacks the original identity capture")
        actual = [[int(channel) for channel in point.split(":")[1].split(",")]
                  for point in match.group(3).split(";")]
        predicted = [sample["gfxstreamDefaultBt601Rgb8"] for sample in samples]
        if actual != predicted:
            raise ValueError(f"API {api} no longer matches this exact host-conversion diagnosis")
        native[api] = {
            "decoder": match.group(1), "format": match.group(2), "identityRgb8": actual,
            "all12ChannelsExactlyMatchGfxstreamDefault": actual == predicted,
        }
    return {
        "status": "OFFLINE_DIAGNOSIS_ONLY",
        "fixtureSha256": digest, "metadata": metadata, "samples": samples,
        "nativePriorFailure": native, "gfxstreamSource": GFXSTREAM_SOURCE,
        "correctedNativeExecution": "NOT_RUN", "physicalCameraValidation": "NOT_RUN",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(diagnose(args.results), ensure_ascii=False, indent=2))
