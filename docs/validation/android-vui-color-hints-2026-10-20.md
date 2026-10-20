# Android complete-VUI input color hints, 2026-10-20

## Status and retained blocker

The fourth device run,
[39416734486](https://github.com/js051/open-eos-control/actions/runs/39416734486),
tested `1a589b91a14f5ce1f93007f09db821fc5c527728`. API 34 and API 36 each
finished 461 cases: 460 passed and one failed. The remaining failure is
`NativeRtpLutRenderingTest.realH264ToGpuSurfacePreservesRowsAndPostLutScopes`.
This is still an acceptance blocker, not `PR ready` or physical-camera evidence.

Configure-time SPS/PPS and system decoder selection restored the isolated-picture
path, but did **not** repair the goldfish color conversion. All three native LUT
renders now complete, with original presentation timestamps and successful cleanup.
Their reported output is 64×48, BT.709 standard `1`, limited range `2`, SDR transfer
`3`, using `c2.goldfish.h264.decoder`. The identity samples remain wrong.

The new candidate in this document is implemented and host-checked only. Its
effect on native output pixels is **NOT_RUN**. No new emulator, KVM, CI, push,
version, or release operation belongs to this work.

## Independent recheck of the fourth-run capture

The complete original fixture is still 167 bytes with SHA-256
`b48b0a7e9a6a54e6b3f34298ef8cbde86034fe1ed850bb3035f1d35c43d88fdf`.
An installed FFmpeg/ffprobe one-frame decode independently reconfirmed 8-bit
4:2:0, limited range, and BT.709 primaries, transfer, and matrix.

| Position | Decoded Y/Cb/Cr | BT.709 inverse RGB | Captured identity RGB |
|---|---|---|---|
| 16,12 | 85,116,208 | 224,40,55 | 209,19,57 |
| 48,12 | 152,87,66 | 47,200,72 | 60,224,77 |
| 16,36 | 86,192,105 | 40,80,217 | 46,75,212 |
| 48,36 | 143,107,144 | 177,144,104 | 174,143,107 |

The correctly converted values are within one byte of every original expectation.
All twelve captured identity channels exactly equal the default limited-range
BT.601 shader calculation documented in the
[original diagnosis](android-native-rtp-color-2026-10-20.md) and
[official gfxstream source](https://android.googlesource.com/platform/hardware/google/gfxstream/+/d047a57228332d995d36600792fa9ccc26cf8ae6/host/gl/YUVConverter.cpp).
This rules out changing the fixture oracle or widening its tolerance.

The swapped capture matches all twelve reversed identity channels exactly.
An independent evaluation of the curved LUT against captured identity pixels has
maximum error 0.416 byte. The five post-LUT scope comparisons completed inside the
swap render before capture. However, the final native assertion loop stops at the
identity failure; offline evaluation of its later samples is not a passing
native test.

## Why an input-hint candidate is narrowly justified

The fourth-run log still shows `color-standard=4` at initial codec configuration,
changing to `1` only with the first decoded picture. Supplying CSD therefore did
not itself populate the framework's initial color defaults.

[Android MediaFormat](https://developer.android.com/reference/android/media/MediaFormat#KEY_COLOR_STANDARD)
supports optional input color standard, range, and transfer metadata.
[AOSP ColorUtils](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-14.0.0_r1/media/module/foundation/ColorUtils.cpp)
separately maps ISO primaries, matrix and transfer values to those platform
concepts. Its default-selection path may infer BT.601 for an SD-sized frame when
those initial concepts are unspecified.

The candidate forwards only complete VUI metadata that can be represented without
loss by public MediaFormat constants. It is not a request to transform the content:
`KEY_COLOR_TRANSFER_REQUEST` is not used. The exact original SPS/PPS still travels
through CSD and remains authoritative for the decoder.

The source hypothesis is limited: supplying accurate early metadata can avoid
incorrect initial defaults. It is **not proof** that the emulator host converter
will honor it. The previously captured later swap frames also had incorrect
colors, and the cited host source revisions are not a trace of the exact emulator
binary. If native output remains unchanged, this must not be called a color fix.

## Candidate mapping and exclusions

`H264DecoderColorHints.kt` is a pure mapping layer. A complete hint requires an
explicit full/limited range flag, a representable primaries/matrix pair, and a
representable transfer. If any part is missing or unsupported, the entire hint
tuple is omitted and only the unchanged SPS conveys its metadata.

- ISO primaries `1` plus matrix `1` maps to BT.709.
- Primaries `5` plus matrix `5` or `6` maps to BT.601 PAL.
- Primaries `6` or `7` plus matrix `5` or `6` maps to BT.601 NTSC.
- Primaries `9` plus matrix `9` maps to non-constant-luminance BT.2020.
- Transfers `1`, `6`, `14`, and `15` map to Android SDR video, following AOSP.
- Transfers `8`, `16`, and `18` remain distinct linear, ST2084, and HLG hints.
- Range is copied only from the explicit VUI Boolean; it is never inferred.

Mixed primaries/matrix combinations, unspecified values, constant-luminance
BT.2020, private/extended standards and unrepresented transfers are not
approximated. No dimensions, decoder name, device name, or captured pixels affect
the mapping.

`AndroidH264DecoderFormat.kt` converts these typed concepts to public Android
constants. This does not broaden LUT admission: the original 8-bit SDR
BT.709/BT.601 gate remains unchanged. In particular, carrying HDR or BT.2020
metadata is not permission to apply the SDR LUT to that content.

The color keys and all constants used here were added in API 24. The app has
`minSdk = 26` and `compileSdk = 35`, so this introduces no API-16 compatibility
path or unsupported call on an admitted Android version.

## Verification and required next gate

The approved offline gate completed without network or emulator use:

- SDK-backed compilation of all 75 current production data source files: PASS,
  44.42 seconds, JVM heap ceiling 768 MiB and two active processors.
- `H264DecoderColorHintsTest`: 10 tests PASS.
- `H264DecoderConfigurationTest`: 55 tests PASS.
- Retained native-LUT and startup structural guards: 22 tests PASS.
- Source identities stayed unchanged throughout this gate.

Tests cover the original BT.709 tuple, full/limited range, both BT.601 aliases,
BT.2020 constant/non-constant distinction, all mapped transfers, missing fields,
unknown values and mixed/private combinations. Structural checks preserve exact
CSD, one startup picture, shared production/test builder, original 18/3/6 pixel
tolerances, native color admission and no EOS injection.

Native validation remains a separate, unexecuted gate. When separately authorized,
first run the unchanged native rendering, GPU numeric-contract and RTP presentation
tests on API 34 and API 36 against the exact candidate source. Require the original
identity/orientation, swap, curved-LUT, five-scope, output-metadata and isolated
first-picture assertions to pass. Only then consider the full instrumentation
suite. Do not rerun the full suite merely to rediscover the same unresolved
conversion, substitute a decoder, rewrite the fixture, add dummy input, extend
timeouts, compensate RGB, or weaken any acceptance check.

## Release assessment

This is a bounded metadata-forwarding candidate within the existing patch work.
It is not a new feature, a validated native color repair, or a release approval.
The prior native color blocker remains unresolved until exact-candidate runtime
evidence satisfies the unchanged acceptance criteria. Physical-camera color
behavior remains unvalidated.
