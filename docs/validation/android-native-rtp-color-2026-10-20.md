# Android native RTP color diagnosis, 2026-10-20

## Status

The shared decoder-selection candidate is implemented. Corrected Android
instrumentation is **NOT_RUN**. This is not `PR ready` or physical-camera evidence.
No color tolerance, fixture pixel, shader, color-admission rule, or CI gate changed.

The latest native evidence is the terminal
[run 39376896373](https://github.com/js051/open-eos-control/actions/runs/39376896373)
at `575eb20d006072a133bbb8df6f7f95b3975a6985`. API 34 and API 36 both failed
`NativeRtpLutRenderingTest.realH264ToGpuSurfacePreservesRowsAndPostLutScopes`.
Both reported `c2.goldfish.h264.decoder`, BT.709 standard `1`, limited range `2`,
and SDR transfer `3`, but emitted the same four wrong identity RGB samples.

The earlier [VUI correction](android-native-fixture-diagnostics-2026-10-15.md)
fixed genuinely incomplete metadata. The new run establishes that this correction
was **not sufficient** to fix the host-decoded pixels. Canonical output metadata
alone does not establish that the host YUV-to-RGB stage used that matrix.

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
run Android, or claim the new candidate works.

## Narrow shared production candidate

`createNativeRtpAvcDecoder()` is used by both the real RTP session and the existing
decoder/GLES instrumentation. It replaces neither the renderer nor the test input.

- Only the exact default component `c2.goldfish.h264.decoder`, on exact emulator
  hardware values `goldfish` or `ranchu`, is eligible.
- Only a locally advertised regular AVC **decoder** is considered:
  `c2.android.avc.decoder` first, then `OMX.google.h264.decoder`.
- All other codec names and hardware values retain their system default. There
  is no general hardware-decoder blacklist or real-device software-forcing policy.
- Enumeration failure or an absent reference keeps the existing default.
  If reference creation fails after the default is released, the normal default
  is recreated once. A failed release aborts replacement rather than overlapping
  physical decoders.
- The existing test continues to record the **actual selected codec**, reported
  output color fields, and pixels. Falling back to the affected default does not
  excuse pixel failure or mark the candidate validated.

The selection uses the documented
[regular codec inventory](https://developer.android.com/reference/android/media/MediaCodecList#REGULAR_CODECS)
and [named codec construction](https://developer.android.com/reference/android/media/MediaCodec#createByCodecName(java.lang.String)).
No new dependency, codec installation, permission, or vendor setting is needed.
This is a bounded workaround candidate, not a claim that the reference path has
already passed the native color test.

All original acceptance stays active: identity/orientation tolerance 18, channel
swap tolerance 3, curved-LUT numeric tolerance 6, five post-LUT scope comparisons,
reported BT.709/limited/SDR metadata, and real MediaCodec→SurfaceTexture→GLES→
Surface→PixelCopy. The synthetic fixture and expected colors are unchanged.

## Verification boundary

- PASS: one-frame offline decode, metadata check, and exact reproduction of all
  twelve captured channels on both prior API jobs.
- PASS: seven focused Android source guards, including shared production/test
  factory use, resource ordering, unchanged VUI/payload, original tolerances, and
  bounded diagnostic fields.
- PASS: whitespace/diff checks.
- PASS: six pure Kotlin selection regressions covering known emulator
  variants, reference availability, real-device defaults, near-match names, and
  unknown hardware.
- PASS: Android SDK-backed compilation of all 72 current data sources, including
  the shared factory and selection helper, in the consolidated offline host batch.
- NOT_RUN: full Android/Compose APK compilation, corrected API 34/API 36 instrumentation,
  reference-decoder pixel output, full-suite native acceptance, and physical camera.
- Python Ruff was unavailable in the checked interpreter; no installation was
  attempted. Source guards are not a substitute for Android type checking.

## Release assessment

This production selection change is a **patch** candidate for emulator RTP preview
color correctness. No version or release state is changed. It cannot be called
fixed or `PR ready` until the exact final candidate passes the retained native
acceptance and required CI. Physical-device compatibility and color calibration
remain unvalidated.
