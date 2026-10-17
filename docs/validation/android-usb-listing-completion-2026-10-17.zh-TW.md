# Android USB 媒體清單的完成與 Recent 預算

基準：`4efb9a33901931fcddd0c87a4fe0f3246bbef0fa`。修正 G08/G11 既有 USB/PTP 媒體列舉，不新增 transport、端點、背景工作或自動重試。

## 已重現的三個反例

對 production `UsbPtpCameraBackend.listMedia` 整個宣告作逐 byte 原樣 extraction，再以合成 PTP／model／folder／host-store adapter 編譯執行：基準 6 個 JUnit 案例中，3 個預期正確行為的 assertion 失敗，另外 3 個控制案例通過。

1. 兩個媒體 handle，其中一個必要 `ObjectInfo` 讀取失敗，另一個成功：原碼回傳單列成功清單。`CameraViewModel.refreshMedia` 對成功回傳一律標為 `COMPLETE`，遺漏的列不再有 failed／partial 說明。
2. 一個已儲存 host item，所有遠端 `ObjectInfo` 都失敗：原碼仍以 host item 回傳成功，掩蓋遠端讀取失敗。
3. 相機有 62 個媒體加一個較新的 directory handle，Recent 要求 61 列以保留 60 列與 `hasMore`：原碼先截取 61 個 handle，再排除 directory，結果只回 60 列，使 caller 得到 `hasMore=false`。

這是 production 宣告的 host 執行證據，不是完整 Android backend、真 USB、相機或 UI 執行。

## 修正與保留的契約

- Recent 的預算改在接受有效、非 directory、具檔名的媒體後計數。保留現有 host item 預填政策；只讀取剩餘媒體預算，達標後不再讀下一個 `ObjectInfo`。
- 未指定 `maximumItems` 的全卡遍歷沒有加入 Recent 上限。
- 必要 `ObjectInfo` 失敗仍保留第一個錯誤與已發出的 progress snapshot；不將剩下的成功列或 host item 提升成成功完成。錯誤在 optional rating fanout 與 terminal completion 前拋出，caller 使用現有 `FAILED` 狀態。
- 取消仍優先傳播，即使之前已記錄另一個讀取錯誤；不自行 retry。
- optional rating 查讀失敗仍是未知 metadata，不會使已完成的必要清單遍歷失敗。
- 若相機在遍歷中刪除 handle，`INVALID_OBJECT_HANDLE` 仍以該次必要讀取失敗呈現，使用者可明確重新整理。沒有宣稱相機內容在整次遍歷期間固定，也沒有新增 strict atomic inventory 語意。

## 本地證據

- 基準 production 宣告 host runner：compile exit 0，JUnit 6 案例中 3 個失敗，對應上列反例。
- 修正後同一 extraction 方式：**13/13 JUnit PASS**。除原 6 案例，涵蓋 host 部分／完全預填、無檔名非媒體、全卡超過 Recent 上限、保留第一個錯誤與先前 50 列 progress、錯誤後取消、optional rating 失敗。
- 使用原已存在、SHA-256 對照 receipt 的離線 Kotlin／JUnit 工具，沒有安裝或下載依賴。
- 來源 wiring guards **4/4 PASS**；兩個修改 Kotlin 檔的 PSI syntax check 通過；`git diff --check` 通過。來源斷言與 syntax check 都不是 Android typecheck。
- `UsbPtpObservedFoldersTest` 另新增 6 個完整 backend＋合成 PTP container 測試，涵蓋 partial progress、消失 handle 的明確重試、host item 不掩蓋錯誤、Recent directory budget、host 預填與全卡；這批完整 backend 測試本輪 **NOT_RUN**。

## Release assessment 與未驗界線

Release impact：**patch**，修復原有清單完成與 Recent 更多資料判斷。此窄範圍已本地實作並具 host 證據；不據此宣稱 PR ready、main accepted 或 released。

完整 Gradle／Android backend unit、Compose/instrumentation、真 USB transport／相機、大卡耗時與實體卡片在拍攝／刪除中的行為仍 **NOT_RUN**。本修正不證明完整相機 inventory 在同一時刻一致，也不修改未實作的 iOS direct USB、EDSDK 或 gphoto2 mutation 能力界線。
