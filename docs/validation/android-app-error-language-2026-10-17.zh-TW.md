# Android 維護退役編譯修正與 App 錯誤語言

基準：`aa4a56c37efd58db0031956188002f89130d486f`。對應原 89 列的 **#55 maintenance**、**#78 upload** 與 **G13 產品殼／英繁中／恢復細節**。不是 iOS #128，也不新增 transport 或上傳端點。

## 已確認的缺陷與可達路徑

1. `CameraViewModel.disconnect()` 的 maintenance catch 未匯入 `data.MaintenanceRetirementUnconfirmedException`，且無參數的函式使用沒有宣告的 `appContext`。直接擷取完整 production 宣告後，以同一組最小周邊 adapter 編譯，基準確實回報這兩個 unresolved reference；這是編譯缺陷，不是僅缺少裝置驗收。
2. 媒體清單的 Upload 按鈕在現有能力允許時，經 root `OpenDocument`、具 owner 驗證的 `completeMediaPicker(UPLOAD)` 進入 `uploadMedia`。選到具名稱但為 0 bytes 的檔案後，原碼產生 App 自己寫的英文 `Selected media size is outside the upload limit.`，再經 `formatException` 與 `ErrorBanner` 顯示；選擇繁中不會翻譯它。缺少檔名、無法開啟來源、超出大小與無完成收據同屬這個明確 App validation 範圍。

Android 既有語言選擇使用 `AppCompatActivity`／`AppCompatDelegate`；typed connection recovery card 在繪製時使用 `stringResource`。本次沒有把 iOS 的 system-locale 問題直接套用到 Android，也沒有改動已存在的連線恢復語言流程。

## 修正

- 補上 maintenance exception 匯入；disconnect 使用 typed App 錯誤 token，繪製時才讀取既有 `maintenance_session_retirement_unconfirmed` 英文／繁中資源，不持有 Context，也不新增全域 locale fallback。
- 五種 upload validation 改拋 `CameraAppException`，仍是 `IllegalStateException` 子類別。條件、大小限制、收據判斷、取消／owner fences 與清理流程不變。
- `formatException` 只識別最外層 typed App exception。既有 nullable String error 的清除與 session 生命週期不變；六種 App token 只接受**整串完全相等**的 mapping。
- `ErrorBanner` 以目前 UI 的 `stringResource` 解譯 token，保留原有 safety warning 順序與原始診斷 fallback。不依相似片語、prefix 或子字串翻譯 camera／server／provider 診斷。

## 本地驗證

- **完整 disconnect 與 formatException 宣告 extraction**：基準 compile 失敗，明確包含缺少 exception 匯入與 `appContext`；修正後同樣 adapter compile 通過。實際 `CameraAppError.kt`、4 個 helper unit tests，以及 6 個 extraction tests 合計 **10/10 PASS**，涵蓋 maintenance 兩種責任、generation 拒收、typed formatting、原始訊息與 cause 保留。
- **五個實際 upload validation statement extraction**：基準 compile 通過，但預期 typed error 的 5 案例失敗，合法邊界控制通過；修正後 **6/6 PASS**。包含 0-byte、空檔名、null source、超限、缺收據與合法邊界。這使用合成輸入與大小常數 adapter，沒有實際 provider／檔案搬移或相機呼叫。
- 使用 EOS 原已存在且 SHA-256 對照 receipt 的離線 Kotlin／JUnit 工具；沒有安裝或下載依賴。
- 新增 5 個來源／資源 wiring guard，加上相鄰 upload outcome 9 個 guard，共 **14/14 PASS**。兩種語言資源的 key 完整、無重複。
- 6 個變動 Kotlin 檔的 PSI syntax check 通過；`git diff --check` 通過。PSI 與來源 guard 不是 Android typecheck。
- 另寫入 3 個 instrumentation cases：同一 token 在繁中→英文→繁中後重新呈現、原始 provider 診斷不改寫、完整 ViewModel／picker consume＋合成 provider 的空檔案在送出前產生 typed failure。這 3 個案例本輪 **NOT_RUN**。

## Release assessment 與界線

Release impact：**patch**，修復既有 Android 編譯缺陷與 App validation 的英繁中呈現。此批已具局部 host 證據，不代表 PR ready、main accepted 或 released。

完整 Gradle／ViewModel／Compose typecheck、instrumentation、系統實際語言切換與 Activity recreation、真系統 picker/provider、實體 USB／相機與 Bridge 退役仍 **NOT_RUN**。上述 extraction 明確使用合成 lifecycle／repository／state／R／annotation adapter，不能取代完整 Android build。這六種 App 錯誤修正不表示所有 G13 文案與可達性均已完成驗收。
