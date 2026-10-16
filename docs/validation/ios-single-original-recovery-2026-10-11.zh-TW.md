# iOS 單項原檔準備紀錄與分享租約

## 原 P2 補齊範圍

基線 `77eb43b`。既有批次與前景準備已有有界 App 副本，單項 `startMediaDownload` 仍只保存暫存 URL，替換下載或斷線會直接刪除其父目錄。本批把既有單項按鈕接上同一套保留／離線紀錄與分享租約；不更改批次選取 UI，也不建立單項 planned roster。

- 單項依序 `beginBatch`、`beginItem(expectedBytes: nil)`、`finishPrepared`／`finishFailure`。清單 size 只提示進度；實際原檔需通過既有 production client HTTP 完整性檢查，App 再確認自有一般檔案、非 symlink、回傳 URL／wire ID 與正實際 bytes 一致。已知空檔／framing 損壞不能變成成功。這不是圖片可解碼或來源 hash 的承諾。
- 沿用最多100筆、512 MiB自有保留副本上限，不淘汰既有紀錄、不讓可選 admission／copy／manifest 故障把有效暫存原檔改成下載失敗。有效原檔發布後的取消也不能撤銷成功；保留失敗仍提示重啟副本可能不可用。
- pre-GET 的 caller 取消或 store 傳回 `CancellationError`、`URLError.cancelled`、`MediaDeliveryStoreError.cancelled` 都停止相機原檔請求，不能當普通記錄故障吞掉。非合作 transport／store reply 尚未結束前仍持有 MEDIA；session、token、clear 世代隔離遲到進度與完成。
- 單項暫存改用既有 `MediaBatchOwnedFile`；替換、刪除或斷線只釋放 App 自己的租約。列表與預覽兩個分享控制均以 one-file `MediaBatchExportPayload` 使用既有 `MediaBatchActivityView`；正在分享的來源保留至最後租約釋放。外部 provider 的完成回覆不是 Files／Photos 已保存證據。
- `isMediaDownloaded` 用 UTF-8 wire identity，NFC／NFD 相機 ID 不互相借用原檔。動態 receipt 顯示名稱共用既有去除 URL authority／query／fragment 的清理函式，manifest 不保存相機來源 URL／ID／token。
- 已確認 clear 會退休本次單項的持久紀錄權限，包含 beginBatch 回覆前後、原檔進行中與保留回覆晚到；仍有效的本次暫存可分享。離線重開／分享不增加相機請求；不重連、不 rebind、不自動 retry、不增加 Range。相機既有原檔 representation fallback 不變，因此不宣稱所有相機流程永遠只有一個 HTTP GET。

## 驗證來源與限制

`MediaBatchDeliveryTests` 新增17個單項 production `CameraSession`／CCAPI／Bridge 整合測試方法，沿用受控 HTTP fixture，涵蓋：

- 冷啟動離線原檔重新分享，clear 後暫存與保留副本各自 pin，釋放後清理
- nil／0／負數／過小／極大 listing size 與真實 bytes；record／byte 上限仍保留有效暫存
- nil store、無法建立 root、admission／chunk copy／持續無法替換 manifest，與三種 store admission 取消（cold load／warmed store 都受控）
- 空檔、Content-Length 不符、caller 取消、非合作 request／reply、clear 與 session 替換、遲到進度／snapshot／share reply
- 已發布原檔後 copy 取消、取消保留回覆、NFC／NFD 同名不同 wire ID、替換下載後舊分享租約

來源審查另補齊 cold load 的錯誤正規化：`URLError.cancelled` 與 `CancellationError` 同樣保留為 `.cancelled`，避免可選紀錄層把注入取消改成普通 storage failure 後繼續 GET。相同三種取消均覆蓋 cold／warmed 路徑，且 caller task 沒有取消；既有普通儲存故障暫存 fallback 與已發布原檔後保留取消控制保留。production store 只做檔案 I/O，此處是注入契約不一致的修正，不能聲稱已發生實際網路取消事故。

`MediaDeliveryStoreTests` 另新增1個 dynamic receipt 顯示名稱與隱私控制。既有 CaptureMediaJourneyTests 的同名隔離、下載失敗／取消、舊 session 回覆及 offline preview 控制保持不變。既有 batch／foreground 流程與驗收 assertion 不刪減。

本批僅完成來源實作與測試來源；`git diff --check` 通過。此環境無可用 iOS／Xcode 建置，Swift 編譯、XCTest、Simulator、UI 分享呈現及實機／真 provider runtime 全部 **NOT_RUN**。沒有安裝、網路查詢、CI、commit 或 push；必須先完成來源審查，再按原開發分支整批正常 CI。任何舊 head 的通過不算本批證據。

## 首輪實際 CI 與操作提示等待候選

遠端 `3cfa1b59fd74e39f2b1ee2710bf7aa3d3f6be4e9`／CI `38161489829` 的必要軟體驗證已執行，整體 **FAILURE**。App 368/368（新增18方法全部）、Core、JVM/APK 與 secret gates 通過；UI 36/37。上段 NOT_RUN 是交付前狀態，不能用本輪 App 通過抵銷 UI 失敗。

唯一失敗是既有 `testCanonicalCaptureReviewRetriesReadsAndFailedPreviewOriginalWithoutReshooting` 繁中分支，點最新照片後等待第一個 Alert 10秒沒有取得成功，尚未進該語言的原檔下載步驟。英文同旅程已通過。保留 artifact `11710589198`，197815479 bytes，SHA256 `afc341aa3cdca398798c8abf4fec3c8a4434bddac0f6b86bf1dcfa03b4a4ee9e`。

伺服器紀錄確認 display GET 回503；18:08:13.822 的失敗 AX hierarchy 包含真實「相機操作失敗」Alert、該503內容、關閉按鈕以及預覽重試控制。這只能證明取證時已呈現，不能證明在原10秒期限內已呈現。原 trace 首次 exists 查詢後約9秒進入內建 debug capture，之後錯誤取證另跨約18秒；尚未確定延遲來自呈現、AX或平台負載，不能宣稱根因已修。

最小候選將本旅程兩處「等 Alert 存在，再立刻點按」統一為既有 `waitForInteraction` 等同一 Alert 內首按鈕可互動，仍10秒，仍必須有 Alert 且成功點按後繼續原重試／下載／取消／不重拍斷言。沒有產品、fixture故障次數、測試選取或 timeout 變更。失敗保存最多16筆既有 predicate 觀察與一張截圖，不額外 AX 查詢、不拿遲到畫面改成通過。同步 XCTest 操作與截圖沒有可取消的每呼叫硬期限，因此10秒不是整段 wall-clock 完成保證。此更強的可互動等待是待新 runtime 驗證的候選。

## Release Assessment

沿原 P2 本機恢復能力補齊既有單項入口，對使用者可見且向後相容；沿原草稿 PR 的 minor 評估，不自行升版或發布。實機與完整產品人工驗收依使用者指示最後集中；目前僅 implemented 候選，未達 PR ready／main accepted／preview released。
