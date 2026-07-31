# Changelog

All notable release-level changes to Open EOS Control are documented here.

## [Unreleased]

## [0.1.7] - 2026-07-31

- Added capability-gated Canon Auto Lighting Optimizer control to Android USB/PTP and the libgphoto2 Desktop Bridge. Exact `AloMode (0xD1C1)` UINT32 values are allow-listed from pinned upstream evidence; one-choice R6 Mark III `x3` state remains diagnostic-only, while usable advertised lists receive English and Traditional Chinese UI across Android, iOS, and PC.
- Added capability-gated camera date/time synchronization across Android, iOS, and the Desktop Bridge. Direct CCAPI writes Canon's RFC 1123 value and DST flag, then verifies a GET readback; direct Android USB prefers Canon EOS `UTCTime (0xD17C)` and falls back to `CameraTime (0xD113)`, requiring a matching post-write event; USB Bridge sessions require a writable libgphoto2 `syncdatetimeutc`/`syncdatetime` action paired with its DATE widget and verify a fresh camera-config readback.
- Added English and Traditional Chinese clock controls, success timestamps, diagnostics, simulator state, and deterministic CCAPI/Bridge/libgphoto2 contract coverage.
- Reconciled Android's public auto-rotate setting on start, resume, focus return, every posture sample, and immediately while Quick Settings owns focus; the orientation listener stops entirely while rotation lock is active so no stale sensor callback can rotate camera controls.
- Kept compact HUD atoms in fixed slots, stacked status icons over their exact values, and retained complete viewfinder copy through a centered portrait layout or a quarter-turn inline layout derived from the available Live View long axis. Settings content still remeasures against swapped axes across the full fixed panel.

## [0.1.6] - 2026-07-30

- Made Android camera controls follow the system auto-rotate setting by default, with explicit always-rotate and fixed alternatives.
- Kept the camera composition fixed while atomic controls rotate in place using quarter-turn-aware measurement.
- Preserved the complete English and Traditional Chinese offline preview copy in a bounded, readable sideways viewport.
- Added orientation policy and effective angle to diagnostics, with emulator and Compose coverage for rotation lock, localization, and enlarged text.

## [0.1.5] - 2026-07-30

- Established a stable Android development signing identity for `main` artifacts and tagged releases so previews from `0.1.5` onward can update in place.
- Kept the private key outside Git while pinning and verifying its public SHA-256 certificate fingerprint before every APK upload.
- Preserved ordinary pull-request and local debug builds without exposing release signing secrets.

## [0.1.4] - 2026-07-30

- Added memory-only 3D `.cube` LUT preview to decoded Live View on Android, iOS, and PC, using bounded parsers and platform-native GPU paths without exposing LUT identity in diagnostics.
- Added mutually exclusive luminance histogram and waveform scopes across Android, iOS, and PC.
- Reworked Android camera orientation behavior so the composition remains fixed while bounded controls follow physical orientation only when the Android system auto-rotate setting is enabled.
- Added compact quarter-turn camera HUD content, bounded readable notices, and nested-rotation protection for Traditional Chinese and enlarged text.
- Added a seven-day Android debug APK artifact to successful `main` CI runs for faster physical-camera validation before a tagged release.

## [0.1.0] - 2026-07-26

Initial development preview.

- Added direct Canon CCAPI control on Android, iOS, and the Desktop Bridge.
- Added Android USB/PTP and capability-gated Canon EOS USB control.
- Added JPEG and capability-gated RTP H.264 Live View with adjustable display FPS.
- Added still capture, recording, exposure, white balance, focus, media, and diagnostic workflows where the connected backend advertises support.
- Added English and Traditional Chinese interfaces plus offline UI preview.
- Added a simulator, deterministic cross-platform tests, public protocol references, and pre-push secret scanning.

This preview still requires broader Canon EOS R6 Mark III physical-device validation. iOS is distributed as source; physical-device builds must be built and signed by the developer.

[0.1.0]: https://github.com/js051/open-eos-control/releases/tag/v0.1.0
[0.1.4]: https://github.com/js051/open-eos-control/releases/tag/v0.1.4
[0.1.5]: https://github.com/js051/open-eos-control/releases/tag/v0.1.5
[0.1.6]: https://github.com/js051/open-eos-control/releases/tag/v0.1.6
[0.1.7]: https://github.com/js051/open-eos-control/releases/tag/v0.1.7
