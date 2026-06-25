# Open EOS Control

繁體中文 | [English](README.md)

Open EOS Control 是一個非官方、開源的 Android 相機控制 App，目標是先支援 Canon EOS R6 Mark III，並透過 Canon CCAPI over Wi-Fi 直接控制相機。

這個專案的成品路線是：**Android 手機或平板直接連相機**。Repo 裡的電腦端東西只用於開發輔助，目前主要是 fake CCAPI camera simulator。

## 專案結構

```text
open-eos-control/
  android/       Android App，Kotlin + Jetpack Compose
  simulator/     Fake Canon CCAPI-compatible camera server
  docs/          設計筆記與開發參考
```

## Android App

用 Android Studio 開啟 `android/`。

此 repo 有提供 `android/local.properties.example`。本機可建立 `android/local.properties` 指向你的 Android SDK 路徑，但這個檔案會被 git ignore，不會提交。

目前 App 包含：

- 直接輸入相機 URL
- `Direct Camera` 與 `Dev Simulator` preset
- 連線 / refresh
- 相機狀態顯示
- ISO、shutter、aperture、white balance 控制
- REC start / stop
- Tap focus 的資料層 API hook

預設 Android URL 是為了手機直接連相機 Wi-Fi：

```text
http://192.168.0.1:8080
```

這只是起始 preset，不保證就是你的相機位址。真機測試時請以相機 CCAPI 設定畫面顯示的 IP/port 為準。

如果用 Android Emulator 連本機 simulator，請使用 App 裡的 `Dev Simulator` preset：

```text
http://10.0.2.2:18080
```

如果是實體 Android 手機要連電腦上的 simulator，電腦端先保持執行：

```bash
docker compose up --build
```

然後在 App 裡填電腦的區網 IP：

```text
http://<電腦區網IP>:18080
```

注意：`10.0.2.2` 只適用於 Android Emulator，不適用於實體手機。

## 建置 APK

Windows 開發機可在 repo 根目錄執行：

```powershell
.\scripts\android-gradle.ps1 :app:assembleDebug
```

輸出的 debug APK 會在：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Fake Camera Simulator

啟動 simulator：

```bash
docker compose up --build
```

Simulator URL：

```text
http://localhost:18080
```

目前 simulator 提供：

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

## 相機與 CCAPI 備註

Canon 官方說明中，CCAPI 是透過 Wi-Fi / wireless 進行相機控制的 Camera Control API，且 CAP/CCAPI 路線支援 Android。此 App 架構已經以手機直接連相機為主，但目前 endpoint map 仍偏 simulator 形狀；要真正穩定支援 R6 Mark III，需要再依 Canon CCAPI reference 與實機 response 校正。

Open EOS Control 與 Canon 無關，且未受 Canon 背書。Canon 與 EOS 是其各自權利人的商標。
