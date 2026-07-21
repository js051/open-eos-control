# Open EOS Control

English | [Traditional Chinese](README.zh-TW.md)

Open EOS Control is an unofficial, open-source Canon EOS control project. It is Android-first today, targets Canon EOS R6 Mark III first, and is structured to grow into PC, iOS, and Android clients that share the same camera-control concepts.

The project is not CCAPI-only. CCAPI over Wi-Fi is the most validated backend. Android also has a standards-based USB/PTP backend, capability-gated Canon EOS remote release, exposure/white-balance control, focus drive and JPEG Live View, plus an executable Desktop Bridge client behind the same camera core contract. The Canon USB paths are grounded in pinned libgphoto2 behavior and covered by deterministic tests, but still require a recorded physical R6 Mark III validation. The PC bridge exposes a tested HTTP API over the open-source `gphoto2` CLI; physical bridge validation and iOS remain in development.

## Project Shape

```text
open-eos-control/
  android/       Android app, Kotlin + Jetpack Compose
  bridge/        PC-side HTTP camera bridge, Python + FastAPI + gphoto2
  simulator/     Fake Canon CCAPI-compatible camera server
  docs/          Architecture, transport, and bridge notes
```

## Current Android App

Open `android/` in Android Studio.

The app currently includes:

- Direct CCAPI camera URL input with HTTP/HTTPS presets
- Optional CCAPI Basic Authentication credentials
- Dev simulator preset
- Connect, refresh, and disconnect
- Camera identity, transport, profile, battery, and storage display
- Android USB/PTP permission, interface diagnostics, real PTP sessions, identity, storage, media listing/download, advertised standard still capture/property control, and capability-gated Canon EOS remote release, half-press, ISO/Tv/Av/WB, focus drive, and JPEG Live View
- Desktop Bridge discovery, Bearer authentication, multi-camera selection, sessions, dynamic capabilities/settings, capture, Live View, focus drive, and media streaming
- Live view frame display with auto/manual refresh and FPS control
- ISO, shutter, aperture, white balance, and dynamic advanced settings
- REC start/stop
- Tap focus hook through the shared backend layer
- Advertised manual shutter half-press with guaranteed release
- Paged camera media browser and streaming download through Android's document picker

Default direct camera presets:

```text
http://192.168.1.2:8080
https://192.168.1.2:443
```

Use the IP and port shown by the camera CCAPI setup screen when testing with a real camera.

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

## Desktop Bridge

The bridge is an executable local service for USB cameras managed by `gphoto2`. Its API discovers cameras and exposes capability-gated identity, status, settings, still capture, half-press, recording, focus drive, JPEG Live View, media listing, and streaming downloads. No product runtime uses a fake camera engine; deterministic fakes live only in bridge tests.

Install `gphoto2` for the host platform first, then:

```powershell
cd bridge
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
gphoto2 --auto-detect
.\.venv\Scripts\open-eos-bridge.exe
```

The default service listens only on `127.0.0.1:18181`. The Android connection screen can use `http://10.0.2.2:18181` from an emulator. A physical phone requires an explicit LAN bind and Bearer token:

```powershell
$env:OPEN_EOS_BRIDGE_HOST = "0.0.0.0"
$env:OPEN_EOS_BRIDGE_TOKEN = "replace-with-a-long-random-token"
.\.venv\Scripts\open-eos-bridge.exe
```

Choose **Desktop bridge** on the Android connection screen, enter the URL and the same token, then scan and select the camera. The token is kept only in process memory and is never persisted or included in diagnostics.

The current CLI adapter deliberately advertises at most 5 FPS because each JPEG is a separate `gphoto2 --capture-preview` transaction. A persistent native libgphoto2 stream is a later performance path. Both service and Android contract paths are automated-test covered; this repository does not yet claim a physical R6 Mark III bridge pass, and this Windows development host currently has no `gphoto2` executable installed.

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
- `GET /ccapi/liveview/frame`

## Roadmap

- Keep CCAPI stable for R6 Mark III wireless control.
- Validate the implemented Android USB/PTP standard and Canon EOS remote-release/exposure/focus/Live View paths on R6 Mark III, then add only further vendor settings, Touch AF, or movie commands backed by reliable evidence.
- Validate the implemented Android-to-Desktop-Bridge libgphoto2 path on R6 Mark III, add a future PC UI, and retain Canon EDSDK as an optional user-installed adapter.
- Bring iOS online through CCAPI/Wi-Fi first; keep iOS USB/PTP as a research track.

See [docs/feature-status.md](docs/feature-status.md) for the canonical completeness ledger, plus [docs/architecture.md](docs/architecture.md), [docs/control-transports.md](docs/control-transports.md), [docs/android-usb-ptp.md](docs/android-usb-ptp.md), [docs/desktop-bridge-protocol.md](docs/desktop-bridge-protocol.md), and [docs/reference-projects.md](docs/reference-projects.md).

## License

Open EOS Control is licensed under the [Apache License 2.0](LICENSE).

Open EOS Control is not affiliated with or endorsed by Canon. Canon and EOS are trademarks of their respective owners.
