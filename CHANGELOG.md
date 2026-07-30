# Changelog

All notable release-level changes to Open EOS Control are documented here.

## [Unreleased]

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
