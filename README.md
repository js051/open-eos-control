# Open EOS Control

English | [Traditional Chinese](README.zh-TW.md)

Open EOS Control is an unofficial, open-source Canon EOS control project. It is Android-first today, targets Canon EOS R6 Mark III first, and is structured to grow into PC, iOS, and Android clients that share the same camera-control concepts.

The project is not CCAPI-only. CCAPI over Wi-Fi is the first working backend; Android USB/PTP and a PC desktop bridge are planned backends behind the same camera core contract.

## Project Shape

```text
open-eos-control/
  android/       Android app, Kotlin + Jetpack Compose
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
- USB/PTP diagnostics for connected Android USB host devices
- Live view frame display with auto/manual refresh and FPS control
- ISO, shutter, aperture, white balance, and dynamic advanced settings
- REC start/stop
- Tap focus hook through the shared backend layer

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
- `GET /ccapi/liveview/frame`

## Roadmap

- Keep CCAPI stable for R6 Mark III wireless control.
- Add Android USB/PTP diagnostics, then still capture, setting writes, and live view where Canon vendor operations allow it.
- Add a desktop bridge that exposes the same camera core contract while using libgphoto2 or a user-installed Canon EDSDK adapter.
- Bring iOS online through CCAPI/Wi-Fi first; keep iOS USB/PTP as a research track.

See [docs/architecture.md](docs/architecture.md), [docs/control-transports.md](docs/control-transports.md), [docs/desktop-bridge-protocol.md](docs/desktop-bridge-protocol.md), and [docs/reference-projects.md](docs/reference-projects.md).

## License

Open EOS Control is licensed under the [Apache License 2.0](LICENSE).

Open EOS Control is not affiliated with or endorsed by Canon. Canon and EOS are trademarks of their respective owners.
