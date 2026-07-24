# Open EOS Control

English | [Traditional Chinese](README.zh-TW.md)

Open EOS Control is an unofficial, open-source Canon EOS control project. It targets Canon EOS R6 Mark III first and is structured around PC, iOS, and Android clients that share the same camera-control concepts.

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
- Camera HTTP sockets bound to the Wi-Fi route so cellular internet can remain enabled
- Dev simulator preset
- Connect, refresh, and disconnect
- Camera identity, transport, profile, battery, and storage display
- Bounded, secret-redacted capability evidence showing the discovery source, protocol versions, advertised commands, and writable settings
- Android USB/PTP permission, interface diagnostics, real PTP sessions, identity, storage, media listing/thumbnail/download/deletion, advertised standard still capture/property control, and capability-gated Canon EOS remote release, half-press, shooting mode, ISO/Tv/Av/WB, exposure compensation, color temperature, white-balance shifts, color space, aspect ratio, power-zoom speed, High ISO noise reduction, AEB, movie start/stop, AF operation/method, Continuous AF, drive, metering, Picture Style, per-card RAW/cRAW/JPEG image quality, Movie Servo AF, focus drive, and JPEG Live View
- Desktop Bridge discovery, Bearer authentication, multi-camera selection, sessions, dynamic capabilities/settings, capture, AF-ON, Live View, focus drive, and ability-gated media thumbnails/transfer/deletion
- Live view frame display with auto/manual refresh and FPS control
- ISO, shutter, aperture, white balance, and dynamic advanced settings
- System, English, or Traditional Chinese language selection, including localized camera-advertised setting values while preserving exact protocol values for writes
- REC start/stop
- Canon coordinate Tap AF through advertised `PUT afframeposition`, using detailed Live View image geometry instead of guessed normalized camera coordinates
- Independent AF-ON through camera-advertised CCAPI autofocus, with a balanced half-press fallback on USB-capable transports
- Advertised manual shutter half-press with guaranteed release
- Paged camera media browser with lazy thumbnails where advertised, streaming download through Android's document picker, and confirmation-gated deletion

Default direct camera presets:

```text
http://192.168.1.2:8080
https://192.168.1.2:443
```

Use the IP and port shown by the camera CCAPI setup screen when testing with a real camera.

On Android, direct-camera HTTP uses the Wi-Fi `Network` that can route to the camera instead of binding the whole app process. Mobile data can stay enabled for internet traffic. Debug diagnostics report the selected route, interface, Wi-Fi availability, and cellular availability; connection fails clearly when no Wi-Fi route reaches the camera.

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
.\scripts\android-gradle.ps1 :app:assembleDebug
```

The debug APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions runs tests and debug builds on pushes to `main` and on pull requests.

## iOS App And Camera Core

`ios/OpenEOSCore` is a Swift Package that implements native CCAPI and authenticated Desktop Bridge clients. The CCAPI path discovers camera-advertised API versions and operations from Canon's same-origin full `url` entries or relative `path` entries. The Bridge path validates the service, scans USB cameras, owns session lifecycle, and maps dynamic capabilities into the same models. Both paths capability-gate settings and commands and support the advertised subset of JPEG Live View, still capture, independent autofocus, half-press, recording, focus, media listing/thumbnail/download/deletion, and redacted diagnostics with bounded capability evidence. The package includes deterministic HTTP contract tests and is compiled by the macOS GitHub Actions job:

```bash
cd ios/OpenEOSCore
swift test
```

`ios/OpenEOSControl` is the iOS 17 SwiftUI app. It provides direct CCAPI connection or Desktop Bridge URL/token, USB camera scanning and selection, offline UI preview, capability-gated Photo/Video controls, manual focus drive when advertised, JPEG Live View with requests clamped to camera-advertised limits, exposure sheets, media transfer and confirmation-gated deletion, redacted diagnostics, manual language selection, and safe portrait/landscape layouts. Bridge tokens and CCAPI passwords remain in memory. Whole-window upside-down rotation stays disabled while key controls can rotate with physical device orientation.

On a macOS host with Xcode and XcodeGen:

```bash
brew install xcodegen
cd ios/OpenEOSControl
xcodegen generate
open OpenEOSControl.xcodeproj
```

GitHub Actions builds the final app bundle, verifies icon/localization/network/orientation metadata, runs the app unit tests, and exercises English portrait/landscape control, confirmation-gated media deletion, Traditional Chinese connection, and Desktop Bridge connection-form flows on an iPhone Simulator. This does not replace an on-device iPhone and EOS R6 Mark III validation record. See [docs/ios-ccapi.md](docs/ios-ccapi.md) for details.

## Desktop Bridge

The bridge is an executable local service and PC control app. It controls USB cameras through `gphoto2`, or connects directly to a camera's wireless CCAPI endpoint without requiring `gphoto2`. Its API and built-in UI expose capability-gated identity, status, settings, still capture, AF-ON, half-press, recording, focus drive or coordinate Tap AF when supported by the selected engine, JPEG Live View, lazy authenticated media thumbnails, streaming downloads, confirmation-gated deletion, and secret-free diagnostics with the engine's advertised capability evidence. Direct CCAPI AF-ON uses the advertised `POST /shooting/control/af` start/stop contract; coordinate Tap AF maps normalized UI input through Canon `flipdetail` image geometry before sending advertised integer `PUT afframeposition`; USB engines use their verified balanced half-press path. The UI supports English, Traditional Chinese, and responsive desktop/narrow layouts. No product runtime uses a fake camera engine; deterministic fakes live only in bridge tests.

Create the environment below. Install `gphoto2` on the host only when using a USB camera:

```powershell
cd bridge
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\open-eos-bridge.exe
```

For USB discovery, verify the optional host dependency separately with `gphoto2 --auto-detect`.

Open [http://127.0.0.1:18181/](http://127.0.0.1:18181/) for the PC control UI. Choose **USB camera** to scan with `gphoto2`, or **Wireless CCAPI** and enter the camera origin such as `http://192.168.1.2:8080`. Optional camera Basic Auth credentials are supported. The UI only enables advertised controls; Bridge tokens and camera passwords stay in memory and are excluded from diagnostics. Language, camera URL, and username may be remembered locally.

The default service listens only on `127.0.0.1:18181`. The Android connection screen can use `http://10.0.2.2:18181` from an emulator. A physical phone requires an explicit LAN bind and Bearer token:

```powershell
$env:OPEN_EOS_BRIDGE_HOST = "0.0.0.0"
$env:OPEN_EOS_BRIDGE_TOKEN = "replace-with-a-long-random-token"
.\.venv\Scripts\open-eos-bridge.exe
```

Choose **Desktop Bridge** on the Android or iOS connection screen, enter the computer LAN URL and the same token, then scan and select the camera. The token is kept only in process memory and is never persisted or included in diagnostics. Direct iOS USB/PTP remains a research item; this Bridge path is the implemented iPhone/iPad route to a PC-attached USB camera.

The current CLI adapter deliberately advertises at most 5 FPS because each JPEG is a separate `gphoto2 --capture-preview` transaction. The CCAPI engine advertises 1-30 FPS client polling, defaults to 15 FPS, retries the R6 Mark III-compatible Live View start payload after an `Invalid parameter` response, and reports observed FPS separately. Its same-origin full-URL/relative-path discovery, settings, capture, guaranteed shutter release, recording, detail-metadata-backed `afframeposition` Tap AF, bounded JPEG extraction, same-origin media traversal, streaming downloads, auth handling, and capability gates are automated-test covered. The browser workflow has also exercised CCAPI connection, valid JPEG display, 15 FPS selection, Tap AF, English/Traditional Chinese, and desktop/narrow layouts. Physical PC/R6 Mark III validation is still required.

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
- `POST /ccapi/shutter/half-press`
- `POST /ccapi/shutter/release`
- `GET /ccapi/media`
- `GET /ccapi/media/{itemId}`
- `DELETE /ccapi/media/{itemId}`
- `GET /ccapi/liveview/frame`

## Roadmap

- Keep CCAPI stable for R6 Mark III wireless control.
- Validate the implemented Android USB/PTP standard and Canon EOS remote-release/exposure/color/bracketing/movie/advanced-settings/focus/Live View paths on R6 Mark III, then add only further vendor settings or Touch AF commands backed by reliable evidence.
- Validate the implemented PC CCAPI, Android-to-Desktop-Bridge, and USB PC control paths on R6 Mark III, improve libgphoto2 preview throughput with a persistent engine, and retain Canon EDSDK as an optional user-installed adapter.
- Validate the implemented iOS SwiftUI CCAPI app on an iPhone and R6 Mark III; keep iOS USB/PTP as a research track.

See [docs/feature-status.md](docs/feature-status.md) for the canonical completeness ledger, plus [docs/architecture.md](docs/architecture.md), [docs/control-transports.md](docs/control-transports.md), [docs/android-usb-ptp.md](docs/android-usb-ptp.md), [docs/desktop-bridge-protocol.md](docs/desktop-bridge-protocol.md), [docs/ios-ccapi.md](docs/ios-ccapi.md), and [docs/reference-projects.md](docs/reference-projects.md).

## License

Open EOS Control is licensed under the [Apache License 2.0](LICENSE).

Open EOS Control is not affiliated with or endorsed by Canon. Canon and EOS are trademarks of their respective owners.
