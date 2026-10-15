# Android native acceptance fixture diagnostics, 2026-10-15

## Status and scope

Local test corrections are implemented. Corrected Android native execution is **NOT_RUN**;
this is not `PR ready`, physical-camera validation, or a general color-management fix.
No production renderer, decoder color policy, download stop policy, CI gate, or tolerance changed.

The earlier exact SHA `35fb3407bbada5f29a4c74aac77f50feda60f980`, run `38689135601`,
failed the same two tests on both API 34 (job `116270831976`) and API 36
(job `116270832116`). Each suite ran 374 tests with two failures.

## H.264 fixture: incomplete VUI, not unexplained pixel drift

The 167-byte synthetic stream declared BT.709 matrix coefficients (`1`), but its
primaries and transfer characteristics were unspecified (`2`, `2`). It is 8-bit
4:2:0, 64×48, limited range. CPU decoding the actual embedded bytes establishes:

| Sample | Decoded Y, Cb, Cr | CPU BT.709 RGB | CPU BT.601 RGB | Earlier native identity RGB |
|---|---|---|---|---|
| (16,12) | 85,116,208 | 223,39,54 | 207,18,55 | 209,19,57 |
| (48,12) | 152,87,66 | 46,199,71 | 59,224,75 | 60,224,77 |
| (16,36) | 86,192,105 | 39,79,216 | 44,73,210 | 46,75,212 |
| (48,36) | 143,107,144 | 175,142,102 | 172,141,104 | 174,143,107 |

Both native jobs reported `c2.goldfish.h264.decoder`, color standard `75`, range `2`,
and transfer `3`. Their identity pixels are within 3 of the CPU BT.601 conversion,
but up to 25 from CPU BT.709. This is evidence of the earlier fixture's BT.601-like
conversion, not permission to accept that mismatch.

AOSP fills unspecified primaries using frame dimensions, represents mixed
primaries/matrix combinations as extended standards, and falls back when mapping
unsupported combinations to a graphics dataspace. This supports the explanation
that the tiny matrix-only fixture acquired SD defaults; it is not a trace of every
internal emulator operation. See
[ColorUtils.cpp](https://android.googlesource.com/platform/frameworks/av/+/master/media/module/foundation/ColorUtils.cpp)
and [ColorUtils.h](https://android.googlesource.com/platform/frameworks/av/+/master/media/module/foundation/include/media/stagefright/foundation/ColorUtils.h).

The production session already rejects standard `75` for native LUT application;
the direct renderer fixture had bypassed that session admission check. A pure
Kotlin regression assertion now explicitly preserves the rejection.

### Metadata-only correction

- Original stream SHA-256: `245005cbafd7fc1f3a493eb48542e777ed707bde39d63c5da6a26db8104ac6ac`
- Corrected stream SHA-256: `b48b0a7e9a6a54e6b3f34298ef8cbde86034fe1ed850bb3035f1d35c43d88fdf`
- Only byte offsets 13 and 14 change, each `0x80` → `0x40`.
- VUI primaries, transfer, and matrix are now explicitly `1,1,1`; full-range flag remains `0`.
- PPS, IDR, all decoded YUV planes, original expected RGB values, and sample positions are unchanged.
- The change is reproducible using the existing PyAV 18.1.0 / FFmpeg
  `h264_metadata=colour_primaries=1:transfer_characteristics=1:matrix_coefficients=1:video_full_range_flag=0`
  bitstream filter, preserving the original four-byte Annex B start codes.

The native test now requires the decoder to report canonical BT.709, limited range,
and SDR video transfer before presentation. Missing metadata also fails. A source
guard parses the fixed SPS and pins the original PPS/IDR digests; substituting the
old matrix-only fixture makes that guard fail.

The original color/orientation tolerance **18**, red/blue swap tolerance **3**, and
curved LUT numeric tolerance **6** are unchanged. The five post-LUT scope comparisons
and real SurfaceTexture/GLES/EGL/PixelCopy pipeline remain in place. Arithmetic on
the earlier captured pixels gives swap error `0` and maximum curved-oracle error
`0.416`; these calculations diagnose the old run and do not validate the new one.

## Restored folder picker: retain untouched rows

The old test expected both selected names to be failures. Production
`executeMediaBatch` deliberately stops a DOWNLOAD batch after its first failure,
and `MediaBatchResult.notStartedItems` accounts for untouched rows. Existing
`MediaDownloadBatchPolicyTest` and `CameraDownloadHistoryJourneyTest` encode that policy.
This failure was a stale expectation, not an intermediate progress snapshot.

The restored-picker test now verifies all of the following:

- Exactly two original selected items survive restoration in the feedback roster.
- Total is two; succeeded is zero; failed is one, naming the first original item.
- The first item is `Failed`; the second is explicitly `NotStarted`.
- Both cameras receive zero original-media GETs.
- The batch result exists, the MEDIA operation has retired, batch progress is clear,
  and no feedback remains pending before assertions run.

The timeout stays bounded by the existing session-test timeout. The test name now
describes the stop-after-first-failure behavior. Production behavior is unchanged.

## Local verification boundary

- PASS: seven focused source guards, including VUI semantics, original PPS/IDR,
  unchanged tolerances, resource cleanup, and restored roster accounting.
- PASS: mutation check rejects the original incomplete VUI.
- PASS: 15 existing pure Kotlin LUT/numeric tests, including explicit rejection of standard `75`.
- PASS: Kotlin PSI syntax on the three changed Kotlin files. This is not Android type checking.
- PASS: focused Ruff checks and whitespace/diff checks.
- PASS: PyAV original/corrected decoding, byte delta, unchanged plane hashes,
  and prior native sample arithmetic.
- NOT_RUN: corrected API 34/API 36 instrumentation, full Android SDK build,
  Gradle resource mapping, corrected GPU/PixelCopy behavior, and physical camera.

Next acceptance must rerun the native suites on the eventual exact candidate SHA.
The old terminal failures remain the latest native evidence until that run completes.

## Release Assessment

Verified baseline: [v0.12.0 Development Preview](https://github.com/js051/open-eos-control/releases/tag/v0.12.0),
checked 2026-10-15 03:54:34 UTC. This isolated test/fixture correction has impact
`none`: no distributable production behavior or version changes. Corrected native
execution remains the acceptance blocker; physical-camera validation is NOT_RUN.
The separate automatic JPEG product safeguards have their own patch assessment.
