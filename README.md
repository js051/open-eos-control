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

- Direct camera URL input
- Direct Camera and Dev Simulator presets
- Connect / refresh
- Camera status display
- ISO, shutter, aperture, and white balance controls
- REC start / stop
- Tap-focus API hook through the data layer

The default Android URL is aimed at direct camera Wi-Fi control:

```text
http://192.168.0.1:8080
```

That address is a starting preset, not a guarantee. Use the IP/port shown by the camera CCAPI setup when testing with a real body.

For Android Emulator plus local simulator, use the Dev Simulator preset:

```text
http://10.0.2.2:18080
```

For a physical Android device with the local simulator, keep `docker compose up --build` running on the computer and enter the computer's LAN IP:

```text
http://<computer-lan-ip>:18080
```

The `10.0.2.2` address only works inside the Android Emulator.

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

Canon describes CCAPI as the Wi-Fi/wireless Camera Control API path with Android support through CAP. The current app is structured for direct phone-to-camera control, but the endpoint map is still simulator-shaped until it is verified against Canon's CCAPI reference and an R6 Mark III body.

Open EOS Control is not affiliated with or endorsed by Canon. Canon and EOS are trademarks of their respective owners.
