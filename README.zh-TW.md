# Open EOS Control

[English](README.md) | 繁體中文

Open EOS Control 是一個非官方、開源的 Canon EOS 控制專案。第一個真機優先目標是 Canon EOS R6 Mark III，架構上讓 PC、iOS、Android 三端共用同一套相機控制概念。

這個專案不是只做 CCAPI。目前驗證最完整的是 Wi-Fi 上的 CCAPI；Android 也已經有走同一個 camera core contract 的標準 USB/PTP backend、依能力開放的 Canon EOS 遠端快門、曝光／白平衡、錄影與進階拍攝設定控制、焦點移動、JPEG Live View，以及可執行的 Desktop Bridge client。Canon USB 路徑以固定版本的 libgphoto2 行為為依據並有可重現測試，但仍需留下 R6 Mark III 真機驗證紀錄。PC bridge 可透過開源 `gphoto2` USB 或原生 HTTP CCAPI 提供經測試的 API 與內建響應式控制介面。原生 Swift CCAPI core 與 iOS 17 SwiftUI App 已實作，具英文／繁中介面及 iPhone Simulator 測試；實體 iPhone 與相機驗證仍待完成。

## 專案結構

```text
open-eos-control/
  android/       Android App，Kotlin + Jetpack Compose
  bridge/        PC 相機橋接服務與控制介面，Python + FastAPI + gphoto2
  ios/           原生 Swift CCAPI core 與 iOS App workspace
  simulator/     假的 Canon CCAPI 相機伺服器
  docs/          架構、傳輸層與 bridge 設計文件
```

## 目前 Android App

用 Android Studio 開啟 `android/`。

目前功能包含：

- 直接輸入 CCAPI 相機 URL，並提供 HTTP/HTTPS preset
- 可選填 CCAPI Basic Authentication 帳號與密碼
- 相機 HTTP socket 綁定可達相機的 Wi-Fi route，行動網路可保持開啟
- Dev simulator preset
- Connect、refresh、disconnect
- 顯示相機身分、transport、profile、電池與儲存狀態
- 顯示有數量上限且遮蔽敏感資訊的能力證據，包括探索來源、協定版本、相機公告命令與可寫設定
- Android USB/PTP 權限與介面診斷、實際 PTP session、相機身分、儲存卡、媒體瀏覽／下載、相機有公告時的標準拍照／屬性控制，以及依能力開放的 Canon EOS 遠端快門、半按、ISO／Tv／Av／白平衡、曝光補償、色溫、白平衡偏移、色彩空間、高 ISO 感光度消除雜訊、AEB、錄影開始／停止、自動對焦操作／方式、連續自動對焦、驅動、測光、相片風格、各卡槽 RAW／cRAW／JPEG 畫質、短片伺服自動對焦、焦點移動與 JPEG Live View
- Desktop Bridge 掃描、Bearer 驗證、多相機選擇、session、動態能力／設定、拍攝、Live View、焦點移動與媒體串流
- Live view 畫面，自動/手動更新與 FPS 控制
- ISO、shutter、aperture、white balance 與動態 advanced settings
- 可選跟隨系統、英文或繁體中文；相機公告的設定值會本地化顯示，寫入時仍保留精確的協定原值
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

Android 直連相機時，只會把相機 HTTP 流量綁定到可達相機的 Wi-Fi `Network`，不會綁定整個 App process，因此行動數據可繼續提供一般網路。Debug 診斷會顯示選到的 route、介面、Wi-Fi 與行動網路狀態；若沒有任何 Wi-Fi route 能到達相機，連線會顯示明確錯誤。

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

## iOS App 與 CCAPI Core

`ios/OpenEOSCore` 是原生 Swift Package，負責 iOS 的 CCAPI 傳輸與命令層。它會依相機公告的 API 版本與 operation 建立能力，並支援設定控制、JPEG Live View、拍照、保證釋放的定時半按、錄影、點選對焦、媒體瀏覽／下載，以及包含有界能力證據且已遮蔽敏感資訊的診斷報告。套件包含可重現的 transport 測試，並由 macOS GitHub Actions job 實際編譯：

```bash
cd ios/OpenEOSCore
swift test
```

`ios/OpenEOSControl` 是 iOS 17 SwiftUI App，提供 CCAPI 直接連線、離線 UI 預覽、依能力開放的拍照／錄影控制、JPEG Live View、依相機公告限制調整的 1-30 FPS、曝光設定 sheet、媒體傳輸、遮蔽敏感資料的診斷、手動語言選擇，以及安全的直向／橫向布局。整個視窗不會上下顛倒，只有關鍵控制會依實體裝置方向旋轉。

在具備 Xcode 與 XcodeGen 的 macOS 主機執行：

```bash
brew install xcodegen
cd ios/OpenEOSControl
xcodegen generate
open OpenEOSControl.xcodeproj
```

GitHub Actions 會建置最終 App bundle、確認 ICON／語系／區網／方向 metadata，執行五個 App unit tests，並在 iPhone Simulator 實際跑過英文直向／橫向控制與繁中連線流程。這些證據不能取代實體 iPhone 與 EOS R6 Mark III 的驗證紀錄；細節請見 [docs/ios-ccapi.md](docs/ios-ccapi.md)。

## Desktop Bridge

Desktop Bridge 是可執行的本機服務，也是 PC 控制 App。它可以透過 `gphoto2` 控制 USB 相機，也能不依賴 `gphoto2`，直接連接相機的無線 CCAPI endpoint。API 與內建介面都會依所選 engine 與相機實際公告的能力，開放身分、狀態、設定、拍照、半按快門、錄影、焦點前後移動或座標 Tap AF、JPEG Live View、媒體列表、串流下載，以及包含 engine 公告能力證據且不含敏感資料的診斷。介面支援英文、繁體中文，以及桌面與窄版響應式配置。正式執行路徑不使用假相機 engine；可重現的 fake 只存在測試中。

先建立下列環境；只有使用 USB 相機時才需要在電腦安裝 `gphoto2`：

```powershell
cd bridge
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\open-eos-bridge.exe
```

若要使用 USB 掃描，再以 `gphoto2 --auto-detect` 確認選用的主機端依賴可正常執行。

在電腦瀏覽器開啟 [http://127.0.0.1:18181/](http://127.0.0.1:18181/) 即可使用 PC 控制介面。選擇「USB 相機」會透過 `gphoto2` 掃描；選擇「無線 CCAPI」則輸入相機 origin，例如 `http://192.168.1.2:8080`，也可提供相機 Basic Auth 帳密。介面只會啟用相機有公告的操作；Bridge token 與相機密碼只留在記憶體且不會寫入診斷。語言、相機 URL 與使用者名稱可以保存在本機。

服務預設只監聽 `127.0.0.1:18181`。Android 模擬器可在連線頁使用 `http://10.0.2.2:18181`；實體手機若要從區網連入，必須明確綁定 LAN 並設定 Bearer token：

```powershell
$env:OPEN_EOS_BRIDGE_HOST = "0.0.0.0"
$env:OPEN_EOS_BRIDGE_TOKEN = "請替換成足夠長的隨機字串"
.\.venv\Scripts\open-eos-bridge.exe
```

在 Android 連線頁選擇「桌面橋接」，輸入 URL 與相同 token，再掃描並選擇相機。token 只保留在 App 程序記憶體中，不會持久化，也不會出現在診斷報告。

目前 CLI adapter 每張 JPEG 都是一次獨立的 `gphoto2 --capture-preview` transaction，因此刻意只公告最高 5 FPS。CCAPI engine 提供 1-30 FPS 的 client polling，初始預設 15 FPS；若 R6 Mark III 對含尺寸的 Live View 啟動 payload 回覆 `Invalid parameter`，會自動改用相容 payload 重試，並把要求與實測 FPS 分開顯示。Discovery、設定、拍照、保證釋放快門、錄影、Tap AF、有界 JPEG 擷取、同源媒體遍歷、串流下載、驗證與能力閘門都有自動測試；瀏覽器也實際跑過 CCAPI 連線、有效 JPEG、15 FPS 切換、Tap AF、英／繁中及桌面／窄版流程。PC 與 R6 Mark III 的實體真機驗證仍待完成。

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
- 在 R6 Mark III 真機驗證已實作的 Android USB/PTP 標準路徑，以及 Canon EOS 遠端快門、曝光、色彩、包圍曝光、錄影、進階設定、焦點移動與 Live View；後續只加入有可靠依據的其他專有設定或 Touch AF 命令。
- 在 R6 Mark III 完成已實作 PC CCAPI、Android-to-Desktop-Bridge 與 USB PC 控制介面的真機驗證，以 persistent engine 改善 libgphoto2 預覽效能，並保留 Canon EDSDK 作為使用者自行安裝的 optional adapter。
- 以實體 iPhone 與 R6 Mark III 驗證已實作的 iOS SwiftUI CCAPI App；iOS USB/PTP 先列為研究線。

功能是否真正完成以 [docs/feature-status.md](docs/feature-status.md) 為準；架構與後續路線請看 [docs/architecture.md](docs/architecture.md)、[docs/control-transports.md](docs/control-transports.md)、[docs/android-usb-ptp.md](docs/android-usb-ptp.md)、[docs/desktop-bridge-protocol.md](docs/desktop-bridge-protocol.md)、[docs/ios-ccapi.md](docs/ios-ccapi.md) 與 [docs/reference-projects.md](docs/reference-projects.md)。

## 授權

Open EOS Control 使用 [Apache License 2.0](LICENSE) 授權。

Open EOS Control 與 Canon 無關，也未受 Canon 認可。Canon 與 EOS 是其各自權利人的商標。
