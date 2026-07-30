# Changelog

All notable release-level changes to Open EOS Control are documented here.

## [Unreleased]

- Added memory-only 3D `.cube` LUT preview to decoded Live View on Android, iOS, and PC, using a conflated Android worker, Core Image `CIColorCube`, and WebGL2 3D textures respectively. Imports are bounded, reject unsupported 1D/shaper files, are localized, and omit LUT identity/data from diagnostics.
- Added mutually exclusive luma histogram and 64x64 luma waveform monitoring scopes to decoded Live View on Android, iOS, and PC, including English/Traditional Chinese controls and redacted diagnostics.
- Added a capability-gated Desktop Bridge Capture Target control. Canon USB host-RAM captures now use gPhoto2's capture-and-download lifecycle, atomically enter a persistent local media library, and support bounded thumbnails, streaming download, and exact deletion across native and WSL runners.
- Added English and Traditional Chinese Capture Target labels to the PC, Android, and iOS control interfaces.

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
