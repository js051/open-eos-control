# Open EOS Control

English | [Traditional Chinese](README.zh-TW.md)

Open EOS Control is an unofficial, open-source Canon EOS control project. It targets Canon EOS R6 Mark III first and is structured around PC, iOS, and Android clients that share the same camera-control concepts.

The current development preview is [v0.1.8](docs/releases/v0.1.8.md). It is intended for testing and contributor feedback, not production camera workflows.

The project is not CCAPI-only. CCAPI over Wi-Fi is the most validated backend. Android also has a standards-based USB/PTP backend and capability-gated Canon EOS controls. Android and iOS can both use the executable Desktop Bridge to control a camera connected to a PC by USB through the same camera contract. The Canon USB paths are grounded in pinned libgphoto2 behavior and covered by deterministic tests, but still require a recorded physical R6 Mark III validation. The PC bridge provides a tested API and responsive control UI through either open-source `gphoto2` USB or native HTTP CCAPI. Native Swift CCAPI and Desktop Bridge clients plus an iOS 17 SwiftUI app are implemented with English/Traditional Chinese UI and iPhone Simulator coverage; physical iPhone and camera validation remains.

## Project Shape

```text
open-eos-control/
  android/       Android app, Kotlin + Jetpack Compose
  bridge/        PC camera bridge and control UI, Python + FastAPI + gphoto2
  ios/           Native Swift camera core and iOS app workspace
  simulator/     Fake Canon CCAPI-compatible camera server
  docs/          Architecture, transport, and bridge notes
```

## Current Android App

Open `android/` in Android Studio.

The app currently includes:

- Direct CCAPI camera URL input with HTTP/HTTPS presets
- Optional CCAPI Basic Authentication credentials
- Camera HTTP and RTP sockets bound to the Wi-Fi route so cellular internet can remain enabled, with refreshed validated-default-network diagnostics
- Dev simulator preset
- Connect, refresh, and disconnect
- Camera identity, transport, profile, battery, and storage display, including card count, total/free bytes, and remaining shots when reported by CCAPI, USB/PTP, or Desktop Bridge
- Bounded, secret-redacted capability evidence showing the discovery source, protocol versions, advertised commands, writable settings, and operations that actually succeeded in the current session
- Session-scoped physical-camera validation on Android, iOS, and PC that exposes only advertised-and-observed features, requires a visible camera-side confirmation, rejects Simulator/Offline Preview evidence, and copies privacy-safe Markdown bound to the matching sanitized diagnostic by native SHA-256
- Capability-gated camera date/time synchronization across Android, iOS, and PC. Direct CCAPI requires advertised GET and PUT `/functions/datetime`, writes Canon's RFC 1123 value plus DST state, and verifies the camera readback. Direct Android USB writes an advertised Canon EOS `UTCTime (0xD17C)` or `CameraTime (0xD113)` UINT32 through `SetDevicePropValueEx` and requires a matching property event. Desktop Bridge USB requires an advertised writable libgphoto2 `syncdatetimeutc`/`syncdatetime` action paired with `datetimeutc`/`datetime`, then forces a fresh config readback within ten seconds of the operation interval.
- Android USB/PTP permission, interface diagnostics, real PTP sessions, identity, storage, media listing/thumbnail/download/deletion, advertised standard still capture/property control, capability-gated Canon `GetEvent` body-control and camera-clock synchronization, a Phone/Memory card capture selector with Canon host-RAM JPEG/RAW transfer into app-private Media, and Canon EOS remote release, half-press, native AF-ON with guaranteed cancel, shooting mode, ISO/Tv/Av/WB, exposure compensation, color temperature, white-balance shifts, color space, aspect ratio, power-zoom speed, Auto Power Off, Auto Lighting Optimizer, High ISO noise reduction, AEB, movie start/stop, AF operation/method, Continuous AF, drive, metering, Picture Style, per-card RAW/cRAW/JPEG image quality, Movie Servo AF, focus drive, and JPEG Live View
- Desktop Bridge discovery, Bearer authentication, multi-camera selection, sessions, dynamic capabilities/settings, capture, AF-ON, Live View, focus drive, and ability-gated media thumbnails/transfer/deletion; the libgphoto2 path includes runtime-probed `gphoto2 --wait-event` body/media synchronization, R6 Mark III autofocus drive/cancel actions, per-card image quality, WB shifts, aspect ratio, power-zoom speed, safe Auto Power Off and Auto Lighting Optimizer choices, and a selectable Capture Target that either shoots to card or atomically transfers host-RAM captures into the Bridge media library
- Live view frame display with auto/manual refresh and FPS control
- Android Live View monitoring assists for decoded JPEG/USB/Bridge frames: imported 3D `.cube` LUT preview, mutually exclusive luminance histogram or 64x64 luma waveform, configurable zebra threshold, false color, focus peaking, 16:9/2.39:1/1:1/4:3 frame guides, action/title safe areas, and 1.33x/1.5x/1.8x/2x anamorphic desqueeze. A single conflated worker applies the latest LUT frame without queue growth. Native RTP remains on its zero-copy surface, so LUT and pixel analysis are disabled there while geometric guides and desqueeze remain available.
- iOS Live View monitoring assists use Core Image `CIColorCube` through one conflated worker for imported 3D `.cube` LUTs and the same bounded 120x80 analysis contract for decoded JPEG/Bridge frames, with the same histogram/luma-waveform choice, zebra, false color, focus peaking, frame guides, safe areas, and anamorphic desqueeze. RTP keeps native sample-buffer rendering, so only its geometry-based guides and desqueeze are enabled.
- The PC control UI applies imported 3D `.cube` LUTs through a floating-point WebGL2 3D texture with explicit trilinear interpolation and the same bounded 120x80 analysis to decoded Bridge frames and local UVC/HDMI video inputs. Its monitoring dialog provides the same histogram/luma-waveform choice, zebra, false color, focus peaking, frame guides, safe areas, and anamorphic desqueeze without changing camera commands or reported capture results. Local video can replace only the viewfinder while the Bridge session continues to control the camera; device IDs/labels and LUT filenames/titles remain memory-only and are omitted from diagnostics. Camera media downloads expose byte progress and cancellation; ordinary files use a streamed browser-download fallback, while unknown or 64 MiB-plus files use direct temporary-file-backed writes when the browser supports them.

LUT import is deliberately a bounded 3D-only `.cube` subset: sizes 2 through 64, red-fast table rows, `DOMAIN_MIN`/`DOMAIN_MAX` or `LUT_3D_INPUT_RANGE`, finite values, and trilinear display interpolation. Combined 1D/shaper LUTs and files larger than 16 MiB are rejected with an explicit error. No Canon or third-party LUT is redistributed.
- Capability-gated Canon CCAPI RTP H.264 Live View on Android, iOS, and PC, with routed UDP reception, RFC 3550/RFC 6184 packet handling, native mobile display or PyAV decode on PC, an Auto/RTP/JPEG source selector, and a 1-30 FPS display/output cap. All three platforms additionally decode advertised in-band RFC 6416 `MP4A-LATM/48000` audio behind default-muted controls: Android uses Media3 LATM extraction, `MediaCodec`, and `AudioTrack`; iOS uses a bounded Swift LATM extractor, `AVAudioConverter`, and `AVAudioEngine`; PC exposes bounded PCM through WebAudio. RTP appears only when the camera advertises both endpoints and a reachable local IPv4 address plus a working video decoder exist
- ISO, shutter, aperture, white balance, and dynamic advanced settings, including camera-advertised Canon CCAPI RAW/JPEG/HEIF image quality and bounded B/A/M/G white-balance shift with specification-shaped object writes
- System, English, or Traditional Chinese language selection, including localized camera-advertised setting values while preserving exact protocol values for writes
- A fixed bottom Photo/Video mode selector keeps the active capture context visible beside the shutter and writes a body shooting mode only when the camera advertises a matching writable value. Its labels and short selection underline rotate inside fixed, edge-inset 48dp hit targets, so a sideways device never turns a full-width selection rectangle into a clipped vertical block. Exposure dials lock while a write is in flight, then align to the camera-confirmed value so rejected or overlapping requests never appear applied.
- Camera-style orientation that locks the app to the device's natural display axis, keeps capture and settings geometry fixed, and rotates only direction-aware content. The Activity reads Android's public auto-rotate policy before starting its foreground orientation listener, applies observed Quick Settings changes even while the shade owns focus, reconciles again on focus return, and disables the listener completely while rotation lock is active. Compact HUD labels and exposure values rotate inside fixed square control slots; battery and storage use icon-over-value atoms, while accessibility descriptions and a readable status panel retain the full camera, battery, storage, and setting values. Complete viewfinder guidance uses a centered stacked layout at 0/180 degrees and a bounded inline layout at 90/270 degrees without dropping localized copy. Scrollable settings remeasure against swapped axes across the full fixed panel; full capability warnings, long errors, dialogs, and the action menu use their own bounded readable panels without moving the shutter, exposure rail, or toolbar.
- REC start/stop
- Canon coordinate Tap AF through advertised `PUT afframeposition`, using detailed Live View image geometry instead of guessed normalized camera coordinates
- Canon Click White Balance through advertised `POST clickwb`, using the same detailed Live View geometry and a Focus/White Balance tap-action selector
- Independent AF-ON through camera-advertised CCAPI autofocus or Canon USB `DoAf`/`AfCancel`, with a balanced half-press fallback when the dedicated USB pair is unavailable
- Advertised manual shutter half-press with guaranteed release
- Capability-gated Bulb long exposure on CCAPI, Android USB/PTP, Desktop Bridge, PC, and iOS: the central shutter switches to timed start/stop only in a camera-advertised Bulb mode, suspends active JPEG polling while held, and retries or performs best-effort release during cleanup
- Paged camera media browser with lazy thumbnails; full-screen CCAPI image/RAW display previews; capability-gated Android USB/PTP and Desktop Bridge JPEG/PNG previews up to 32 MiB; streaming download through Android's document picker; and confirmation-gated deletion. Wired RAW, HEIF, and video items remain downloadable but do not expose a fake preview action.

Default direct camera presets:

```text
http://192.168.1.2:8080
https://192.168.1.2:443
```

Use the IP and port shown by the camera CCAPI setup screen when testing with a real camera.

On Android, direct-camera HTTP and RTP use the Wi-Fi `Network` that can route to the camera instead of binding the whole app process. Mobile data can stay enabled for other internet traffic. Refresh in Debug re-reads the selected camera route, its current availability, cellular validation, system-default transport and system-default validation; `wifiCellularCoexistence=true` means the bound camera Wi-Fi is still present while Android currently has a validated cellular default network. The app does not claim coexistence from radio availability alone, and connection fails clearly when no Wi-Fi route reaches the camera.

HTTPS uses Android's normal certificate validation. The app no longer accepts arbitrary self-signed certificates. Use HTTP on the isolated camera network or install a trusted certificate when the camera cannot present a system-trusted HTTPS certificate.

For Android Emulator plus local simulator:

```text
http://10.0.2.2:18080
```

For a physical Android device with the local simulator, run the simulator on the computer and enter the computer LAN IP:

```text
http://<computer-lan-ip>:18080
```

## Build And Test

This repo includes `android/local.properties.example`. A local `android/local.properties` can point at your SDK path, but it is intentionally ignored by git.

On this Windows dev machine, use the helper script so Gradle runs with Android Studio's bundled JDK 17:

```powershell
.\scripts\android-gradle.ps1 :app:testDebugUnitTest
.\scripts\android-gradle.ps1 connectedDebugAndroidTest
.\scripts\android-gradle.ps1 :app:assembleDebug
```

`connectedDebugAndroidTest` requires a running Android emulator or attached device. The Compose suite covers connection, control, Debug, media, language selection, offline preview, and primary-control visibility at 360 x 800 portrait, 800 x 360 landscape, and 1.5x font scale.

To include the real Android UI-to-HTTP Simulator path, start the fake camera in a second terminal and require it from the instrumentation run:

```powershell
Set-Location simulator
python -m pip install -e .
python -m uvicorn main:app --host 0.0.0.0 --port 18080
```

```powershell
.\scripts\android-gradle.ps1 connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.requireSimulator=true
```

This flow connects through `10.0.2.2`, decodes a Live View frame, changes ISO, captures a still, starts and stops recording, verifies the new media item, and disconnects. CI runs this path on every pull request and `main` push.

The debug APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions runs unit tests, the debug build, and the Compose instrumentation suite on a Pixel 5 API 34 emulator for pushes to `main` and pull requests.

Development releases are built entirely by GitHub Actions. A `vX.Y.Z` tag must match every platform version and point to a commit already on `main`; after secret scanning and Android, Desktop Bridge, Windows standalone, Simulator, and iOS validation pass, the release workflow publishes the Android debug APK, a directly executable Windows x64 Desktop Bridge, the cross-platform Bridge wheel/source distribution, release notes, and SHA-256 checksums as a GitHub prerelease.

The iOS app is compiled and tested on an iPhone Simulator, but the release does not include an installable IPA. Physical-device distribution requires an Apple Developer team, a distribution certificate, and a matching provisioning profile; none of those signing credentials are stored in this public repository.

## iOS App And Camera Core

`ios/OpenEOSCore` is a Swift Package that implements native CCAPI and authenticated Desktop Bridge clients. The CCAPI path discovers camera-advertised API versions and operations from Canon's same-origin full `url` entries or relative `path` entries. It supports complete JPEG Live View lifecycles and, when the camera advertises both RTP operations, validates Canon SDP plus RFC 3550/RFC 6184 H.264 access units and owns exact RTP start/stop cleanup. The Bridge path validates the service, scans USB cameras, owns session lifecycle, and maps dynamic capabilities into the same models. Both paths capability-gate settings and commands and support the advertised subset of Live View, still capture, camera clock synchronization, timed Bulb start/stop, independent autofocus, half-press, recording, focus, media listing/thumbnail/download/deletion, and versioned diagnostics with bounded capability evidence, advertised/observed validation differences, and redacted credentials and camera serials. The package includes deterministic HTTP contract tests and is compiled by the macOS GitHub Actions job:

```bash
cd ios/OpenEOSCore
swift test
```

`ios/OpenEOSControl` is the iOS 17 SwiftUI app. It provides direct CCAPI connection or Desktop Bridge URL/token, USB camera scanning and selection, offline UI preview, capability-gated Photo/Video controls, manual focus drive when advertised, JPEG or advertised RTP H.264 Live View, exposure sheets, file-backed media transfer with real byte progress and cancellation, confirmation-gated deletion, redacted diagnostics, manual language selection, and safe portrait/landscape layouts. RTP uses a same-subnet Wi-Fi IPv4 address, Wi-Fi-only Network.framework UDP listeners, native sample-buffer rendering, Auto/JPEG/RTP selection, and a 1-30 FPS display cap. An advertised in-band `MP4A-LATM/48000` stream receives a separate default-muted, foreground-only AAC-LC monitor through `AVAudioConverter` and `AVAudioEngine`; AUTO closes failed video RTP state before falling back to JPEG. Bridge tokens and CCAPI passwords remain in memory. Whole-window upside-down rotation stays disabled while key controls can rotate with physical device orientation.

On a macOS host with Xcode and XcodeGen:

```bash
brew install xcodegen
cd ios/OpenEOSControl
xcodegen generate
open OpenEOSControl.xcodeproj
```

GitHub Actions builds an unsigned Simulator app bundle, verifies icon/localization/network/orientation metadata, runs the app unit tests, and exercises seven iPhone Simulator UI workflows. In addition to the offline English/Traditional Chinese, orientation, media, monitoring, and Desktop Bridge states, a required network end-to-end workflow starts the real fake-camera service and drives the production SwiftUI -> `CameraAppState` -> `OpenEOSCore` -> CCAPI HTTP path through decoded Live View, ISO control, still capture, balanced AF-ON and shutter half-press, coordinate Tap AF, Click White Balance, manual focus drive, mode-gated Bulb start/stop, recording, decoded media preview, confirmed deletion, and disconnect. The workflow intentionally sets `CODE_SIGNING_ALLOWED=NO`, so this build cannot be installed on a physical iPhone and is not published as an IPA. Deterministic Simulator evidence does not replace an on-device iPhone and EOS R6 Mark III validation record. See [docs/ios-ccapi.md](docs/ios-ccapi.md) for details.

## Desktop Bridge

The bridge is an executable local service and PC control app. It controls USB cameras through `gphoto2`, or connects directly to a camera's wireless CCAPI endpoint without requiring `gphoto2`. Its API and built-in UI expose capability-gated identity, status, settings, still capture, timed Bulb start/stop, AF-ON, half-press, recording, focus drive, coordinate Tap AF or Click White Balance when supported by the selected engine, JPEG or advertised RTP H.264 Live View, lazy authenticated media thumbnails, streaming downloads, confirmation-gated deletion, and versioned diagnostics with advertised/observed validation differences while excluding credentials and camera serials. The libgphoto2 settings map includes R6 Mark III WB shifts, per-card image quality, aspect ratio, power-zoom speed, Auto Power Off, and Capture Target; values still require writable camera-advertised choices, the undocumented `0xFFFFFFFF` power sentinel is rejected, and one-choice advanced controls stay hidden. `Memory card` uses the camera's card capture path. `Internal RAM`/`SDRAM` requires advertised image capture and uses gPhoto2's capture-and-download lifecycle; files remain hidden in same-volume staging until the command has downloaded and removed the camera-side temporary object, then move atomically into the Bridge media library for thumbnail, streaming download, and deletion. Direct CCAPI RTP validates Canon SDP, receives RFC 3550/RFC 6184 H.264 over a routed UDP socket, decodes every access unit through PyAV/FFmpeg, and emits FPS-capped JPEG frames through the existing authenticated Bridge endpoint. When the SDP also advertises in-band RFC 6416 `MP4A-LATM/48000`, a separate PC-only receiver decodes bounded 48 kHz stereo PCM for user-gesture WebAudio playback; audio failure remains independent of video. Direct CCAPI AF-ON uses the advertised `POST /shooting/control/af` start/stop contract; libgphoto2 USB prefers the runtime `autofocusdrive`/`autofocuscancel` pair and falls back to balanced half-press. Coordinate Tap AF and Click White Balance map normalized UI input through Canon `flipdetail` image geometry before sending integer `PUT afframeposition` or `POST clickwb` commands. The UI supports English, Traditional Chinese, and responsive desktop/narrow layouts. No product runtime uses a fake camera engine; deterministic fakes live only in bridge tests.

On Windows x64, download `open-eos-control-bridge-windows-x64-X.Y.Z.exe` from a release and run it directly. It contains the Python runtime, PyAV/FFmpeg, and the browser control UI. The loopback control page opens after the embedded service is ready; keep the console window open while using the app, and close it to stop the service. `--no-browser` disables automatic browser launch. No Python installation is needed for wireless CCAPI or local UVC/HDMI preview. USB camera control still requires a working system `gphoto2` engine.

The wheel remains the cross-platform and development path:

```powershell
cd bridge
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\open-eos-bridge.exe
```

For USB discovery, verify the optional host dependency separately with `gphoto2 --auto-detect`.

On Windows, the Bridge prefers a native `gphoto2` executable and automatically uses `wsl.exe --exec gphoto2` when native gphoto2 is absent. Set `OPEN_EOS_GPHOTO2_WSL_DISTRO` when the desired WSL 2 distribution is not the default. Windows USB devices are not exposed to WSL directly, so the host also needs `usbipd-win` and the Canon device must be bound and attached to WSL. Run the read-only doctor before changing the machine:

```powershell
.\scripts\windows-gphoto2-doctor.ps1
.\scripts\windows-gphoto2-doctor.ps1 -Json
```

The Bridge health response distinguishes a missing distribution, missing WSL gphoto2, missing `usbipd-win`, and a ready WSL runner. The doctor prints the corresponding commands but never installs a distribution, driver, or package by itself. Microsoft notes that a USB device attached to WSL is unavailable to Windows until detached.

Host-RAM captures are stored under the platform user-data directory by default: `%LOCALAPPDATA%\OpenEOSControl\Captures` on Windows, `~/Library/Application Support/OpenEOSControl/Captures` on macOS, or `${XDG_DATA_HOME:-~/.local/share}/open-eos-control/captures` on Linux. Set `OPEN_EOS_CAPTURE_DIR` to an absolute path to choose another location. The path is not returned by the API or included in diagnostics; only opaque media IDs are exposed.

Open [http://127.0.0.1:18181/](http://127.0.0.1:18181/) for the PC control UI. Choose **USB camera** to scan with `gphoto2`, or **Wireless CCAPI** and enter the camera origin such as `http://192.168.1.2:8080`. Optional camera Basic Auth credentials are supported. The UI only enables advertised controls; Bridge tokens and camera passwords stay in memory and are excluded from diagnostics. Language, camera URL, and username may be remembered locally.

After connecting a camera-control session, **Preview input** can switch the viewfinder to a local UVC camera or HDMI capture card. The browser requests video only and may show its camera-permission prompt on first use. Run the UI on the default loopback URL or HTTPS, choose the desired **Video device**, then start the preview. Switching source/device, disconnecting, or closing the page stops all local media tracks. Canon coordinate Tap AF, Click White Balance and focus magnification remain unavailable for local video because it carries no Canon Live View geometry; the current libgphoto2 focus-drive path also requires camera-side Live View. The implementation is test-covered, but physical R6 Mark III UVC and HDMI capture-card validation remains.

The default service listens only on `127.0.0.1:18181`. The Android connection screen can use `http://10.0.2.2:18181` from an emulator. A physical phone requires an explicit LAN bind and Bearer token:

```powershell
$env:OPEN_EOS_BRIDGE_TOKEN = "replace-with-a-long-random-token"
.\open-eos-control-bridge-windows-x64-X.Y.Z.exe --host 0.0.0.0 --no-browser
```

The wheel command can instead use `OPEN_EOS_BRIDGE_HOST=0.0.0.0` with the same token. The standalone executable deliberately has no token command-line option, so credentials do not appear in the process list.

Choose **Desktop Bridge** on the Android or iOS connection screen, enter the computer LAN URL and the same token, then scan and select the camera. The token is kept only in process memory and is never persisted or included in diagnostics. Direct iOS USB/PTP remains a research item; this Bridge path is the implemented iPhone/iPad route to a PC-attached USB camera.

The USB CLI adapter uses persistent `gphoto2 --capture-movie --stdout` MJPEG, accepts a 1-30 FPS output cap, and automatically falls back to bounded per-frame `--capture-preview` at up to 5 FPS when the persistent command fails. Camera-control commands close the movie process before accessing the camera and the next frame request starts it again; diagnostics preserve the effective transport and fallback reason. Requested FPS is not a promise that the camera and USB link will sustain that rate. The CCAPI engine also advertises 1-30 FPS, defaults the UI to 15 FPS, retries the R6 Mark III-compatible JPEG Live View start payload after an `Invalid parameter` response, and reports observed FPS separately. When discovery advertises Canon's RTP endpoints, PyAV and a routed local IPv4 receiver are available, `AUTO` prefers RTP and falls back cleanly to JPEG on startup failure; explicit RTP reports the real error. The FPS value caps decoded JPEG output because Canon's RTP start body has no encoder-FPS field. Android, iOS, and PC RTP audio are exposed only for advertised in-band `MP4A-LATM/48000`; explicit `cpresent=0`, unsupported codecs/rates, bind failures and decode errors are reported without claiming playback or stopping video. Mobile audio is independently received and parsed even while muted, starts decoding/playback only after an explicit user toggle, and mutes again when the app enters the background. Same-origin discovery, settings, capture, guaranteed shutter release, recording, detail-metadata-backed Tap AF/Click WB under both sources, bounded JPEG/RTP video/audio decoding, media traversal/download/deletion, auth handling, and capability gates are automated-test covered. The browser workflow also supports Auto/RTP/JPEG selection, default-muted camera audio, English/Traditional Chinese, and desktop/narrow layouts. Physical Android/iOS/PC R6 Mark III RTP audio/video and USB throughput validation are still required, and the latest recorded R6 Mark III discovery did not advertise RTP.

## Fake Camera Simulator

```bash
docker compose up --build
```

Simulator URL:

```text
http://localhost:18080
```

Useful endpoints:

- `GET /ccapi` and Canon-style `/ccapi/ver100/...` routes for direct production CCAPI clients
- `GET /health`
- `GET /ccapi/info`
- `GET /ccapi/status`
- `GET /ccapi/capabilities`
- `PATCH /ccapi/exposure`
- `PATCH /ccapi/white-balance`
- `POST /ccapi/clock/sync`
- `POST /ccapi/capture/still`
- `POST /ccapi/record/start`
- `POST /ccapi/record/stop`
- `POST /ccapi/bulb/start`
- `POST /ccapi/bulb/stop`
- `POST /ccapi/focus/tap`
- `POST /ccapi/focus/drive`
- `POST /ccapi/whitebalance/click`
- `POST /ccapi/shutter/half-press`
- `POST /ccapi/shutter/release`
- `GET /ccapi/media`
- `GET /ccapi/media/{itemId}`
- `DELETE /ccapi/media/{itemId}`
- `GET /ccapi/liveview/frame`

The required Android device workflow uses the test-only reset/state/mode endpoints to assert that production UI actions reached the backend. It covers Live View, exposure, still/movie capture, Tap AF, Click WB, AF-ON, half-press, focus drive, Bulb, media preview and deletion. The PC browser workflow connects through Canon-style discovery and `/ccapi/ver100/...` routes, then drives the production browser UI, FastAPI service, and `CcapiEngine` while asserting the same backend state. These reproducible Simulator paths do not replace the physical R6 Mark III validation record.

## Roadmap

- Keep CCAPI stable for R6 Mark III wireless control and validate whether its advertised API set includes the implemented Android/iOS/PC RTP H.264 and AAC-LATM paths; otherwise retain JPEG polling.
- Validate the implemented Android USB/PTP standard and Canon EOS clock synchronization, host-RAM JPEG/RAW transfer, card capture, remote-release/exposure/color/bracketing/movie/advanced-settings/focus/Live View paths on R6 Mark III, then add only further vendor settings or Touch AF commands backed by reliable evidence.
- Validate the implemented PC CCAPI, Android-to-Desktop-Bridge, persistent gphoto2 USB preview, and USB PC control paths on R6 Mark III, then retain Canon EDSDK as an optional user-installed adapter.
- Validate the implemented iOS SwiftUI CCAPI app, including RTP when advertised and Wi-Fi/cellular coexistence, on an iPhone and R6 Mark III; keep iOS USB/PTP as a research track.

## Physical-Camera Evidence

Android, iOS, and PC can create an in-app physical confirmation record for the current connection, and their diagnostic reports can be checked with the repository's strict R6 Mark III verifier. It rejects stale, inconsistent, truncated, or privacy-unsafe reports; required capabilities pass only when they were both advertised and observed successfully in the current session. Physical camera-side results remain a separate explicit operator confirmation. See [Device Validation](docs/device-validation.md) for the workflow and auditable Markdown/JSON output.

See [docs/feature-status.md](docs/feature-status.md) for the canonical completeness ledger, plus [docs/architecture.md](docs/architecture.md), [docs/control-transports.md](docs/control-transports.md), [docs/android-usb-ptp.md](docs/android-usb-ptp.md), [docs/desktop-bridge-protocol.md](docs/desktop-bridge-protocol.md), [docs/ios-ccapi.md](docs/ios-ccapi.md), [docs/device-validation.md](docs/device-validation.md), and [docs/reference-projects.md](docs/reference-projects.md).

## License

Open EOS Control is licensed under the [Apache License 2.0](LICENSE).

Open EOS Control is not affiliated with or endorsed by Canon. Canon and EOS are trademarks of their respective owners.
