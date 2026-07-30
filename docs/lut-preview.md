# 3D LUT Preview

Open EOS Control can apply an imported 3D `.cube` LUT to decoded Live View frames. The LUT is a display-only monitoring tool: it does not alter camera recording, downloaded media, exposure commands, or reported capture results.

## Supported Subset

- `LUT_3D_SIZE` from 2 through 64.
- Red-fast table order (`r + size * g + size * size * b`).
- `DOMAIN_MIN` and `DOMAIN_MAX`, or `LUT_3D_INPUT_RANGE`.
- Finite numeric table values and trilinear interpolation.
- Files no larger than 16 MiB.

The importer rejects 1D LUTs, combined shaper/3D files, duplicate or conflicting directives, invalid domains, incomplete tables, extra table rows, and non-finite values. No Canon or third-party LUT is bundled or redistributed.

## Platform Paths

- Android applies the LUT to decoded JPEG, USB/PTP and Desktop Bridge frames with one conflated worker. It completes at most one conversion while retaining only the newest waiting frame, so work cannot queue without bound.
- iOS converts decoded JPEG and Desktop Bridge frames with Core Image `CIColorCube` through one conflated worker with the same bounded scheduling rule.
- PC uploads the LUT as a floating-point WebGL2 3D texture and performs explicit trilinear interpolation for decoded Bridge or local UVC/HDMI frames on the GPU.

Android and iOS native RTP retain their direct native-surface renderers. LUT preview and pixel-derived scopes are unavailable for those paths because the app does not receive a decoded CPU image; geometric guides and anamorphic desqueeze remain available.

## Privacy And Lifetime

Imported LUT contents, title and filename remain in memory for the active app/page lifetime. Diagnostics report only whether a LUT is loaded and its cube size. They never include the filename, title, table values, file path or file contents.

Pixel-derived monitoring assists analyze the post-LUT image, so histogram, waveform, zebra, false color and focus peaking match the displayed preview. Removing the LUT immediately restores the unmodified decoded Live View.
