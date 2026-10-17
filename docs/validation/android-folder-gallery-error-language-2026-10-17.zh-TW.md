# Android 資料夾／圖庫儲存失敗的語言補齊

基準：`144db341b6a81c5e938dbf17fdc3473ffde2d5c5`。既有原列 **#76 Media download／G09＋G13** 的有界延續。

## 已確認的相鄰路徑

- 資料夾儲存的 production caller 先呼叫 `createMediaDocument`。`DocumentsContract.createDocument` 回傳 null 時，原先仍拋出 App 英文原因；前一批 null-output 修正不會涵蓋這個更早的建立失敗。
- `CameraMediaGalleryStore` 的手動儲存有五種相鄰 App 原因：insert 回傳 null、output 回傳 null、空檔／回報寫入數不一致、原檔預期長度不符、publish update 不等於 1。它們經既有 `formatException` 進入每檔 `Failed`。
- 同一 Gallery helper 也供自動匯入使用。runner 根據 exception 類別與 receipt flags 判斷結果，不比較錯誤文字；ViewModel 對未發布且非取消的項目另有固定英文 fallback。此 fallback 一併改為 token，避免只修 manual caller。

## 修正與保留

以上七種 App 原因使用既有 exact-token 英文／繁中呈現。原 `check`／`error` 仍以 `IllegalStateException` 子類別失敗；條件與先後不變。

- 資料夾顯示一般建立失敗原因。檔名仍原樣保留在 exact item、viewer 標題與 provider create 參數；沒有為了套用 token 修改檔名、MIME、URI 或相機 identity。原 filename 的清理／fallback 政策不變。
- 手動 provider 自己拋出的原始例外保持不變；沒有翻譯相似診斷字串。
- 沒有改動建立後的 owned cleanup、成功 publication／close 收據、進度與 owner fences。
- cleanup-unconfirmed 維持既有獨立訊號／警告；自動匯入先判斷 published，再判斷取消，最後才使用未發布 fallback。runner 的 published／failed／cleanupConfirmed 分類不變。
- 自動專用 JPEG eligibility、MIME、identity、decode、encoded-size 等原始診斷仍由原 fallback 包住，不擴大改寫。相鄰 delete helper 的錯誤已有 cleanup-unconfirmed 呈現，也不改動。

## 執行證據與限制

使用已存在並核對 SHA-256 receipt 的離線 Kotlin／JUnit 工具，沒有安裝或下載。

- 完整 production `CameraMediaGalleryStore.kt`，搭配合成 Android resolver／URI／MediaStore／model／decoder adapters 編譯執行。
- 另原樣 extraction SAF create/delete helper、真實資料夾 caller 的 create→open→download→finalization block，以及自動匯入 receipt／fallback／transfer 宣告；直接編譯實際 `MediaOutputFinalization`、`MediaSaveFeedback`、`CameraAppError`。
- 基準 negative control：**9 fail／9 pass**。修正後：**18/18 PASS**。基準共用新增 token helper 作測試支援，不是完整基準 Android build。
- 獨立案例包括 null create 不開啟／刪除未建立輸出、檔名與 MIME 原樣傳遞、null Gallery insert/output、空 bytes、回傳 bytesTransferred 不符、原 item 與回傳 item 兩種 expected size 不符、publish 失敗、原 provider 例外、cleanup unknown、真正確認後的 receipt／不刪除，以及自動匯入取消和 published receipt 優先。
- 來源／雙語資源 guards **10/10 PASS**；五個修改 Kotlin 檔的 PSI syntax check 通過；`git diff --check` 通過。
- 新增一個 Gallery instrumentation case（五個失敗情境），並更新既有 publish-failure test 為 typed identity assertion。它們本輪 **NOT_RUN**；既有 banner locale test 也只是擴大 enum 覆蓋，沒有本輪原生執行。

這些 host 證據包含真實 production 類別／宣告，但周邊是明示的合成 adapter，並非 Android API、真 MediaStore/provider、原生 JPEG decoder、完整 ViewModel 或 Compose 執行。

## Release assessment

影響：**patch**。補齊既有儲存錯誤語言，不更改檔案交付成功條件。

完整 Android build、instrumentation、系統語言切換／Activity recreation、真系統 picker/provider、實體相機仍 **NOT_RUN**。Live View 含 byte/content-type 的動態 decode 診斷留待另行有界評估；沒有刪掉它的診斷資訊，也不宣稱 G13 全部完成。
