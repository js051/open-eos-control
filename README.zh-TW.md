# Open EOS Control

[English](README.md) | 繁體中文

Open EOS Control 是一個非官方、開源的 Canon EOS 控制專案。第一個真機優先目標是 Canon EOS R6 Mark III，架構上讓 PC、iOS、Android 三端共用同一套相機控制概念。

這個專案不是只做 CCAPI。目前驗證最完整的是 Wi-Fi 上的 CCAPI；Android 也已有標準 USB/PTP backend 與依能力開放的 Canon EOS 控制。Android 與 iOS 現在都能透過同一套 camera contract 使用可執行的 Desktop Bridge，控制以 USB 接在電腦上的相機。Canon USB 路徑以固定版本的 libgphoto2 行為為依據並有可重現測試，但仍需留下 R6 Mark III 真機驗證紀錄。PC bridge 可透過開源 `gphoto2` USB 或原生 HTTP CCAPI 提供經測試的 API 與內建響應式控制介面。原生 Swift CCAPI／Desktop Bridge client 與 iOS 17 SwiftUI App 已實作，具英文／繁中介面及 iPhone Simulator 測試；實體 iPhone 與相機驗證仍待完成。

## 專案結構

```text
open-eos-control/
  android/       Android App，Kotlin + Jetpack Compose
  bridge/        PC 相機橋接服務與控制介面，Python + FastAPI + gphoto2
  ios/           原生 Swift 相機 core 與 iOS App workspace
  simulator/     假的 Canon CCAPI 相機伺服器
  docs/          架構、傳輸層與 bridge 設計文件
```

## 目前 Android App

用 Android Studio 開啟 `android/`。

目前功能包含：

- 直接輸入 CCAPI 相機 URL，並提供 HTTP/HTTPS preset
- 可選填 CCAPI Basic Authentication 帳號與密碼
- 相機 HTTP 與 RTP socket 綁定可達相機的 Wi-Fi route，行動網路可保持開啟，並可重新整理已驗證的預設網路診斷
- Dev simulator preset
- Connect、refresh、disconnect
- 顯示相機身分、transport、profile、電池與儲存狀態
- 顯示有數量上限且遮蔽敏感資訊的能力證據，包括探索來源、協定版本、相機公告命令與可寫設定
- Android USB/PTP 權限與介面診斷、實際 PTP session、相機身分、儲存卡、媒體瀏覽／縮圖／下載／刪除、相機有公告時的標準拍照／屬性控制，以及依能力開放的 Canon EOS 遠端快門、半按、拍攝模式、ISO／Tv／Av／白平衡、曝光補償、色溫、白平衡偏移、色彩空間、畫面比例、電動變焦速度、自動關閉電源、高 ISO 感光度消除雜訊、AEB、錄影開始／停止、自動對焦操作／方式、連續自動對焦、驅動、測光、相片風格、各卡槽 RAW／cRAW／JPEG 畫質、短片伺服自動對焦、焦點移動與 JPEG Live View
- Desktop Bridge 掃描、Bearer 驗證、多相機選擇、session、動態能力／設定、拍攝、AF-ON、Live View、焦點移動，以及依能力提供的媒體縮圖／傳輸／刪除；libgphoto2 路徑也包含 R6 Mark III 各卡槽影像畫質、白平衡偏移、畫面比例、電動變焦速度、安全的自動關機選項，以及拍攝前恢復記憶卡目的地
- Live view 畫面，自動/手動更新與 FPS 控制
- Android、iOS 與 PC 上依能力開放的 Canon CCAPI RTP H.264 Live View：使用可達路由的 UDP、RFC 3550／RFC 6184 封包處理、手機原生顯示或 PC PyAV 解碼，可切換自動／RTP／JPEG 來源並限制 1-30 FPS 顯示／輸出幀率；只有相機同時公告兩個 RTP endpoint，且本機有可達 IPv4 與可用 decoder 時才會出現
- ISO、shutter、aperture、white balance 與動態 advanced settings
- 可選跟隨系統、英文或繁體中文；相機公告的設定值會本地化顯示，寫入時仍保留精確的協定原值
- REC 開始/停止
- Canon 座標 Tap AF：只有相機公告 `PUT afframeposition` 且詳細 Live View 提供影像座標時才開放，不會把 0..1 座標直接猜測成機身命令
- Canon 點選白平衡：只有相機公告 `POST clickwb` 且詳細 Live View 提供影像座標時才開放，取景畫面可切換點選對焦／點選白平衡
- 獨立 AF-ON：CCAPI 使用相機公告的自動對焦命令，有線傳輸則以確實釋放的半按流程作為後備
- 依相機公告能力執行手動快門半按，並保證送出釋放命令
- 支援分頁及按需縮圖的相機媒體瀏覽、透過 Android 文件選擇器串流下載大型檔案，以及需確認後才執行的刪除

預設直連相機 URL：

```text
http://192.168.1.2:8080
https://192.168.1.2:443
```

真機測試時，請以相機 CCAPI 設定畫面顯示的 IP 與 port 為準。

Android 直連相機時，只會把相機 HTTP 與 RTP 流量綁定到可達相機的 Wi-Fi `Network`，不會綁定整個 App process，因此行動數據可繼續提供其他網路流量。在 Debug 按 Refresh 會重新讀取相機 route、相機網路是否仍存在、行動網路驗證狀態、系統預設 transport 與預設網路驗證狀態；只有相機流量確實綁定仍存在的 Wi-Fi，且 Android 當下以已驗證的行動網路作為預設網路時，才會顯示 `wifiCellularCoexistence=true`。App 不會只因行動數據 radio 存在就宣稱已可共存；若沒有任何 Wi-Fi route 能到達相機，連線會顯示明確錯誤。

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

## iOS App 與相機 Core

`ios/OpenEOSCore` 是原生 Swift Package，包含 CCAPI 與具 Bearer 驗證的 Desktop Bridge client。CCAPI 會解析 Canon 公告的同源完整 `url` 或相對 `path`，再依 API 版本與 operation 建立能力；除了完整 JPEG Live View 生命週期，也會在相機公告兩個 RTP operation 時驗證 Canon SDP、RFC 3550 與 RFC 6184 H.264 access unit，並負責精確清理 RTP start／stop。Bridge 會驗證服務、掃描 USB 相機、管理 session，並把動態能力映射到同一套模型。兩條路徑都依能力支援設定控制、Live View、拍照、獨立自動對焦、半按、錄影、對焦、媒體瀏覽／下載／刪除，以及包含有界能力證據且已遮蔽敏感資訊的診斷報告。套件包含可重現的 HTTP 契約測試，並由 macOS GitHub Actions job 實際編譯：

```bash
cd ios/OpenEOSCore
swift test
```

`ios/OpenEOSControl` 是 iOS 17 SwiftUI App，提供 CCAPI 直接連線，或輸入 Desktop Bridge URL／token 後掃描並選擇 USB 相機；同時具備離線 UI 預覽、依能力開放的拍照／錄影與手動焦點驅動、JPEG 或相機公告的 RTP H.264 Live View、曝光設定 sheet、媒體傳輸與需確認後執行的刪除、遮蔽敏感資料的診斷、手動語言選擇，以及安全的直向／橫向布局。RTP 會使用和相機同子網的 Wi-Fi IPv4、限定 Wi-Fi 的 Network.framework UDP listener、系統原生 sample-buffer 顯示、自動／JPEG／RTP 選擇與 1-30 FPS 顯示上限；自動模式若 RTP 啟動失敗，會先完整清理再退回 JPEG。Bridge token 與 CCAPI 密碼都只留在記憶體。整個視窗不會上下顛倒，只有關鍵控制會依實體裝置方向旋轉。

在具備 Xcode 與 XcodeGen 的 macOS 主機執行：

```bash
brew install xcodegen
cd ios/OpenEOSControl
xcodegen generate
open OpenEOSControl.xcodeproj
```

GitHub Actions 會建置最終 App bundle、確認 ICON／語系／區網／方向 metadata，執行 App unit tests，並在 iPhone Simulator 實際跑過英文直向／橫向控制、確認式媒體刪除、繁中連線與 Desktop Bridge 連線表單流程。這些證據不能取代實體 iPhone 與 EOS R6 Mark III 的驗證紀錄；細節請見 [docs/ios-ccapi.md](docs/ios-ccapi.md)。

## Desktop Bridge

Desktop Bridge 是可執行的本機服務，也是 PC 控制 App。它可以透過 `gphoto2` 控制 USB 相機，也能不依賴 `gphoto2`，直接連接相機的無線 CCAPI endpoint。API 與內建介面都會依所選 engine 與相機實際公告的能力，開放身分、狀態、設定、拍照、AF-ON、半按快門、錄影、焦點前後移動、座標 Tap AF 或點選白平衡、JPEG 或相機公告的 RTP H.264 Live View、需身分驗證的按需媒體縮圖、串流下載、需確認後執行的刪除，以及包含 engine 公告能力證據且不含敏感資料的診斷。libgphoto2 設定映射包含 R6 Mark III 白平衡偏移、各卡槽影像畫質、畫面比例、電動變焦速度與自動關閉電源；仍須相機實際公告可寫 choices，未記載的 `0xFFFFFFFF` 電源值會被拒絕，只有單一選項的進階控制不會顯示。拍照前會優先選用相機公告且可寫的「Memory card」目的地；若已是主機 RAM 卻沒有記憶卡選項，就會在送快門前拒絕操作。主機 RAM 的物件傳輸尚未開放。PC RTP 會驗證 Canon SDP，以 RFC 3550／RFC 6184 接收與重組 H.264，由 PyAV／FFmpeg 解碼，再透過既有 Bridge endpoint 輸出受 FPS 上限控制的 JPEG。直接 CCAPI 的 AF-ON 使用相機公告的 `POST /shooting/control/af` start/stop；座標 Tap AF 與點選白平衡會先用 Canon `flipdetail` 的影像幾何資訊換算，再分別送出整數 `PUT afframeposition` 或 `POST clickwb`；USB engine 使用已驗證且確實釋放的半按流程。介面支援英文、繁體中文，以及桌面與窄版響應式配置。正式執行路徑不使用假相機 engine；可重現的 fake 只存在測試中。

先建立下列環境；只有使用 USB 相機時才需要在電腦安裝 `gphoto2`：

```powershell
cd bridge
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\open-eos-bridge.exe
```

若要使用 USB 掃描，再以 `gphoto2 --auto-detect` 確認選用的主機端依賴可正常執行。

在 Windows 上，Bridge 會優先使用 native `gphoto2`；找不到時會自動改用 `wsl.exe --exec gphoto2`。若要使用的 WSL 2 distribution 不是預設值，可設定 `OPEN_EOS_GPHOTO2_WSL_DISTRO`。Windows USB 裝置不會直接出現在 WSL，因此主機還需要 `usbipd-win`，並把 Canon 裝置 bind／attach 到 WSL。改動系統前可先執行唯讀 doctor：

```powershell
.\scripts\windows-gphoto2-doctor.ps1
.\scripts\windows-gphoto2-doctor.ps1 -Json
```

Bridge health 會分別指出缺少 Linux distribution、WSL 內缺少 gphoto2、缺少 `usbipd-win`，或 WSL runner 已可用；doctor 只列出對應命令，不會自行安裝 distribution、driver 或 package。Microsoft 也特別說明，USB 裝置 attach 到 WSL 期間不再能由 Windows 使用，detach 後才會恢復。

在電腦瀏覽器開啟 [http://127.0.0.1:18181/](http://127.0.0.1:18181/) 即可使用 PC 控制介面。選擇「USB 相機」會透過 `gphoto2` 掃描；選擇「無線 CCAPI」則輸入相機 origin，例如 `http://192.168.1.2:8080`，也可提供相機 Basic Auth 帳密。介面只會啟用相機有公告的操作；Bridge token 與相機密碼只留在記憶體且不會寫入診斷。語言、相機 URL 與使用者名稱可以保存在本機。

服務預設只監聽 `127.0.0.1:18181`。Android 模擬器可在連線頁使用 `http://10.0.2.2:18181`；實體手機若要從區網連入，必須明確綁定 LAN 並設定 Bearer token：

```powershell
$env:OPEN_EOS_BRIDGE_HOST = "0.0.0.0"
$env:OPEN_EOS_BRIDGE_TOKEN = "請替換成足夠長的隨機字串"
.\.venv\Scripts\open-eos-bridge.exe
```

在 Android 或 iOS 連線頁選擇「Desktop Bridge」，輸入電腦的區網 URL 與相同 token，再掃描並選擇相機。token 只保留在 App 程序記憶體中，不會持久化，也不會出現在診斷報告。iOS 直接 USB/PTP 仍屬研究項目；目前這條 Bridge 路徑才是 iPhone／iPad 控制電腦 USB 相機的已實作方案。

USB CLI adapter 現在使用持續的 `gphoto2 --capture-movie --stdout` MJPEG 串流，提供 1-30 FPS 輸出上限；若持續命令失敗，會自動降級成最高 5 FPS 的有界逐幀 `--capture-preview`。相機控制命令執行前會關閉 movie process，下一次取幀時再自動恢復；診斷會保留實際 transport 與 fallback 原因。請求 FPS 不代表相機與 USB 一定能維持該速率。CCAPI engine 同樣提供 1-30 FPS，介面初始預設 15 FPS；若 R6 Mark III 對含尺寸的 JPEG Live View 啟動 payload 回覆 `Invalid parameter`，會自動改用相容 payload 重試。若 discovery 公告 Canon RTP endpoint，且 PyAV 與本機可達 IPv4 都可用，自動模式會優先使用 RTP，啟動失敗時完整清理後退回 JPEG；明確選 RTP 則顯示真實錯誤。FPS 在 RTP 下限制解碼後 JPEG 輸出，因 Canon start body 沒有編碼器 FPS 參數。同源 discovery、設定、拍照、快門釋放、錄影、兩種來源下以詳細 metadata 換算的 Tap AF／點選白平衡、有界 JPEG／RTP 解碼、媒體、驗證與能力閘門都有自動測試。PC 與 R6 Mark III 的 RTP 與 USB throughput 真機驗證仍待完成；最近一次 R6 Mark III discovery 並未公告 RTP。

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
- `POST /ccapi/whitebalance/click`
- `POST /ccapi/shutter/half-press`
- `POST /ccapi/shutter/release`
- `GET /ccapi/media`
- `GET /ccapi/media/{itemId}`
- `DELETE /ccapi/media/{itemId}`
- `GET /ccapi/liveview/frame`

## Roadmap

- 保持 R6 Mark III 的 CCAPI 無線控制穩定，並驗證機身公告的 API 是否包含已實作的 Android／iOS／PC RTP H.264 路徑；若未公告就維持 JPEG 輪詢。
- 在 R6 Mark III 真機驗證已實作的 Android USB/PTP 標準路徑，以及 Canon EOS 遠端快門、曝光、色彩、包圍曝光、錄影、進階設定、焦點移動與 Live View；後續只加入有可靠依據的其他專有設定或 Touch AF 命令。
- 在 R6 Mark III 完成已實作 PC CCAPI、Android-to-Desktop-Bridge、持續 gphoto2 USB 預覽與 USB PC 控制介面的真機驗證，並保留 Canon EDSDK 作為使用者自行安裝的 optional adapter。
- 以實體 iPhone 與 R6 Mark III 驗證已實作的 iOS SwiftUI CCAPI App、相機有公告時的 RTP，以及 Wi-Fi／行動網路共存；iOS USB/PTP 先列為研究線。

功能是否真正完成以 [docs/feature-status.md](docs/feature-status.md) 為準；架構與後續路線請看 [docs/architecture.md](docs/architecture.md)、[docs/control-transports.md](docs/control-transports.md)、[docs/android-usb-ptp.md](docs/android-usb-ptp.md)、[docs/desktop-bridge-protocol.md](docs/desktop-bridge-protocol.md)、[docs/ios-ccapi.md](docs/ios-ccapi.md) 與 [docs/reference-projects.md](docs/reference-projects.md)。

## 授權

Open EOS Control 使用 [Apache License 2.0](LICENSE) 授權。

Open EOS Control 與 Canon 無關，也未受 Canon 認可。Canon 與 EOS 是其各自權利人的商標。
