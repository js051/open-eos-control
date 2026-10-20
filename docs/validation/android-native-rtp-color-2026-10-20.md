# Android native RTP color diagnosis, 2026-10-20

## Status

The forced reference-decoder candidate **failed** on API 34 and API 36. It is
withdrawn: the shared production/test factory again delegates directly to
Android's system AVC decoder choice. The rollback is implemented; its verification
is **NOT_RUN**. The original goldfish color failure remains **UNRESOLVED**, not
fixed or waived. This is not `PR ready` or physical-camera evidence.
No color tolerance, fixture byte, shader, color-admission rule, single-access-unit
assertion, or CI gate changed in the rollback.

The original color evidence is the terminal
[run 39376896373](https://github.com/js051/open-eos-control/actions/runs/39376896373)
at `575eb20d006072a133bbb8df6f7f95b3975a6985`. API 34 and API 36 both failed
`NativeRtpLutRenderingTest.realH264ToGpuSurfacePreservesRowsAndPostLutScopes`.
Both reported `c2.goldfish.h264.decoder`, BT.709 standard `1`, limited range `2`,
and SDR transfer `3`, but emitted the same four wrong identity RGB samples.

The earlier [VUI correction](android-native-fixture-diagnostics-2026-10-15.md)
fixed genuinely incomplete metadata. The new run establishes that this correction
was **not sufficient** to fix the host-decoded pixels. Canonical output metadata
alone does not establish that the host YUV-to-RGB stage used that matrix.

The replacement candidate was subsequently tested in
[run 39399454712](https://github.com/js051/open-eos-control/actions/runs/39399454712)
at `a78e7a08840cf063a60a13d3fdfc8fe2ef99349e`:

- On both APIs, `AndroidRtpPresentationJourneyTest.oneAccessUnitDrainsDecodedOutputWithoutWaitingForAnotherPacket`
  timed out after 4,000 ms with one datagram, one access unit, one key frame,
  SPS/PPS present, and zero decoded output pictures.
- On both APIs, the native LUT test selected `c2.android.avc.decoder` and failed
  before color/pixel checks: zero output buffers, output format unobserved, no
  presentation, no copied RGB, and successful cleanup. This is not evidence of
  corrected reference-decoder color.
- The other five RTP presentation tests passed on both APIs. Passing continuing
  streams does not satisfy the retained isolated-first-picture contract.
- Whole-suite totals were API 34: 460 cases / 5 failures; API 36: 460 cases /
  64 failures, not five. The additional API 36 SystemUI-blocked cases and unresolved
  ANR initiating cause are separate from this RTP diagnosis.

## Independently reproduced matrix evidence

The embedded 167-byte fixture still has SHA-256
`b48b0a7e9a6a54e6b3f34298ef8cbde86034fe1ed850bb3035f1d35c43d88fdf`.
Existing local FFmpeg/ffprobe independently confirm 64×48, 8-bit 4:2:0, limited
range, and BT.709 primaries/transfer/matrix. Decoding one frame reproduces the
original YCbCr samples; the PPS, IDR, expected RGB, and positions are untouched.

| Position | Decoded Y/Cb/Cr | Rounded BT.709 RGB | gfxstream default RGB | Native API 34 and 36 |
|---|---|---|---|---|
| 16,12 | 85,116,208 | 224,40,55 | 209,19,57 | 209,19,57 |
| 48,12 | 152,87,66 | 47,200,72 | 60,224,77 | 60,224,77 |
| 16,36 | 86,192,105 | 40,80,217 | 46,75,212 | 46,75,212 |
| 48,36 | 143,107,144 | 177,144,104 | 174,143,107 | 174,143,107 |

The BT.709 column applies the standard limited-range inverse matrix to the decoded
bytes, independently of Android. Every channel is within one level of its original
expected value.

The gfxstream column uses the actual default shader coefficients and offsets from
[YUVConverter.cpp, revision d047a57](https://android.googlesource.com/platform/hardware/google/gfxstream/+/d047a57228332d995d36600792fa9ccc26cf8ae6/host/gl/YUVConverter.cpp).
Its normalized offsets are `0.0625` and `0.5`, rather than exact `16/255` and
`128/255`; retaining that distinction reproduces **all twelve native channel bytes
exactly**, including the small differences from a textbook BT.601 conversion.
The source selects this limited-range BT.601 shader by default and selects BT.709
only for the corresponding color-aspect inputs.

The [goldfish decoder source](https://android.googlesource.com/device/generic/goldfish-opengl/+/5cd042c8586a38b408f390fcef979d58ace3da23/system/codecs/c2/decoders/avcdec/C2GoldfishAvcDec.cpp)
can render to a host color buffer before returning image color metadata, and
updates the framework's VUI color information separately.
[The host decoder](https://android.googlesource.com/platform/hardware/google/aemu/+/7e11d40d0aa8ee3cfa8012c5eeaee01e1e765ccd/host-common/MediaH264DecoderGeneric.cpp)
likewise separates host rendering from returned color-aspect values. These are
official source references, not a binary trace of the exact CI emulator build.
Together with the byte-exact match they support a goldfish host-conversion diagnosis,
not a claim that every emulator or physical decoder is affected.

Reproduce from the saved terminal result artifact with:

```sh
python3 scripts/validation/diagnose_android_rtp_color.py \
  --results /path/to/android-device2-results.json
```

The script reads only the synthetic fixture and supplied results, uses installed
FFmpeg/ffprobe, bounds subprocess time, and outputs JSON. It does not download tools,
run Android, or claim a decoder substitution works.

## Why the substitution is withdrawn

The official Android reference AVC component requests display-order output:
[Android 14 C2SoftAvcDec.cpp, line 474](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-14.0.0_r1/media/codec2/components/avc/C2SoftAvcDec.cpp#474)
and [Android 16, line 484](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-16.0.0_r1/media/codec2/components/avc/C2SoftAvcDec.cpp#484)
set `e_frm_out_mode = IVD_DISPLAY_FRAME_OUT`.

In [libavc's frame-end path](https://android.googlesource.com/platform/external/libavc/+/refs/tags/android-14.0.0_r1/decoder/ih264d_parse_slice.c#773),
lines 773–778 assign existing pictures to the display queue **before** lines
830–835 insert the newly decoded picture. Lines 865–869 assign after insertion
only in decode-order mode. The corresponding
[Android 16 implementation](https://android.googlesource.com/platform/external/libavc/+/refs/tags/android-16.0.0_r1/decoder/ih264d_parse_slice.c#773)
has the same ordering. An isolated first picture in this path is therefore not
made available by that decode call; later input or draining can release it.
The app's zero decoded-output count must not be relabeled as success just because
the codec may have internally decoded a picture.

The unchanged fixture additionally has `bitstream_restriction_flag = 0`: it
does not explicitly signal `max_num_reorder_frames` or `max_dec_frame_buffering`.
[libavc's delay calculation](https://android.googlesource.com/platform/external/libavc/+/refs/tags/android-14.0.0_r1/decoder/ih264d_utils.c#814)
uses its DPB size in this case, and uses `num_reorder_frames + 1` for a restricted
progressive stream. That explains buffering, but rewriting the fixture to signal
zero reorder depth would not remove the before-insertion ordering above or prove
the product's live contract. Production already requests low latency when the
selected codec advertises it; the cited reference component does not implement a
low-latency output-mode override. Setting an unsupported key is not a repair.

The logs support this mechanism: both failing cases advertise an initial
`output.delay.value = 8`, while a passing continuing-stream case later reports
`18`. The input-done callback marked stale for frame zero also appears in passing
cases; it alone does not establish dropped input.

## Bounded rollback

`createNativeRtpAvcDecoder()` remains shared by production and the real
decoder/GLES test, but now calls only `MediaCodec.createDecoderByType("video/avc")`.
The emulator-name/hardware policy, codec enumeration, named replacement, legacy
software fallback, and their six policy unit tests are removed. This is a rollback
of the unsupported substitution, not a new decoder blacklist or an assertion
that every system-selected decoder meets the one-picture contract.

Source guards pin the shared direct factory, the identical full fixture bytes
in both device tests, exactly one startup input in each test, original 4,000 ms
readiness and decoded-picture assertions, output polling without new packets,
and absence of EOS injection. No dummy picture, extra access unit, increased
timeout, SPS rewrite, synthetic readiness, or RGB compensation is introduced.

The diagnostic still records the actual selected codec, output color fields and
pixels. Returning to goldfish is expected to expose the original color failure;
it must not be marked passing without corrected native evidence.

All original acceptance stays active: identity/orientation tolerance 18, channel
swap tolerance 3, curved-LUT numeric tolerance 6, five post-LUT scope comparisons,
reported BT.709/limited/SDR metadata, and real MediaCodec→SurfaceTexture→GLES→
Surface→PixelCopy. The synthetic fixture and expected colors are unchanged.

## Separate startup-CSD caveat, not an implemented fix

Both current paths configure before supplying SPS/PPS, then submit parameter sets
with the first IDR as an ordinary input buffer. The documented
[MediaCodec codec-specific-data contract](https://developer.android.com/reference/android/media/MediaCodec)
provides initial AVC SPS/PPS through `csd-0`/`csd-1` at configuration, or explicit
`BUFFER_FLAG_CODEC_CONFIG` inputs after starting and before frame data. Supplying
CSD at configuration auto-submits it; applications must not submit it again.
Combined parameter sets plus key frame are separately documented for adaptive
**mid-stream format changes**, which does not establish the same startup guarantee.

This is a portability concern worth a separately scoped investigation. It does
not remove the reference decoder's display-order queue and is not proof that
goldfish would use the right YUV matrix. A future production change must derive
configuration from the actual stream, preserve exactly one real first picture,
and handle missing/changed parameter sets and decoder-generation ownership.
Do not hardcode this synthetic fixture's BT.709 tags for arbitrary camera input.
No CSD, color-hint, or low-latency change is included in this rollback.

## Verification boundary

- Historical PASS: one-frame offline decode, metadata check, and exact reproduction of all
  twelve captured channels on both prior API jobs.
- Historical PASS for the withdrawn candidate: seven focused Android source guards, including shared production/test
  factory use, resource ordering, unchanged VUI/payload, original tolerances, and
  bounded diagnostic fields.
- Historical PASS for the withdrawn candidate: six pure Kotlin selection regressions covering known emulator
  variants, reference availability, real-device defaults, near-match names, and
  unknown hardware.
- Historical PASS for the withdrawn candidate: Android SDK-backed compilation of all 72 then-current data sources, including
  the shared factory and selection helper, in the consolidated offline host batch.
- PASS for the withdrawn candidate: full instrumentation compilation before the
  third device run. Compilation did not establish runtime codec behavior.
- FAIL for the withdrawn candidate: both API 34/API 36 isolated-picture readiness
  and native LUT presentation, before any reference-decoder color result.
- NOT_RUN for the rollback: updated source guards, Android compilation and
  instrumented acceptance. Reference-decoder pixel correctness was not established.
- UNRESOLVED: original goldfish native color acceptance.
- NOT_RUN: physical-camera validation.
- Python Ruff was unavailable in the checked interpreter; no installation was
  attempted. Source guards are not a substitute for Android type checking.

## Release assessment

This rollback removes a production regression from an unaccepted **patch**
candidate. No version or release state is changed. It is not a color-correctness
fix and cannot be called `PR ready` while retained native acceptance fails.
Physical-device compatibility and color calibration remain unvalidated.
