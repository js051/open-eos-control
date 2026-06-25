# Open EOS Control

Open EOS Control is an unofficial, open-source Android control app for Canon EOS cameras, starting with the Canon EOS R6 Mark III and Canon CCAPI over Wi-Fi.

The product target is an Android phone/tablet app that talks directly to the camera. The desktop pieces in this repo are only development support, mainly a fake CCAPI camera simulator.

## Project Shape

```text
open-eos-control/
  android/       Android app, Kotlin + Jetpack Compose
  simulator/     Fake Canon CCAPI-compatible camera server
  docs/          Design notes and development references
```

## Android App

Open `android/` in Android Studio.

This repo includes `android/local.properties.example`. A local `android/local.properties` can point at your SDK path, but it is intentionally ignored by git.

The app currently includes:

- Camera base URL input
- Connect / refresh
- Camera status display
- ISO, shutter, aperture, and white balance controls
- REC start / stop
- Tap-focus API hook through the data layer

For Android Emulator plus local simulator, use:

```text
http://10.0.2.2:18080
```

For a physical Android device, use the LAN IP of the development computer running the simulator, or the camera CCAPI URL when testing with a real body.

CLI builds use the committed Gradle wrapper. On this Windows dev machine, use the helper script so Gradle runs with Android Studio's bundled JDK 17:

```powershell
.\scripts\android-gradle.ps1 :app:assembleDebug
```

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

## Camera Notes

The current CCAPI paths are simulator-friendly and intentionally isolated in the Android data layer. Real Canon endpoint verification should happen against Canon's CCAPI reference and an R6 Mark III body before the adapter is treated as stable.

Open EOS Control is not affiliated with or endorsed by Canon. Canon and EOS are trademarks of their respective owners.
