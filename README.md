# Open EOS Control

English | [Traditional Chinese](README.zh-TW.md)

Open EOS Control is an unofficial, open-source Canon EOS control project. It targets Canon EOS R6 Mark III first and is structured around PC, iOS, and Android clients that share the same camera-control concepts.

The current development preview is [v0.1.0](docs/releases/v0.1.0.md). It is intended for testing and contributor feedback, not production camera workflows.

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
- Android USB/PTP permission, interface diagnostics, real PTP sessions, identity, storage, media listing/thumbnail/download/deletion, advertised standard still capture/property control, Canon host-RAM JPEG/RAW transfer into app-private Media with card fallback, and capability-gated Canon EOS remote release, half-press, native AF-ON with guaranteed cancel, shooting mode, ISO/Tv/Av/WB, exposure compensation, color temperature, white-balance shifts, color space, aspect ratio, power-zoom speed, Auto Power Off, High ISO noise reduction, AEB, movie start/stop, AF operation/method, Continuous AF, drive, metering, Picture Style, per-card RAW/cRAW/JPEG image quality, Movie Servo AF, focus drive, and JPEG Live View
- Desktop Bridge discovery, Bearer authentication, multi-camera selection, sessions, dynamic capabilities/settings, capture, AF-ON, Live View, focus drive, and ability-gated media thumbnails/transfer/deletion; the libgphoto2 path includes R6 Mark III autofocus drive/cancel actions, per-card image quality, WB shifts, aspect ratio, power-zoom speed, safe Auto Power Off choices, and a selectable Capture Target that either shoots to card or atomically transfers host-RAM captures into the Bridge media library
- Live view frame display with auto/manual refresh and FPS control
- Capability-gated Canon CCAPI RTP H.264 Live View on Android, iOS, and PC, with routed UDP reception, RFC 3550/RFC 6184 packet handling, native mobile display or PyAV decode on PC, an Auto/RTP/JPEG source selector, and a 1-30 FPS display/output cap; it appears only when the camera advertises both RTP endpoints and a reachable local IPv4 address plus a working decoder exist
- ISO, shutter, aperture, white balance, and dynamic advanced settings, including camera-advertised Canon CCAPI RAW/JPEG/HEIF image quality and bounded B/A/M/G white-balance shift with specification-shaped object writes
- System, English, or Traditional Chinese language selection, including localized camera-advertised setting values while preserving exact protocol values for writes
- Camera-style orientation that keeps capture geometry fixed, observes the Android system auto-rotate preference, disables orientation sensing while rotation is locked, and remeasures rotating text/icons inside stable control slots when the phone is held sideways or upside down
- REC start/stop
- Canon coordinate Tap AF through advertised `PUT afframeposition`, using detailed Live View image geometry instead of guessed normalized camera coordinates
- Canon Click White Balance through advertised `POST clickwb`, using the same detailed Live View geometry and a Focus/White Balance tap-action selector
- Independent AF-ON through camera-advertised CCAPI autofocus or Canon USB `DoAf`/`AfCancel`, with a balanced half-press fallback when the dedicated USB pair is unavailable
- Advertised manual shutter half-press with guaranteed release
- Paged camera media browser with lazy thumbnails and full-screen CCAPI image/RAW display previews where advertised, streaming download through Android's document picker, and confirmation-gated deletion

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

The debug APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions runs unit tests, the debug build, and the Compose instrumentation suite on a Pixel 5 API 34 emulator for pushes to `main` and pull requests.

Development releases are built entirely by GitHub Actions. A `vX.Y.Z` tag must match every platform version and point to a commit already on `main`; after secret scanning and Android, Desktop Bridge, Simulator, and iOS validation pass, the release workflow publishes the Android debug APK, Desktop Bridge wheel/source distribution, release notes, and SHA-256 checksums as a GitHub prerelease.

The iOS app is compiled and tested on an iPhone Simulator, but the release does not include an installable IPA. Physical-device distribution requires an Apple Developer team, a distribution certificate, and a matching provisioning profile; none of those signing credentials are stored in this public repository.

## iOS App And Camera Core

`ios/OpenEOSCore` is a Swift Package that implements native CCAPI and authenticated Desktop Bridge clients. The CCAPI path discovers camera-advertised API versions and operations from Canon's same-origin full `url` entries or relative `path` entries. It supports complete JPEG Live View lifecycles and, when the camera advertises both RTP operations, validates Canon SDP plus RFC 3550/RFC 6184 H.264 access units and owns exact RTP start/stop cleanup. The Bridge path validates the service, scans USB cameras, owns session lifecycle, and maps dynamic capabilities into the same models. Both paths capability-gate settings and commands and support the advertised subset of Live View, still capture, independent autofocus, half-press, recording, focus, media listing/thumbnail/download/deletion, and versioned diagnostics with bounded capability evidence, advertised/observed validation differences, and redacted credentials and camera serials. The package includes deterministic HTTP contract tests and is compiled by the macOS GitHub Actions job:

```bash
cd ios/OpenEOSCore
swift test
```

`ios/OpenEOSControl` is the iOS 17 SwiftUI app. It provides direct CCAPI connection or Desktop Bridge URL/token, USB camera scanning and selection, offline UI preview, capability-gated Photo/Video controls, manual focus drive when advertised, JPEG or advertised RTP H.264 Live View, exposure sheets, media transfer and confirmation-gated deletion, redacted diagnostics, manual language selection, and safe portrait/landscape layouts. RTP uses a same-subnet Wi-Fi IPv4 address, a Wi-Fi-only Network.framework UDP listener, native sample-buffer rendering, Auto/JPEG/RTP selection, and a 1-30 FPS display cap; AUTO closes failed RTP state before falling back to JPEG. Bridge tokens and CCAPI passwords remain in memory. Whole-window upside-down rotation stays disabled while key controls can rotate with physical device orientation.

On a macOS host with Xcode and XcodeGen:

```bash
brew install xcodegen
cd ios/OpenEOSControl
xcodegen generate
open OpenEOSControl.xcodeproj
```

GitHub Actions builds an unsigned Simulator app bundle, verifies icon/localization/network/orientation metadata, runs the app unit tests, and exercises English portrait/landscape control, confirmation-gated media deletion, Traditional Chinese connection, and Desktop Bridge connection-form flows on an iPhone Simulator. The workflow intentionally sets `CODE_SIGNING_ALLOWED=NO`, so this build cannot be installed on a physical iPhone and is not published as an IPA. This does not replace an on-device iPhone and EOS R6 Mark III validation record. See [docs/ios-ccapi.md](docs/ios-ccapi.md) for details.

## Desktop Bridge

The bridge is an executable local service and PC control app. It controls USB cameras through `gphoto2`, or connects directly to a camera's wireless CCAPI endpoint without requiring `gphoto2`. Its API and built-in UI expose capability-gated identity, status, settings, still capture, AF-ON, half-press, recording, focus drive, coordinate Tap AF or Click White Balance when supported by the selected engine, JPEG or advertised RTP H.264 Live View, lazy authenticated media thumbnails, streaming downloads, confirmation-gated deletion, and versioned diagnostics with advertised/observed validation differences while excluding credentials and camera serials. The libgphoto2 settings map includes R6 Mark III WB shifts, per-card image quality, aspect ratio, power-zoom speed, Auto Power Off, and Capture Target; values still require writable camera-advertised choices, the undocumented `0xFFFFFFFF` power sentinel is rejected, and one-choice advanced controls stay hidden. `Memory card` uses the camera's card capture path. `Internal RAM`/`SDRAM` requires advertised image capture and uses gPhoto2's capture-and-download lifecycle; files remain hidden in same-volume staging until the command has downloaded and removed the camera-side temporary object, then move atomically into the Bridge media library for thumbnail, streaming download, and deletion. Direct CCAPI RTP validates Canon SDP, receives RFC 3550/RFC 6184 H.264 over a routed UDP socket, decodes every access unit through PyAV/FFmpeg, and emits FPS-capped JPEG frames through the existing authenticated Bridge endpoint. Direct CCAPI AF-ON uses the advertised `POST /shooting/control/af` start/stop contract; libgphoto2 USB prefers the runtime `autofocusdrive`/`autofocuscancel` pair and falls back to balanced half-press. Coordinate Tap AF and Click White Balance map normalized UI input through Canon `flipdetail` image geometry before sending integer `PUT afframeposition` or `POST clickwb` commands. The UI supports English, Traditional Chinese, and responsive desktop/narrow layouts. No product runtime uses a fake camera engine; deterministic fakes live only in bridge tests.

Create the environment below. Install `gphoto2` on the host only when using a USB camera:

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

The default service listens only on `127.0.0.1:18181`. The Android connection screen can use `http://10.0.2.2:18181` from an emulator. A physical phone requires an explicit LAN bind and Bearer token:

```powershell
$env:OPEN_EOS_BRIDGE_HOST = "0.0.0.0"
$env:OPEN_EOS_BRIDGE_TOKEN = "replace-with-a-long-random-token"
.\.venv\Scripts\open-eos-bridge.exe
```

Choose **Desktop Bridge** on the Android or iOS connection screen, enter the computer LAN URL and the same token, then scan and select the camera. The token is kept only in process memory and is never persisted or included in diagnostics. Direct iOS USB/PTP remains a research item; this Bridge path is the implemented iPhone/iPad route to a PC-attached USB camera.

The USB CLI adapter uses persistent `gphoto2 --capture-movie --stdout` MJPEG, accepts a 1-30 FPS output cap, and automatically falls back to bounded per-frame `--capture-preview` at up to 5 FPS when the persistent command fails. Camera-control commands close the movie process before accessing the camera and the next frame request starts it again; diagnostics preserve the effective transport and fallback reason. Requested FPS is not a promise that the camera and USB link will sustain that rate. The CCAPI engine also advertises 1-30 FPS, defaults the UI to 15 FPS, retries the R6 Mark III-compatible JPEG Live View start payload after an `Invalid parameter` response, and reports observed FPS separately. When discovery advertises Canon's RTP endpoints, PyAV and a routed local IPv4 receiver are available, `AUTO` prefers RTP and falls back cleanly to JPEG on startup failure; explicit RTP reports the real error. The FPS value caps decoded JPEG output because Canon's RTP start body has no encoder-FPS field. Its same-origin discovery, settings, capture, guaranteed shutter release, recording, detail-metadata-backed Tap AF/Click WB under both sources, bounded JPEG/RTP decoding, media traversal/download/deletion, auth handling, and capability gates are automated-test covered. The browser workflow also supports Auto/RTP/JPEG selection, English/Traditional Chinese, and desktop/narrow layouts. Physical PC/R6 Mark III RTP and USB throughput validation are still required, and the latest recorded R6 Mark III discovery did not advertise RTP.

## Fake Camera Simulator

```bash
docker compose up --build
```

Simulator URL:

```text
http://localhost:18080
```

Useful endpoints:

- `GET /health`
- `GET /ccapi/info`
- `GET /ccapi/status`
- `GET /ccapi/capabilities`
- `PATCH /ccapi/exposure`
- `PATCH /ccapi/white-balance`
- `POST /ccapi/record/start`
- `POST /ccapi/record/stop`
- `POST /ccapi/focus/tap`
- `POST /ccapi/whitebalance/click`
- `POST /ccapi/shutter/half-press`
- `POST /ccapi/shutter/release`
- `GET /ccapi/media`
- `GET /ccapi/media/{itemId}`
- `DELETE /ccapi/media/{itemId}`
- `GET /ccapi/liveview/frame`

## Roadmap

- Keep CCAPI stable for R6 Mark III wireless control and validate whether its advertised API set includes the implemented Android/iOS/PC RTP H.264 path; otherwise retain JPEG polling.
- Validate the implemented Android USB/PTP standard and Canon EOS host-RAM JPEG/RAW transfer, card capture, remote-release/exposure/color/bracketing/movie/advanced-settings/focus/Live View paths on R6 Mark III, then add only further vendor settings or Touch AF commands backed by reliable evidence.
- Validate the implemented PC CCAPI, Android-to-Desktop-Bridge, persistent gphoto2 USB preview, and USB PC control paths on R6 Mark III, then retain Canon EDSDK as an optional user-installed adapter.
- Validate the implemented iOS SwiftUI CCAPI app, including RTP when advertised and Wi-Fi/cellular coexistence, on an iPhone and R6 Mark III; keep iOS USB/PTP as a research track.

See [docs/feature-status.md](docs/feature-status.md) for the canonical completeness ledger, plus [docs/architecture.md](docs/architecture.md), [docs/control-transports.md](docs/control-transports.md), [docs/android-usb-ptp.md](docs/android-usb-ptp.md), [docs/desktop-bridge-protocol.md](docs/desktop-bridge-protocol.md), [docs/ios-ccapi.md](docs/ios-ccapi.md), and [docs/reference-projects.md](docs/reference-projects.md).

## License

Open EOS Control is licensed under the [Apache License 2.0](LICENSE).

Open EOS Control is not affiliated with or endorsed by Canon. Canon and EOS are trademarks of their respective owners.
