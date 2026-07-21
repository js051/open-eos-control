# Open EOS Control

[English](README.md) | 繁體中文

Open EOS Control 是一個非官方、開源的 Canon EOS 控制專案。目前先以 Android App 為主，第一個真機優先目標是 Canon EOS R6 Mark III，架構上會保留 PC、iOS、Android 三端共用同一套相機控制概念的空間。

這個專案不是只做 CCAPI。現在已經可用的第一條路線是 Wi-Fi 上的 CCAPI；後續會把 Android USB/PTP 直連與 PC desktop bridge 接到同一個 camera core contract 後面。

## 專案結構

```text
open-eos-control/
  android/       Android App，Kotlin + Jetpack Compose
  simulator/     假的 Canon CCAPI 相機伺服器
  docs/          架構、傳輸層與 bridge 設計文件
```

## 目前 Android App

用 Android Studio 開啟 `android/`。

目前功能包含：

- 直接輸入 CCAPI 相機 URL，並提供 HTTP/HTTPS preset
- 可選填 CCAPI Basic Authentication 帳號與密碼
- Dev simulator preset
- Connect、refresh、disconnect
- 顯示相機身分、transport、profile、電池與儲存狀態
- Android USB host 裝置的 USB/PTP 診斷
- Live view 畫面，自動/手動更新與 FPS 控制
- ISO、shutter、aperture、white balance 與動態 advanced settings
- REC 開始/停止
- Tap focus，已經走共用 backend layer
- 依相機公告能力執行手動快門半按，並保證送出釋放命令
- 支援分頁的相機媒體瀏覽，以及透過 Android 文件選擇器串流下載大型檔案

預設直連相機 URL：

```text
http://192.168.1.2:8080
https://192.168.1.2:443
```

真機測試時，請以相機 CCAPI 設定畫面顯示的 IP 與 port 為準。

HTTPS 會使用 Android 正常的憑證驗證，不再接受任意自簽憑證。若相機無法提供系統信任的 HTTPS 憑證，請在隔離的相機網路使用 HTTP，或先安裝可信憑證。

Android Emulator 搭配本機 simulator：

```text
http://10.0.2.2:18080
```

實體 Android 手機搭配本機 simulator 時，先在電腦啟動 simulator，然後在 App 輸入電腦的區網 IP：

```text
http://<computer-lan-ip>:18080
```

## 建置與測試

repo 內有 `android/local.properties.example`。你可以在本機建立 `android/local.properties` 指到 Android SDK 路徑；這個檔案已經被 git ignore。

在這台 Windows 開發機上，建議用 helper script，讓 Gradle 使用 Android Studio 內建的 JDK 17：

```powershell
.\scripts\android-gradle.ps1 :app:testDebugUnitTest
.\scripts\android-gradle.ps1 :app:assembleDebug
```

debug APK 會輸出到：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions 會在 push 到 `main` 與 pull request 時跑 unit test 和 debug build。

## Fake Camera Simulator

```bash
docker compose up --build
```

Simulator URL：

```text
http://localhost:18080
```

常用 endpoint：

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

- 先把 R6 Mark III 的 CCAPI 無線控制維持穩定。
- 加入 Android USB/PTP 診斷，再逐步補 still capture、設定寫入，以及相機允許時的 live view。
- 加入 desktop bridge，對外使用同一套 camera core contract，內部可接 libgphoto2 或使用者自行安裝的 Canon EDSDK adapter。
- iOS 先走 CCAPI/Wi-Fi；iOS USB/PTP 先列為研究線。

功能是否真正完成以 [docs/feature-status.md](docs/feature-status.md) 為準；架構與後續路線請看 [docs/architecture.md](docs/architecture.md)、[docs/control-transports.md](docs/control-transports.md)、[docs/desktop-bridge-protocol.md](docs/desktop-bridge-protocol.md) 與 [docs/reference-projects.md](docs/reference-projects.md)。

## 授權

Open EOS Control 使用 [Apache License 2.0](LICENSE) 授權。

Open EOS Control 與 Canon 無關，也未受 Canon 認可。Canon 與 EOS 是其各自權利人的商標。
