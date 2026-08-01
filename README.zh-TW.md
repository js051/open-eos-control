# Open EOS Control

[English](README.md) | 繁體中文

Open EOS Control 是一個非官方、開源的 Canon EOS 控制專案。第一個真機優先目標是 Canon EOS R6 Mark III，架構上讓 PC、iOS、Android 三端共用同一套相機控制概念。

目前的開發預覽版為 [v0.1.8](docs/releases/v0.1.8.md)，用途是測試與收集貢獻者回饋，不建議用於正式拍攝流程。

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
- 顯示相機身分、transport、profile 與電池；CCAPI、USB/PTP 或桌面橋接有回報時，亦顯示記憶卡數、總容量、可用容量及剩餘可拍張數
- 顯示有數量上限且遮蔽敏感資訊的能力證據，包括探索來源、協定版本、相機公告命令、可寫設定，以及本次工作階段中確實成功的操作
- Android、iOS 與 PC 都提供工作階段限定的真機驗證，只列出相機已公告且本次操作確實成功的功能；必須再由操作人員確認相機端結果，模擬器／離線預覽不能充當證據，複製的隱私安全 Markdown 會以平台原生 SHA-256 綁定對應診斷報告
- Android、iOS 與 PC 都提供依能力開放的相機日期／時間同步。直接 CCAPI 必須同時公告 GET 與 PUT `/functions/datetime`，寫入 Canon 指定的 RFC 1123 時間與 DST 狀態後再回讀驗證。Android 直接 USB 會透過 `SetDevicePropValueEx` 寫入相機公告的 Canon EOS `UTCTime (0xD17C)` 或 `CameraTime (0xD113)` UINT32，並要求收到相符的屬性事件。Desktop Bridge USB 必須同時取得相機公告的可寫 libgphoto2 `syncdatetimeutc`／`syncdatetime` action 與對應 `datetimeutc`／`datetime`，執行後強制重新讀取 config，且回報時間須落在操作時間區間前後十秒內。
- Android USB/PTP 權限與介面診斷、實際 PTP session、相機身分、儲存卡、媒體瀏覽／縮圖／下載／刪除、相機有公告時的標準拍照／屬性控制，以及依能力開放的 Canon `GetEvent` 機身操作與相機時鐘同步。只有手機與記憶卡兩條路徑都可執行時才顯示拍攝儲存位置選擇；選擇手機會把 Canon 主機 RAM JPEG／RAW 原子傳輸至 App 私有 Media。另包含 Canon EOS 遠端快門、半按、保證取消的原生 AF-ON、拍攝模式、ISO／Tv／Av／白平衡、曝光補償、色溫、白平衡偏移、色彩空間、畫面比例、電動變焦速度、自動關閉電源、自動亮度優化、高 ISO 感光度消除雜訊、AEB、錄影開始／停止、自動對焦操作／方式、連續自動對焦、驅動、測光、相片風格、各卡槽 RAW／cRAW／JPEG 畫質、短片伺服自動對焦、焦點移動與 JPEG Live View
- Desktop Bridge 掃描、Bearer 驗證、多相機選擇、session、動態能力／設定、拍攝、AF-ON、Live View、焦點移動，以及依能力提供的媒體縮圖／傳輸／刪除；libgphoto2 路徑也包含經 runtime 探測的 `gphoto2 --wait-event` 機身／媒體同步、R6 Mark III 自動對焦啟動／取消 action、各卡槽影像畫質、白平衡偏移、畫面比例、電動變焦速度、安全的自動關機與自動亮度優化選項，以及可選擇寫入記憶卡或將主機 RAM 拍攝原子轉存到 Bridge 媒體庫的儲存位置
- Live view 畫面，自動/手動更新與 FPS 控制
- Android Live View 監看輔助會對解碼後的 JPEG／USB／Bridge 影格套用匯入的 3D `.cube` LUT，並以有界 120x80 背景分析提供互斥的亮度直方圖或 64x64 亮度波形圖、可調斑馬紋、偽色、峰值對焦、16:9／2.39:1／1:1／4:3 畫幅框線、動作／標題安全區域，以及 1.33x／1.5x／1.8x／2x 變形鏡頭反擠壓。LUT 使用單一 conflated worker 處理最新影格，不會累積工作；原生 RTP 保留零拷貝表面，因此 LUT 與像素分析停用，只保留幾何型框線與反擠壓。
- iOS Live View 監看輔助使用單一 conflated worker 搭配 Core Image `CIColorCube` 套用匯入的 3D `.cube` LUT，並沿用相同的有界 120x80 分析契約，支援解碼後 JPEG／Bridge 影格的直方圖／亮度波形圖選擇、斑馬紋、偽色、峰值對焦、畫幅框線、安全區域與反擠壓；RTP 保留原生 sample-buffer 顯示，因此只開放幾何型功能。
- PC 控制介面透過浮點 WebGL2 3D texture 與明確的三線性插值，對 Bridge 解碼影格與本機 UVC／HDMI 視訊輸入套用匯入的 3D `.cube` LUT，再執行同一套有界 120x80 分析。監看輔助視窗也提供直方圖／亮度波形圖、斑馬紋、偽色、峰值對焦、畫幅框線、安全區域與變形鏡頭反擠壓，不會改動相機命令或偽造拍攝結果。本機視訊只替換取景畫面，Bridge session 仍持續控制相機；裝置 ID／名稱及 LUT 檔名／標題只留在記憶體且不會寫入診斷。相機媒體下載會顯示位元組進度並可取消；一般檔案使用串流式瀏覽器下載 fallback，未知大小或至少 64 MiB 的檔案則在瀏覽器支援時直接寫入具暫存保護的目的檔。

LUT 匯入刻意只支援有界的 3D `.cube` 子集：2 到 64 階、Red-fast 資料列、`DOMAIN_MIN`／`DOMAIN_MAX` 或 `LUT_3D_INPUT_RANGE`、有限數值與三線性顯示插值。混合 1D／shaper LUT 或超過 16 MiB 的檔案會回報明確錯誤；專案不重新散布 Canon 或第三方 LUT。
- Android、iOS 與 PC 上依能力開放的 Canon CCAPI RTP H.264 Live View：使用可達路由的 UDP、RFC 3550／RFC 6184 封包處理、手機原生顯示或 PC PyAV 解碼，可切換自動／RTP／JPEG 來源並限制 1-30 FPS 顯示／輸出幀率。三個平台也會處理相機公告、使用帶內設定的 RFC 6416 `MP4A-LATM/48000` 音訊，且都預設靜音；Android 使用 Media3 LATM 解析、`MediaCodec` 與 `AudioTrack`，iOS 使用有界 Swift LATM 解析器、`AVAudioConverter` 與 `AVAudioEngine`，PC 則將有界 PCM 送至 WebAudio。手機端只有在使用者明確開啟後才解碼播放，App 進入背景時會再次靜音；只有相機同時公告兩個 RTP endpoint，且本機有可達 IPv4 與可用影像 decoder 時才會出現 RTP
- ISO、shutter、aperture、white balance 與動態 advanced settings，包含相機公告的 Canon CCAPI RAW／JPEG／HEIF 畫質及有界 B/A／M/G 白平衡偏移，並依規格以完整物件寫回
- 可選跟隨系統、英文或繁體中文；相機公告的設定值會本地化顯示，寫入時仍保留精確的協定原值
- 固定於底部的拍照／錄影模式選擇器會在快門旁持續顯示目前操作情境；只有相機公告可寫且相符的機身模式時才同步寫入。模式文字與短選取底線會在固定、避開實體邊緣的 48dp 觸控槽內一起旋轉，因此手機側放時不會把整塊選取背景變成遭裁切的直條。曝光轉盤在寫入期間會鎖定，完成後再對齊相機確認值，因此遭拒或重疊的請求不會看起來像已套用。
- 相機式方向行為：App 固定在裝置自然顯示軸，拍攝布局與設定面板外框不換位，只旋轉需要隨方向閱讀的內容。Activity 會在啟動前景方向感測器前先讀取 Android 系統「自動旋轉」政策；即使 Quick Settings 暫時取得焦點，公開設定通知也會立刻套用，回到 App 時再核對一次，旋轉鎖定期間則完全停用方向感測器。HUD 短標示與曝光值會在固定方形控制槽內轉向，電量與儲存空間採圖示在上、精確數值在下的原子槽，完整資料仍可從無障礙描述與狀態面板查看。完整取景提示在 0／180 度採置中堆疊，在 90／270 度改用依取景區長軸量測的橫列，繁中與英文說明都不刪除；捲動設定則交換內部量測軸並使用整個固定面板，不移動快門、曝光列或工具列。
- REC 開始/停止
- Canon 座標 Tap AF：只有相機公告 `PUT afframeposition` 且詳細 Live View 提供影像座標時才開放，不會把 0..1 座標直接猜測成機身命令
- Canon 點選白平衡：只有相機公告 `POST clickwb` 且詳細 Live View 提供影像座標時才開放，取景畫面可切換點選對焦／點選白平衡
- 獨立 AF-ON：CCAPI 使用相機公告的自動對焦命令；Canon USB 優先使用 `DoAf`／`AfCancel`，沒有這組專用操作時才以確實釋放的半按流程作為後備
- 依相機公告能力執行手動快門半按，並保證送出釋放命令
- CCAPI、Android USB/PTP、Desktop Bridge、PC 與 iOS 共用依能力開放的 Bulb 長曝光：只有相機公告 Bulb 模式與完整按壓／釋放路徑時，中央快門才切換成可計時的開始／停止控制；曝光期間暫停主動 JPEG 輪詢，失敗可重試釋放，結束工作階段也會盡力釋放快門
- 支援分頁與按需縮圖；相機公告時的 CCAPI 相片／RAW 全螢幕預覽；依單筆能力開放、上限 32 MiB 的 Android USB/PTP 與 Desktop Bridge JPEG／PNG 預覽；透過 Android 文件選擇器串流下載大型檔案；以及需確認後才執行的刪除。有線 RAW、HEIF 與影片仍可下載，但不顯示無法執行的預覽按鈕。

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
.\scripts\android-gradle.ps1 connectedDebugAndroidTest
.\scripts\android-gradle.ps1 :app:assembleDebug
```

`connectedDebugAndroidTest` 需要先啟動 Android 模擬器或接上裝置。Compose 測試涵蓋連線、控制、Debug、媒體、語言選擇、離線預覽，以及 `360 x 800` 直向、`800 x 360` 橫向和 `1.5x` 字體下主要控制是否保持可見。

若要納入 Android UI 到 HTTP Simulator 的真實路徑，請在另一個終端啟動假相機，並要求裝置測試必須連線成功：

```powershell
Set-Location simulator
python -m pip install -e .
python -m uvicorn main:app --host 0.0.0.0 --port 18080
```

```powershell
.\scripts\android-gradle.ps1 connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.requireSimulator=true
```

此流程會透過 `10.0.2.2` 連線、解碼 Live View 畫面、修改 ISO、拍照、開始與停止錄影、確認新增媒體，最後斷線。每次 pull request 與推送至 `main` 時，CI 都會執行這條路徑。

debug APK 會輸出到：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions 會在 push 到 `main` 與 pull request 時跑 unit test、debug build，以及 Pixel 5 API 34 模擬器上的 Compose 儀器測試。

開發版完全由 GitHub Actions 線上建置。`vX.Y.Z` tag 必須符合所有平台的版本，且指向已存在於 `main` 的 commit；完整歷史機密掃描及 Android、Desktop Bridge、Windows standalone、Simulator、iOS 驗證全部通過後，release workflow 才會把 Android debug APK、可直接執行的 Windows x64 Desktop Bridge、跨平台 Bridge wheel／source distribution、release notes 與 SHA-256 校驗檔發布為 GitHub prerelease。

iOS App 會在 iPhone Simulator 完成編譯與測試，但 Release 不會附上可安裝的 IPA。實體裝置發行需要 Apple Developer Team、distribution certificate 與相符的 provisioning profile；這些簽章憑證都不會存放在公開 repository。

## iOS App 與相機 Core

`ios/OpenEOSCore` 是原生 Swift Package，包含 CCAPI 與具 Bearer 驗證的 Desktop Bridge client。CCAPI 會解析 Canon 公告的同源完整 `url` 或相對 `path`，再依 API 版本與 operation 建立能力；除了完整 JPEG Live View 生命週期，也會在相機公告兩個 RTP operation 時驗證 Canon SDP、RFC 3550 與 RFC 6184 H.264 access unit，並負責精確清理 RTP start／stop。Bridge 會驗證服務、掃描 USB 相機、管理 session，並把動態能力映射到同一套模型。兩條路徑都依能力支援設定控制、Live View、拍照、相機時鐘同步、Bulb 計時開始／停止、獨立自動對焦、半按、錄影、對焦、媒體瀏覽／下載／刪除，以及含版本、產生時間、公告／實測差異與有界能力證據的診斷報告；帳密與相機序號都會遮蔽。套件包含可重現的 HTTP 契約測試，並由 macOS GitHub Actions job 實際編譯：

```bash
cd ios/OpenEOSCore
swift test
```

`ios/OpenEOSControl` 是 iOS 17 SwiftUI App，提供 CCAPI 直接連線，或輸入 Desktop Bridge URL／token 後掃描並選擇 USB 相機；同時具備離線 UI 預覽、依能力開放的拍照／錄影與手動焦點驅動、JPEG 或相機公告的 RTP H.264 Live View、曝光設定 sheet、具真實位元組進度與取消操作的檔案式媒體傳輸、需確認後執行的刪除、遮蔽敏感資料的診斷、手動語言選擇，以及安全的直向／橫向布局。RTP 會使用和相機同子網的 Wi-Fi IPv4、限定 Wi-Fi 的 Network.framework UDP listener、系統原生 sample-buffer 顯示、自動／JPEG／RTP 選擇與 1-30 FPS 顯示上限；若 SDP 另行公告帶內 `MP4A-LATM/48000`，App 會獨立接收並以 `AVAudioConverter`／`AVAudioEngine` 提供預設靜音、前景限定的相機音訊。自動模式若 RTP 啟動失敗，會先完整清理再退回 JPEG。Bridge token 與 CCAPI 密碼都只留在記憶體。整個視窗不會上下顛倒，只有關鍵控制會依實體裝置方向旋轉。

在具備 Xcode 與 XcodeGen 的 macOS 主機執行：

```bash
brew install xcodegen
cd ios/OpenEOSControl
xcodegen generate
open OpenEOSControl.xcodeproj
```

GitHub Actions 會建置未簽章的 Simulator App bundle、確認 ICON／語系／區網／方向 metadata，執行 App unit tests，並在 iPhone Simulator 跑過七個 UI 流程。除了英文／繁中、方向、媒體、監看與 Desktop Bridge 的離線狀態，CI 還會強制啟動真實的假相機服務，從正式 SwiftUI -> `CameraAppState` -> `OpenEOSCore` -> CCAPI HTTP 路徑驗證已解碼 Live View、ISO 控制、拍照、成對釋放的 AF-ON 與半按快門、座標 Tap AF、點選白平衡、手動焦點驅動、僅在 Bulb 模式成立的開始／停止曝光、錄影、已解碼媒體預覽、確認後刪除與斷線。workflow 明確使用 `CODE_SIGNING_ALLOWED=NO`，因此這個 build 無法安裝到實體 iPhone，也不會作為 IPA 發布；可重現的 Simulator 證據仍不能取代實體 iPhone 與 EOS R6 Mark III 的驗證紀錄。細節請見 [docs/ios-ccapi.md](docs/ios-ccapi.md)。

## Desktop Bridge

Desktop Bridge 是可執行的本機服務，也是 PC 控制 App。它可以透過 `gphoto2` 控制 USB 相機，也能不依賴 `gphoto2`，直接連接相機的無線 CCAPI endpoint。API 與內建介面都會依所選 engine 與相機實際公告的能力，開放身分、狀態、設定、拍照、Bulb 計時開始／停止、AF-ON、半按快門、錄影、焦點前後移動、座標 Tap AF 或點選白平衡、JPEG 或相機公告的 RTP H.264 Live View、需身分驗證的按需媒體縮圖、串流下載、需確認後執行的刪除，以及含版本、時間與公告／實測差異且不包含帳密或相機序號的診斷。libgphoto2 設定映射包含 R6 Mark III 白平衡偏移、各卡槽影像畫質、畫面比例、電動變焦速度、自動關閉電源與拍攝儲存位置；仍須相機實際公告可寫 choices，未記載的 `0xFFFFFFFF` 電源值會被拒絕，只有單一選項的進階控制不會顯示。選擇「記憶卡」時沿用相機端拍攝；選擇「電腦（Internal RAM／SDRAM）」時，必須由相機公告 image capture，並使用 gPhoto2 的 capture-and-download 生命週期。檔案在下載並刪除相機端暫存物件前只存在同磁碟 staging，命令成功後才原子移入 Bridge 媒體庫，供縮圖、串流下載與刪除。PC RTP 會驗證 Canon SDP，以 RFC 3550／RFC 6184 接收與重組 H.264，由 PyAV／FFmpeg 解碼，再透過既有 Bridge endpoint 輸出受 FPS 上限控制的 JPEG；若 SDP 同時公告 in-band RFC 6416 `MP4A-LATM/48000`，另一個獨立接收器會輸出有界 48 kHz stereo PCM，只有使用者按下喇叭控制後才透過 WebAudio 播放，音訊失敗不會中止影像。直接 CCAPI 的 AF-ON 使用相機公告的 `POST /shooting/control/af` start/stop；libgphoto2 USB 優先使用 runtime 的 `autofocusdrive`／`autofocuscancel` action pair，缺少時才退回確實釋放的半按流程。座標 Tap AF 與點選白平衡會先用 Canon `flipdetail` 的影像幾何資訊換算，再分別送出整數 `PUT afframeposition` 或 `POST clickwb`。介面支援英文、繁體中文，以及桌面與窄版響應式配置。正式執行路徑不使用假相機 engine；可重現的 fake 只存在測試中。

Windows x64 可直接從 Release 下載 `open-eos-control-bridge-windows-x64-X.Y.Z.exe` 並執行。檔案已包含 Python runtime、PyAV／FFmpeg 與瀏覽器控制介面；內嵌服務準備完成後會自動開啟 loopback 控制頁。使用期間請保留主控台視窗，關閉視窗即停止服務；加入 `--no-browser` 可停用自動開啟瀏覽器。無線 CCAPI 與本機 UVC／HDMI 監看不需另外安裝 Python；USB 相機控制仍需系統中有可用的 `gphoto2` engine。

wheel 仍保留作為跨平台與開發安裝方式：

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

主機 RAM 拍攝預設保存在各平台的使用者資料目錄：Windows 為 `%LOCALAPPDATA%\OpenEOSControl\Captures`，macOS 為 `~/Library/Application Support/OpenEOSControl/Captures`，Linux 為 `${XDG_DATA_HOME:-~/.local/share}/open-eos-control/captures`。若要改位置，可把 `OPEN_EOS_CAPTURE_DIR` 設為絕對路徑。API 與診斷不會輸出這個本機路徑，只會提供不透明的媒體 ID。

在電腦瀏覽器開啟 [http://127.0.0.1:18181/](http://127.0.0.1:18181/) 即可使用 PC 控制介面。選擇「USB 相機」會透過 `gphoto2` 掃描；選擇「無線 CCAPI」則輸入相機 origin，例如 `http://192.168.1.2:8080`，也可提供相機 Basic Auth 帳密。介面只會啟用相機有公告的操作；Bridge token 與相機密碼只留在記憶體且不會寫入診斷。語言、相機 URL 與使用者名稱可以保存在本機。

建立相機控制 session 後，可在「監看輸入」把取景畫面切換成本機 UVC 相機或 HDMI 擷取卡。瀏覽器只要求視訊權限，第一次啟用時可能顯示相機權限提示；請使用預設 loopback URL 或 HTTPS，選擇「視訊裝置」後再啟動預覽。切換來源／裝置、中斷連線或關閉頁面時都會停止所有本機 media track。由於本機視訊不含 Canon Live View 幾何資訊，座標 Tap AF、點選白平衡與對焦放大不會在此來源開放；目前 libgphoto2 焦點前後移動也仍需要相機端 Live View。實作已有自動測試，但 R6 Mark III UVC 與 HDMI 擷取卡真機驗證仍待完成。

服務預設只監聽 `127.0.0.1:18181`。Android 模擬器可在連線頁使用 `http://10.0.2.2:18181`；實體手機若要從區網連入，必須明確綁定 LAN 並設定 Bearer token：

```powershell
$env:OPEN_EOS_BRIDGE_TOKEN = "請替換成足夠長的隨機字串"
.\open-eos-control-bridge-windows-x64-X.Y.Z.exe --host 0.0.0.0 --no-browser
```

wheel 指令也可搭配相同 token，另設 `OPEN_EOS_BRIDGE_HOST=0.0.0.0`。standalone 刻意不提供 token 命令列參數，避免憑證出現在程序清單。

在 Android 或 iOS 連線頁選擇「Desktop Bridge」，輸入電腦的區網 URL 與相同 token，再掃描並選擇相機。token 只保留在 App 程序記憶體中，不會持久化，也不會出現在診斷報告。iOS 直接 USB/PTP 仍屬研究項目；目前這條 Bridge 路徑才是 iPhone／iPad 控制電腦 USB 相機的已實作方案。

USB CLI adapter 現在使用持續的 `gphoto2 --capture-movie --stdout` MJPEG 串流，提供 1-30 FPS 輸出上限；若持續命令失敗，會自動降級成最高 5 FPS 的有界逐幀 `--capture-preview`。相機控制命令執行前會關閉 movie process，下一次取幀時再自動恢復；診斷會保留實際 transport 與 fallback 原因。請求 FPS 不代表相機與 USB 一定能維持該速率。CCAPI engine 同樣提供 1-30 FPS，介面初始預設 15 FPS；若 R6 Mark III 對含尺寸的 JPEG Live View 啟動 payload 回覆 `Invalid parameter`，會自動改用相容 payload 重試。若 discovery 公告 Canon RTP endpoint，且 PyAV 與本機可達 IPv4 都可用，自動模式會優先使用 RTP，啟動失敗時完整清理後退回 JPEG；明確選 RTP 則顯示真實錯誤。FPS 在 RTP 下限制解碼後 JPEG 輸出，因 Canon start body 沒有編碼器 FPS 參數。PC 音訊只會在 SDP 公告 in-band `MP4A-LATM/48000` 時啟用；`cpresent=0`、不支援的 codec／rate、bind 或解碼失敗都會誠實顯示且不影響影像。同源 discovery、設定、拍照、快門釋放、錄影、兩種來源下以詳細 metadata 換算的 Tap AF／點選白平衡、有界 JPEG／RTP 影像與音訊解碼、媒體、驗證與能力閘門都有自動測試。PC 與 R6 Mark III 的 RTP 音訊／影像及 USB throughput 真機驗證仍待完成；最近一次 R6 Mark III discovery 並未公告 RTP。

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
- `POST /ccapi/clock/sync`
- `POST /ccapi/capture/still`
- `POST /ccapi/record/start`
- `POST /ccapi/record/stop`
- `POST /ccapi/bulb/start`
- `POST /ccapi/bulb/stop`
- `POST /ccapi/focus/tap`
- `POST /ccapi/focus/drive`
- `POST /ccapi/whitebalance/click`
- `POST /ccapi/shutter/half-press`
- `POST /ccapi/shutter/release`
- `GET /ccapi/media`
- `GET /ccapi/media/{itemId}`
- `DELETE /ccapi/media/{itemId}`
- `GET /ccapi/liveview/frame`

Android 必跑的裝置流程會使用僅供測試的 reset／state／mode endpoint，逐項確認正式 UI 操作確實抵達 backend；範圍包含 Live View、曝光、拍照／錄影、Tap AF、點選白平衡、AF-ON、半按快門、焦點驅動、Bulb、媒體預覽與刪除。這條可重現的 Simulator 路徑不能取代 R6 Mark III 真機驗證紀錄。

## Roadmap

- 保持 R6 Mark III 的 CCAPI 無線控制穩定，並驗證機身公告的 API 是否包含已實作的 Android／iOS／PC RTP H.264 與 AAC-LATM 路徑；若未公告就維持 JPEG 輪詢。
- 在 R6 Mark III 真機驗證已實作的 Android USB/PTP 標準路徑、Canon EOS 相機時鐘同步、主機 RAM JPEG／RAW 傳輸與記憶卡拍攝，以及遠端快門、曝光、色彩、包圍曝光、錄影、進階設定、焦點移動與 Live View；後續只加入有可靠依據的其他專有設定或 Touch AF 命令。
- 在 R6 Mark III 完成已實作 PC CCAPI、Android-to-Desktop-Bridge、持續 gphoto2 USB 預覽與 USB PC 控制介面的真機驗證，並保留 Canon EDSDK 作為使用者自行安裝的 optional adapter。
- 以實體 iPhone 與 R6 Mark III 驗證已實作的 iOS SwiftUI CCAPI App、相機有公告時的 RTP，以及 Wi-Fi／行動網路共存；iOS USB/PTP 先列為研究線。

## 真機證據

Android、iOS 與 PC 都能在 App 內建立本次連線的真機確認紀錄，診斷報告也可以交給 repository 內的 R6 Mark III 嚴格 verifier。過期、集合不一致、證據截斷或含敏感資訊的報告會被拒絕；必要能力只有在相機公告且本次工作階段確實成功時才會通過。相機端實體結果仍必須由操作人員明確確認。完整流程與可審核的 Markdown／JSON 輸出請見[真機驗證](docs/device-validation.zh-TW.md)。

功能是否真正完成以 [docs/feature-status.md](docs/feature-status.md) 為準；架構與後續路線請看 [docs/architecture.md](docs/architecture.md)、[docs/control-transports.md](docs/control-transports.md)、[docs/android-usb-ptp.md](docs/android-usb-ptp.md)、[docs/desktop-bridge-protocol.md](docs/desktop-bridge-protocol.md)、[docs/ios-ccapi.md](docs/ios-ccapi.md)、[docs/device-validation.zh-TW.md](docs/device-validation.zh-TW.md) 與 [docs/reference-projects.md](docs/reference-projects.md)。

## 授權

Open EOS Control 使用 [Apache License 2.0](LICENSE) 授權。

Open EOS Control 與 Canon 無關，也未受 Canon 認可。Canon 與 EOS 是其各自權利人的商標。
