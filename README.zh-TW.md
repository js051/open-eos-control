# Open EOS Control

繁體中文 | [English](README.md)

Open EOS Control 是一個非官方、開源的 Android 相機控制 App，目標是直接透過 Canon CCAPI over Wi-Fi 控制 Canon EOS 相機。專案目前以 Canon EOS R6 Mark III 為優先驗證機種。

最終成品會是 Android 手機 / 平板 App。repo 裡的桌面端內容只是開發輔助，主要用途是提供假的 CCAPI 相機模擬器，讓 UI 和資料層可以先在電腦上自我測試。

## 專案結構

```text
open-eos-control/
  android/       Android App，Kotlin + Jetpack Compose
  simulator/     Fake Canon CCAPI-compatible camera server
  docs/          架構與開發筆記
```

## Android App

用 Android Studio 開啟 `android/`。

repo 內有 `android/local.properties.example`。本機可以建立自己的 `android/local.properties` 指向 Android SDK 路徑，但這個檔案會被 git ignore，不會推上去。

目前 App 已包含：

- 直接輸入相機 CCAPI URL
- `Direct Camera` 與 `Dev Simulator` 預設連線
- 連線、刷新、斷線
- 相機狀態顯示
- Live view frame 顯示，支援自動 / 手動刷新
- ISO、shutter、aperture、white balance 控制
- REC start / stop
- 點擊畫面對焦的 API hook
- 手機窄螢幕單欄捲動版面

預設 Android 直接連相機 Wi-Fi 的 URL 是：

```text
http://192.168.0.1:8080
```

這只是起始 preset，不保證每台相機都相同。實機測試時請以相機 CCAPI 設定畫面顯示的 IP / port 為準。

如果使用 Android Emulator 搭配本機 simulator，請在 App 內選 `Dev Simulator` preset：

```text
http://10.0.2.2:18080
```

如果是實體 Android 手機要連本機 simulator，電腦端先保持 simulator 運行：

```bash
docker compose up --build
```

然後在 App 裡輸入電腦的區網 IP：

```text
http://<電腦區網IP>:18080
```

注意：`10.0.2.2` 只適用 Android Emulator，不適用實體手機。

## 建置 APK

Windows 開發環境可在 repo 根目錄執行：

```powershell
.\scripts\android-gradle.ps1 :app:assembleDebug
```

debug APK 會輸出到：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

可以把這個 APK 傳到手機並手動安裝。不過目前仍是開發版，真機控制 Canon 相機前，仍需要用實機 CCAPI 回應修正 endpoint 與 payload。

## 自我測試

執行 Android unit tests：

```powershell
.\scripts\android-gradle.ps1 :app:testDebugUnitTest
```

建置 debug APK：

```powershell
.\scripts\android-gradle.ps1 :app:assembleDebug
```

GitHub Actions 會在 push 到 `main` 和 pull request 時自動跑上述兩個任務。

## Fake Camera Simulator

啟動 simulator：

```bash
docker compose up --build
```

Simulator URL：

```text
http://localhost:18080
```

目前 simulator endpoint：

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

## Canon / CCAPI 注意事項

Canon CCAPI 是透過 Wi-Fi / wireless 進行 Camera Control 的 API 路徑，也有 Android 使用情境。Open EOS Control 目前已經以「手機直接連相機」為主要架構，但 endpoint map 和 response schema 仍是 simulator-first，需要後續用 Canon CCAPI reference 與 Canon EOS R6 Mark III 實機逐步校正。

Open EOS Control 與 Canon 沒有隸屬或背書關係。Canon 與 EOS 是各自權利人的商標。
