# 3D LUT Preview

Open EOS Control can apply an imported 3D `.cube` LUT to decoded Live View frames. The LUT is a display-only monitoring tool: it does not alter camera recording, downloaded media, exposure commands, or reported capture results.

## Supported Subset

- `LUT_3D_SIZE` from 2 through 64.
- Red-fast table order (`r + size * g + size * size * b`).
- `DOMAIN_MIN` and `DOMAIN_MAX`, or `LUT_3D_INPUT_RANGE`.
- Finite numeric table values and trilinear interpolation in encoded sRGB display values.
- Normalize and clamp input coordinates; preserve negative/over-range table entries and clamp only the interpolated output.
- Files no larger than 16 MiB.

The importer rejects 1D LUTs, combined shaper/3D files, duplicate or conflicting directives, invalid domains, incomplete tables, extra table rows, and non-finite values. No Canon or third-party LUT is bundled or redistributed.

## Platform Paths

- Android applies the LUT to decoded JPEG, USB/PTP and Desktop Bridge frames with one conflated worker. It completes at most one conversion while retaining only the newest waiting frame, so work cannot queue without bound.
- iOS converts decoded JPEG and Desktop Bridge frames through one conflated Core Image worker. Its decoded/native paths share an explicit extended-sRGB context and `CIColorCubeWithColorSpace` pipeline, then output sRGB RGBA8/BGRA8. This corrects the former implicit linear-light decoded path; the new shared numeric/image tests are not yet natively executed.
- PC uploads the LUT as a floating-point WebGL2 3D texture and performs explicit trilinear interpolation for decoded Bridge or local UVC/HDMI frames on the GPU.

Android and iOS native RTP retain their direct native-surface renderers. LUT preview and pixel-derived scopes are unavailable for those paths because the app does not receive a decoded CPU image; geometric guides and anamorphic desqueeze remain available.

## Privacy And Lifetime

Imported LUT contents, title and filename remain in memory for the active app/page lifetime. Diagnostics report only whether a LUT is loaded and its cube size. They never include the filename, title, table values, file path or file contents.

Pixel-derived monitoring assists analyze the post-LUT image, so histogram, waveform, zebra, false color and focus peaking match the displayed preview. Removing the LUT immediately restores the unmodified decoded Live View.

## Native RTP candidate update (2026-10-14)

The historical native-RTP limitations above describe the earlier delivered baseline. The new native LUT candidate compiled but failed acceptance on 2026-10-15; see the [exact-head failures and local correction](validation/native-rtp-followup-2026-10-15.md). Android and iOS candidates live on separate development branches; neither this document nor the preceding green CI proves a combined tree or physical-camera parity.

## Shared numeric contract (2026-10-15)

The three branches carry the same bounded [numeric fixture](../contracts/live-view-lut/v1/numeric-vectors.json): twelve fixed vectors cover identity, inversion, cross-channel mixing, non-unit domains, negative/over-range nodes, and a red-fast asymmetric 3³ table. Six vectors also have byte input/output values. They describe display preview numbers, not RAW/scene-linear color grading or calibrated exposure.

iOS tests compare the actual CPU sampler with all twelve fixed outputs, and use the six byte vectors in PNG-decoded and true Metal/native filter paths. A separate JPEG test compares against the actual JPEG-decoded input, accounting for codec quantization before LUT application. Existing H.264 tests still validate decoder geometry and post-LUT scopes. Image/GPU tolerance is bounded separately from the CPU float tolerance; passing source checks cannot count as any of these native executions.
