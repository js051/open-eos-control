"""Real host PyAV reset evidence using only synthetic, in-memory H264 streams."""

from __future__ import annotations

import hashlib
import json
from fractions import Fraction
from io import BytesIO

import av
from PIL import Image

from open_eos_bridge.rtp import ANNEX_B_START_CODE, H264AccessUnit, PyAvH264FrameDecoder

from .test_rtp import _encoded_h264_access_unit, _split_annex_b


def _delayed_dark_access_units() -> tuple[H264AccessUnit, ...]:
    encoder = av.CodecContext.create("libx264", "w")
    encoder.width, encoder.height = 64, 48
    encoder.pix_fmt = "yuv420p"
    encoder.time_base = Fraction(1, 30)
    encoder.max_b_frames = 2
    encoder.gop_size = 30
    encoder.options = {"preset": "ultrafast", "x264-params": "b-adapt=0:scenecut=0"}
    encoder.open()
    packets = []
    for index in range(6):
        frame = av.VideoFrame(64, 48, "yuv420p")
        for plane, value in zip(frame.planes, (32, 128, 128), strict=True):
            plane.update(bytes([value]) * plane.buffer_size)
        frame.pts = index
        packets.extend(encoder.encode(frame))
    packets.extend(encoder.encode(None))

    units = []
    for packet in packets:
        nal_units = tuple(_split_annex_b(bytes(packet)))
        assert nal_units, "delayed-frame fixture must contain Annex B NAL units"
        units.append(H264AccessUnit(
            nal_units=nal_units,
            rtp_timestamp=(packet.pts or 0) * 3000,
            key_frame=any(nal[0] & 0x1F == 5 for nal in nal_units),
            sequence_parameter_set=next((nal for nal in nal_units if nal[0] & 0x1F == 7), None),
            picture_parameter_set=next((nal for nal in nal_units if nal[0] & 0x1F == 8), None),
        ))
    assert len(units) == 6, "each synthetic input frame must have an encoded access unit"
    assert units[0].key_frame
    return tuple(units)


def _fixture_sha256(units: tuple[H264AccessUnit, ...]) -> str:
    payload = b"".join(ANNEX_B_START_CODE + nal for unit in units for nal in unit.nal_units)
    return hashlib.sha256(payload).hexdigest()


def test_real_pyav_epoch_reset_discards_delayed_frames_and_accepts_fresh_idr(record_testsuite_property) -> None:
    old_units = _delayed_dark_access_units()
    fresh_idr = _encoded_h264_access_unit()
    delta = next(unit for unit in old_units if not unit.key_frame)
    decoder, control = PyAvH264FrameDecoder(), PyAvH264FrameDecoder()
    try:
        assert isinstance(decoder._codec, av.CodecContext)
        for unit in old_units:
            assert decoder.decode(unit, emit_frame=False) is None
            assert control.decode(unit, emit_frame=False) is None

        # Establish native buffered output before reset using an identically fed
        # control. A fixture without real delay is a failure, never a skip.
        assert decoder._codec.has_b_frames
        control_delayed = control._codec.decode(None)
        assert control_delayed, "fixture did not establish real delayed decoder output"
        assert all(frame.to_image().convert("L").getextrema()[1] < 40 for frame in control_delayed)

        old_context = decoder._codec
        old_parameters = decoder._sps, decoder._pps
        decoder.reset_for_frame_epoch()
        assert isinstance(decoder._codec, av.CodecContext)
        assert decoder._codec is not old_context
        assert (decoder._sps, decoder._pps) == old_parameters
        assert decoder._waiting_for_key_frame is True

        # The retained context is an observation witness only. Its delayed
        # frames are never submitted to the reset adapter or its consumer.
        abandoned_frames = old_context.decode(None)
        assert len(abandoned_frames) == len(control_delayed)
        assert all(frame.to_image().convert("L").getextrema()[1] < 40 for frame in abandoned_frames)

        assert decoder.decode(delta, emit_frame=True) is None
        assert decoder._waiting_for_key_frame is True
        jpeg = decoder.decode(fresh_idr, emit_frame=True)
        assert jpeg is not None and jpeg.startswith(b"\xff\xd8") and jpeg.endswith(b"\xff\xd9")
        with Image.open(BytesIO(jpeg)) as image:
            assert image.size == (64, 48)
            fresh_luminance = image.convert("L").getextrema()
            assert 60 < fresh_luminance[0] <= fresh_luminance[1] < 120
        assert decoder._waiting_for_key_frame is False

        evidence = {
            "pyav_version": av.__version__,
            "libav_versions": av.library_versions,
            "old_fixture_sha256": _fixture_sha256(old_units),
            "fresh_fixture_sha256": _fixture_sha256((fresh_idr,)),
            "old_access_units": len(old_units),
            "buffered_frames_before_reset_control": len(control_delayed),
            "abandoned_frames_in_tested_old_context": len(abandoned_frames),
            "fresh_jpeg_luminance_range": fresh_luminance,
        }
        for name, value in evidence.items():
            record_testsuite_property(name, json.dumps(value, sort_keys=True))
        print("REAL_CODEC_RESET_EVIDENCE=" + json.dumps(evidence, sort_keys=True))
    finally:
        decoder.close()
        control.close()
