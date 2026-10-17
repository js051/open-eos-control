# Android 儲存位置錯誤的英繁中恢復訊息

基準：`9a039c7eaff0e9d609a0d8ba0251c3d8dc90f61e`。延續既有 G13 App 錯誤呈現，範圍是原 89 列 **#76 media download** 的文件／資料夾儲存失敗，不增加 provider 權限或背景重試。

## 反例與修正

`downloadMedia` 與資料夾 batch 下載的 `ContentResolver.openOutputStream` 若回傳 null，原碼各自產生 App 撰寫的英文例外。單檔一般錯誤 banner 與 `MediaSaveFeedback.Failed` 的每檔狀態都使用格式化後的原始字串，因此繁中畫面仍有英文原因。

- 兩個 null-output validation 改用 `DOWNLOAD_DESTINATION_UNAVAILABLE` typed App error。
- 一般 banner 沿用前一 checkpoint 的 exact-token 解譯；每檔 `Failed` 先依目前 UI locale 解譯**完整相等的 App token**，再放進既有 `media_save_failed` 包裝資源。
- provider 自己拋出的例外不轉換、不翻譯；未辨識的文字原樣保留。
- `IncompleteFile`／cleanup-unconfirmed 分支保持獨立。沒有改動 staged output 清理、close／confirm 收據、request／camera generation ownership、取消、retry admission 或成功判斷。

## 局部執行證據

使用 EOS 原有並經 SHA-256 receipt 核對的離線 Kotlin／JUnit 工具，對以下 production 片段原樣 extraction：兩個 null-output validation、`formatException`、單檔 failure feedback 分支、完整 feedback status `when`、banner 顯示運算式與既有 network/safety error mapper。另直接編譯實際 `MediaOutputFinalization.kt`、`MediaSaveFeedback.kt`、`CameraAppError.kt`。

周邊是合成 resolver／model／resource adapters；resource adapter 讀取實際英文與繁中 XML。不是完整 ViewModel、Compose、Android provider 或實體相機執行。

- 基準 production 片段：compile 通過，**4 fail／6 pass**。兩個 validation 仍拋英文一般例外、null-output 串接 banner 的原因未翻譯、每檔 renderer 對 typed token 直接輸出 token。
- 此 negative control 共用修正後新增 token/helper 作測試支援，以檢查原有 validation／renderer 是否符合目標契約；不是宣稱基準整個 app 已有新增 token。
- 修正後相同 harness：**10/10 PASS**。包含 null output 清理一次／無完成收據、繁中→英文→繁中、原始 provider 訊息、cleanup unknown 的 `IncompleteFile` 優先、取消傳播、已確認輸出不再刪除，以及原 request／connection reference＋generation 身分要求。
- 來源／資源 wiring guards **7/7 PASS**；四個修改 Kotlin 檔的 PSI syntax check 通過；`git diff --check` 通過。這些不是 Android typecheck。
- 新增一個實際 `MediaViewerDialog` instrumentation case，驗證每檔 typed／raw failure、英繁中切換與明確 retry callback；本輪 **NOT_RUN**。一般 banner 的既有新測試會涵蓋新增 enum，但也仍未在 Android 執行。

## Release assessment 與界線

Release impact：**patch**。修復既有儲存錯誤的 App 語言呈現；沒有修改真正資料交付結果。

完整 Gradle／Compose build、instrumentation、真系統 picker／provider、AppCompat Activity recreation、實體相機／儲存裝置仍 **NOT_RUN**。本次沒有翻譯原始遠端診斷，也沒有把未證實可達的 tap-focus literal 一併修改；不宣稱 G13 全部文案或平台驗收完成。
