# Android：經 Bridge 的相機連線錯誤復原

## 範圍與交付狀態

- 基準 commit：`96ab9a1b5dd42950c130a2e54d7676dea2d4ef52`；基準 tree：`75c9704719fbb232271d92735644d0111341356b`。
- 本批補齊 Android 對 PC／Bridge 可選 `error.connectionReason` 的接收、分類與復原文案。
- 狀態為 **implemented（原始碼及測試來源存在）**。Kotlin 編譯、JVM 測試、Android instrumentation、App 旅程、真機與本批 exact-head CI 均為 **NOT_RUN**。
- 本次未啟動 Gradle、安裝套件、下載依賴、啟動模擬器、執行網路驗收、commit、push 或 CI；本地 AGP／依賴驗證依整批工作安排暫緩。
- 來源參考：`docs/validation/pc-connection-guidance-recovery-2026-10-11.zh-TW.md` 所描述的 Bridge 錯誤合約；Android 既有 `ConnectionFailureProtocolTest` 與 connection recovery 流程。

## 可接受的錯誤證據

Android 只有在明確執行相機掃描 `GET /v1/cameras` 或建立工作階段 `POST /v1/session`，且實際 HTTP status 為 `502`、JSON `error.code` 恰為字串 `CCAPI_DISCOVERY_FAILED`、`error.engine` 恰為字串 `ccapi` 時，才接收可選 `error.connectionReason`。既有 Bridge base path 保留。

允許值封閉為以下八項：

- `AUTHENTICATION_REJECTED`
- `TLS_ERROR`
- `HOST_NOT_FOUND`
- `TIMEOUT`
- `UNREACHABLE`
- `HTTP_ERROR`
- `DISCOVERY_FAILED`
- `ENDPOINT_NOT_FOUND`

`connectionReason` 必須是精確、大小寫相符的 JSON 字串；不修剪空白、不使用 `optString` 強制轉型、不解析 message 或 code 裡的診斷文字。缺少、null、數字、布林、陣列、物件、未知值、`UNKNOWN` 與 `INVALID_ADDRESS` 均不採用。非契約 status、code 或 engine 亦保留既有 HTTP 分類。

`DesktopBridgeException` 保留原型別、實際 status、code、message、feature 與 engine，僅增加預設為 null 的已驗證 enum 欄位。Bridge 自己的 HTTP 401／403 仍是 Bridge 存取失敗；JSON 宣稱的相機原因不能覆蓋它。

`/health`、已建立工作階段的讀取／命令、停止、媒體與關閉請求未開啟 camera reason 解析。即使收到相同的完整 JSON 錯誤合約，也維持原本的 HTTP 證據。`SHUTTER_RELEASE_UNCONFIRMED` 的例外包裝、release session 狀態與 UI 安全警示優先權維持。

## 復原畫面

分類器以相同的第一個有效原因同時產生 reason 與 `cameraViaBridge`，避免從不同 cause 拼出錯誤來源。cause traversal 保留最多 16 層；先檢查範圍內的 cancellation，再分類，也能處理循環 cause。

`ConnectionRecovery.cameraViaBridge` 預設為 false，僅在桌面 Bridge 連線目標與已驗證的相機原因同時成立時設為 true。復原狀態只保留 enum、連線目標與布林，不保存 URL、token、帳密、裝置識別或伺服器本文。

英文與繁體中文各有八項專用相機復原文案：

- 驗證拒絕：檢查橋接電腦上設定的相機 URL、Camera Control API 帳密及相機存取設定。
- TLS：檢查相機 HTTPS URL、憑證，以及相機與橋接電腦的日期時間。
- DNS、逾時與無法到達：檢查橋接電腦到相機的網路、名稱解析、位址／連接埠及相機喚醒狀態。
- HTTP、端點與探索：檢查相機 CCAPI 狀態及橋接電腦上的相機 URL 設定。

此來源不顯示 Bridge token 修正說明，也不指向 Android Bridge 頁面不存在的相機帳密欄位。Dismiss 不修改欄位、不重試；掃描與連線仍需使用者明確操作。直接 CCAPI、Bridge token 與 USB 的既有對應保留。

## 測試來源與仍待執行的驗收

本批只新增或擴充測試來源，沒有移除既有斷言或增加既有期限：

- `ConnectionFailureProtocolTest`：真實 MockWebServer 回應經正式 client，涵蓋八值白名單、錯誤型別與格式、缺欄位／未知值、status／code／engine gate、Bridge 401／403 優先權、成功回應不能注入失敗、base path 下的 session creation、health／已建 session 的讀取／命令／停止／關閉負例、release safety、取消不重試。
- `ConnectionFailureTest`：包裝原因、同一 cause 的來源配對、外層 HTTP 錯誤不借用內層 camera origin、例外建構器的封閉白名單、cancellation 優先與 16 層界線。原循環 cause 測試保留。
- `CameraConnectionConfigurationRecoveryTest`：正式 VM → repository → client → MockWebServer → recovery；涵蓋八原因、session creation、Bridge 401／403、舊版／未知值 fallback、安全狀態不洩漏本文、Dismiss 與明確重試、取消後晚到原因不能重開錯誤畫面，以及 release safety 不變成 connection recovery。原 4 秒等待期限與 gate 期限保留。
- `ConnectionRecoveryStateTest`：純 resource ID 對應、直接 CCAPI／Bridge token／USB 區分、安全狀態只含 bounded evidence，以及 wrapped cancellation。
- `CameraConnectionRecoveryUiTest`：既有注入狀態的 Compose fixture 中，八原因各以英文 320×480 與繁體中文 480×320、2 倍字級檢查可捲動文案、文字裁切、Dismiss 點擊範圍與掃描控制項；確認錯誤 token／直接相機說明及相機帳密欄位不存在，且無自動連線、掃描或 token 變更。這是待執行的 UI fixture，不能代稱已完成 production App 或真機驗收。

本次僅完成原始碼檢視與 `git diff --check`，不得將其列為 Kotlin、JVM、instrumentation 或 App 驗收通過。後續需對整批最終 tree 執行必要編譯、JVM、Android UI 及 exact-head CI；真機仍需另外記錄實際相機、transport、app build 與操作。

## Release Assessment

- 原始碼版本：`0.13.0`；最近已發布基線由整批交付另行核實。
- 建議影響：`patch`，修正既有 Android 經 Bridge 的錯誤復原方向；不增加相機控制能力或破壞必要 Bridge 欄位。
- 本批不修改版本、release、安全設定、TLS 信任、權限、重試政策或必要驗收。
- 未解除編譯／執行驗證與 exact-head CI 的交付門檻；本文件不授權合併或發布，亦不構成真機相容性聲明。
