# Android 即時顯示解碼失敗：保留診斷與目前 UI 語言

基準：`e0f9cbc4166dd226316e3a4abe52545f1d092177`。範圍為 Android G06／G13 的 **ViewModel 收到影格後，BitmapFactory 回傳 null** 這一條 App 自訂錯誤路徑。

## 缺陷與呼叫證據

- `CameraViewModel.readLiveViewFrameInternal()` 原先將 null decode 組成英文 `IllegalStateException`，附上 byte count／content type；`formatException` 將它當一般診斷，`retireFailedLiveView` 存入 `CameraUiState.error`，`ErrorBanner` 原樣顯示。保留中的錯誤不會隨繁中／英文 UI 語言變動。
- 若 content type 包含 `SocketTimeoutException`，舊 banner 的 safety substring mapper 會把整個解碼錯誤換成逾時提示，連原本的解碼原因與診斷也丟失。
- `retireFailedLiveView` 會清除 `LiveViewDiagnostics` 與畫面，所以不能只把 byte count／content type 搬到該欄位而不處理清除時序。
- 可達路徑的來源證據：CCAPI `readFirstJpegFrame` 接受 SOI／EOI marker framing；Desktop Bridge client 的 `isCompleteJpeg` 也只做頭尾 marker 檢查。`FF D8 FF D9` 可通過這些 framing 檢查，但它不提供可解碼影像。新 native 案例在健康預覽之後送出這種影格，以避免測到 first-source validator。**本輪未執行 Android decoder**，實際 `BitmapFactory` 行為仍由尚未執行的 instrumentation 案例驗證。舊測試的 `01 02 03` 會在 CCAPI reader 提早失敗，不能代替本案例。

## 窄修正

- 新增獨立 `LiveViewFrameDecodeException`／`LiveViewDecodeFailure`，不改既有 `CameraAppError` enum、無參數 token 或 `CameraUiState.error` 型別。
- 只有最外層 typed exception 經 `formatException` 產生此單一錯誤的內部 token。byte count 必須非負；null 與空字串分開，content type 使用固定四位小寫十六進位 UTF-16 code unit，保留 Unicode、控制字元、百分號、分隔符與未配對 surrogate。
- Parser 僅接受整串 canonical token，parse 後重建必須完全等於原文；不 trim、不查 provider 診斷的子字串、不從 cause 中抽取 token。
- `ErrorBanner` 先處理此 typed payload，再走原有 mapper／raw fallback；繪製時用 `stringResource` 與兩個型別明確的參數。null content type 使用目前語言的「未知內容類型」，空字串保持空白。content type 當作 `%2$s` 的資料，不當作格式字串或錯誤類型。
- owner／generation fence、取消傳遞、終止輪詢、Stop reconciliation、顯式重新開始、錯誤清除與安全警告優先順序不變。沒有新相機命令或自動重試。

## 本地證據

1. 使用既有離線 Kotlin 2.2.21／JUnit 4.13.2 jars，先逐一對照既有 installation receipt 的 SHA-256，沒有下載或安裝。
2. Host harness 擷取完整 production `readLiveViewFrameInternal`、清除／退役／formatter 宣告與 banner display expression；編譯實際新 helper、原 `CameraAppError` 與 `LiveViewLifecycle`。周邊 bitmap decoder、repository、state、Compose annotation／resource 呼叫是明示合成 adapter，字串值取自實際兩語資源。這不是完整 ViewModel typecheck 或 Android／Compose runtime。
3. 同一 caller harness 對基準得到 **10 案中 4 FAIL／6 PASS**，失敗包括英文錯誤、保留錯誤的語言切換、null fallback 及錯誤的 legacy timeout 分類。修正後 caller **10/10 PASS**；加上實際 helper unit tests **6/6 PASS**，合計 **16/16 PASS**。涵蓋 canonical 拒絕、原始 provider／cause、Unicode／控制字元、null／empty、Int 邊界、舊 owner／attempt、取消及成功解碼控制。
4. 新來源／資源 guard **4/4 PASS**，相鄰 app-error guard **7/7 PASS**；兩語 placeholder 均為 `%1$d`／`%2$s`，resource keys 無重複。`git diff --check` 通過。
5. 六個變動 Kotlin 檔的 PSI syntax check 通過；這是語法檢查，不是完整 Android typecheck。
6. 新增 **3 個 instrumentation cases，NOT_RUN**：實際 bitmap null 後退役／Stop／顯式重啟、同一錯誤在繁中→英文→繁中與不同 content type 的呈現、provider-wrapped token 保留原文。

Host evidence 目錄：`android-liveview-decode-language-evidence-20261017`（本地 EOS 工作區）；包含 runner、baseline/final compile 與 JUnit logs、receipt、PSI source/log。Host runner SHA-256：`dfea60fd53a883c0687d592b7bf77f975e9741fba72890daa4e5390bcb480882`。Production helper SHA-256：`fabadecda2df1c5d5157d8af529acad5f7c12f5ef4c8fc03d7999b0fb658df9c`。

## Release assessment 與剩餘邊界

本地 release baseline 為 `0.13.0 Development Preview`（本輪未查 remote release）。Impact：**patch**，修正既有 Android 錯誤呈現並保留診斷；不需新增能力或協定版本。尚未宣稱 PR ready、main accepted 或 released。

完整 Gradle／Compose typecheck、3 個 instrumentation 案例、Android 系統真實語言切換與 Activity recreation、實體相機／USB／Bridge 解碼與 Stop／重連驗收仍 **NOT_RUN**。本次只處理上述 typed App failure；其餘 camera／provider／transport 診斷維持既有呈現，沒有廣泛翻譯或完整 G06／G13 驗收聲明。
