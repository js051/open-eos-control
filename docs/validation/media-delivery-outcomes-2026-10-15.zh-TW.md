# 三端交付結果：已封存來源驗證紀錄

本文件在共同 dev 整合時保留兩份候選的有效契約與歷史證據。以下測試數字只屬各自來源批次，不能視為整合 dev 的重新驗收。原文中的分支／未推送狀態為當時紀錄；整合狀態另以本次收據為準。

## PC／Android 來源紀錄

## 三端交付結果與中斷恢復：本地驗證範圍

## 這一批的實際修正

PC 單檔 Blob 路徑原先與直接 writer 共用「已下載」；現在只顯示已交給瀏覽器，請使用者檢查下載項目。直接 writer 僅在 close 回覆成功後顯示關閉確認。close 拒絕即使 abort 成功仍屬不明；abort 失敗也保留不明提示。靜默取消後才完成的 close 不覆寫新操作提示，也不刪除已完成輸出。

Android 原先 `executeMediaBatch` 只在取消或 durable admission 失敗時停下；普通失敗，甚至 `cleanupUnconfirmed` 都會繼續下一檔。`retryMediaRead` 只擋住同檔重試，無法阻止建立下一檔輸出。這會在目的地可能已有殘留或失效時繼續消耗來源／目的地 I/O。

本批只對 DOWNLOAD 改為第一個最終失敗／不明結果即停批，與 PC 和 iOS 相同。早先 confirmed 結果保留；未開始列沒有 receipt，畫面顯示未開始，不降成失敗或取消。原有安全清理後、同檔內的有限 transport retry 維持；metadata／mutation 批次策略不變。清理不明列仍需要原有 explicit continuation 的新來源映射與不確定性承認。

iOS `performMediaBatchDownload` 已在每列 catch 後 return：早先 app-private ready 檔保留，後列 notStarted。這只是準備原檔，不是外部 Files 交付。手動 Files picker 的新持久歷史由獨立變更處理；provider callback 仍是批次觀察，不能推論逐檔 bytes 或雲端同步。

## 為何使用這個一致政策

- 停批讓使用者先檢查目的地，減少同一權限、容量、裝置故障造成連續殘留。
- 保留精確的已確認／失敗／未開始區別，避免把未執行工作假稱失敗。
- 代價是某個損壞來源也會阻止其後有效檔案。使用者可檢查後明確映射續接，不能靠背景自動重試消除這個決策。
- 如未來要提供「只略過已證實清理完成的獨立失敗」模式，應是另行可見的選項與驗收群；不可默默放寬 cleanup 不明、admission 失敗或 owner 失效的停止規則。

## PC 單檔 bounded durable observations：本批已實作

沿用原 IndexedDB 單快照與 actual-attempt writer，新增 schema 4：

1. `browser-directory` 的舊 `completed` 語義不變。`browser-file` 僅以 `close-confirmed` 表示 writer.close 回覆成功；`browser-download` 僅以 `handoff-requested` 表示已向瀏覽器提出下載要求，且必須保留 `outcomeUnknown=true`。Blob 永遠不能成為 completed。
2. schema 1–3 原資料可唯讀且不改寫；下一次成功 mutation 才原子遷移。schema 3 的 origin lineage 驗證與 completed-descendant 防重複保持；舊客戶端遇 schema 4 拒絕讀寫，不覆蓋新資料。
3. `showSaveFilePicker` 在使用者事件內、第一次 await 之前同步呼叫。選回 handle 後才等待 history admission，之後才 createWritable 或原檔 GET。Blob 也在 GET 前 admission。handle 僅留在函式區域，沒有 URL／handle／session／原始錯誤進入歷史。Picker 取消不建立 observation；退役 picker 的晚回 handle 不開 writer。
4. 歷史使用既有 100 列／100 KiB 上限，和工作清單共用容量。只能淘汰最舊、非 roster、已終態的獨立紀錄；pending 或 worklist 不會被新單檔淘汰。ticket／admission 的容量、IDB 不可用、transaction 失敗或不明結果會停止，顯示尚未開 writer／GET 的明確提示。只有終態寫入屬 best-effort：不改真實 I/O 結果、不重試、不自動再開 picker、不刪成功輸出。
5. 冷啟 pending 投影為不明，沒有自動 source request。clear epoch 阻止晚結果復活，第一個 terminal 結果不可被後來取消或失敗覆蓋。close 晚於取消／session 退役仍記 close-confirmed，但不覆蓋新 UI 提示。createWritable 拒絕且未返回 writer、close 拒絕、abort 失敗均保留不明，提醒檢查目的地。
6. 單檔結果只顯示獨立 observation，沒有 selected-worklist 續接入口。歷史中的名稱是顯示標籤；同名不能建立身份或關聯。新保存必須從目前 UI 明確選檔及授權目的地。
7. `bytesTransferred` 只記錄來源已讀取 bytes，尤其 Blob 路徑不等於外部已儲存 bytes。關閉確認也不證明 fsync、目前存在、逐 byte 回讀或雲端同步。

測試涵蓋同步 picker 順序、admission 前零 writer／GET、取消與換 session、Blob／writer 各自 cold reopen、clear/late callback、first-terminal、quota/transaction failure、遷移／origin lineage、欄位白名單／能力拒存、共享容量與 UI 無續接。

## 驗證與未驗證

共享 `contracts/media-delivery/v1/outcome-vectors.json` 共 26 個 bounded 合成向量。它定義可觀察證據，不強制三端 enum 字面相同，也不是 persisted schema。

PC 以 production callbacks／真實 modules 配合合成 DOM、writer、IndexedDB 模型測試，包含實際消費向量的 capture-review 與 transfer-history 測試。最終本地結果：

- `npm run test:modules`：25 組 TAP 摘要共 936 tests 通過，另含同命令內非 TAP module checks；其中 capture-review 194、transfer-history 81、batch-owner/UI 58。
- 完整 Bridge `python -m pytest`：553 passed、2 warnings（既有 Starlette/httpx deprecation、OpenAPI duplicate operation ID），無失敗。
- `ruff check --no-cache . ../scripts/validation`、兩個變更 production JS 的 `node --check`、`git diff --check` 通過。
- 既有工具 `python -m build --wheel --no-isolation` 成功，無下載依賴。wheel 內 app.js、index.html、media-transfer-history.js 均逐 byte 等於已測來源；這只是本地打包，不是發布。
- 用 base `13873354e53924ce445572d71efcc5ba219a0f60` 的真實 schema 3 client 加同一合成 IDB adapter 驗證 open/list/clear/ticket/plan 5 種操作，全數拒絕 schema 4 且沒有改寫快照。
- 本批沒有執行真瀏覽器 File System Access、實際 IndexedDB 多分頁／冷啟、Downloads UI 或真機相機／provider 測試。合成 deferred promises 證明程式呼叫順序，不能替代瀏覽器平台 user-activation 實測。

Android 本地只用既有 Kotlin 2.2.21、coroutine-core 與 JUnit 工具。真實 finalization primitive、未改寫抽取的 production batch function/DTO 與永久 `MediaDownloadBatchPolicyTest` 可執行。完整既有 coroutines-test／org.json／Android SDK 測試組沒有在此本地環境執行；已補 source 測試及 Compose journey，但需另行原生驗證。

沒有新增 CI、push、merge、release、安裝或真機驗證。此文件不能當作 PR ready、main accepted 或物理相機相容性證據。


## Release Assessment

- 最新已發布基線：Development Preview [v0.12.0](https://github.com/js051/open-eos-control/releases/tag/v0.12.0)，`prerelease=true`，發布時間 2026-10-07 02:38:04 UTC；交付流程於 2026-10-15 03:54:34 UTC 重新核對官方 GitHub API。
- 建議影響：`minor`。此群增加使用者可見的持久單檔交付觀察；結果用字及停批安全修正包含其中。維持現有產品版本 `0.13.0`，本批不改版本、不建立 tag 或 release。
- 尚未完成的 gate：此批新提交的 exact-SHA CI、真瀏覽器與原生執行，以及實體相機／目的地驗證。先前 RTP 候選 CI 無法驗證本批較新的 source tree。沒有把本地測試通過提升成 PR ready、main accepted 或 released。
- 實機狀態：本群未新增物理相機證據；檔案 close／picker callback 也不代表雲端同步、fsync 或未來檔案存在。

## iOS 來源紀錄

## 三平台交付結果與取消恢復（2026-10-15 候選）

這是下一個功能／行為對齊群，不是原生 RTP LUT 的驗收結果。本群尚未推送或取得 exact-head `ci-complete`；既有三個草稿 PR 與已保存的 RTP 候選驗證保持獨立。沒有合併 main、發版或實機通過聲明。

## 要對齊的使用者結果

同一個受支援 transport 上，使用者能選原檔、明確交付、停止、查看留下的結果，再明確接續。各平台保留實際證據的差別：

- PC Blob 路徑只有「已交給瀏覽器下載」；沒有 writer.close 或外部檔案證據。
- PC File System Access 與 Android SAF 的成功是所用 writer 的完成檢查點；Android Gallery 還有其 publication 檢查點。不是雲端同步／日後檔案仍存在的保證。
- iOS 私有原檔的 prepared 是 App 副本準備完成；前景 Files writer copied 與手動 Files picker 的 providerReported 是不同事實。
- UIDocumentPicker 回報的 URL 數量及順序不提供可信的逐輸入檔案映射；即使數量完整，也不證明外部 bytes 或雲端同步。重複、缺少、過多回報與無回呼 teardown 仍是不明。
- 已確認的完成結果不能因較晚的取消變成未完成；無法記錄結果不能撤銷已完成的交付。清除歷史也不刪除外部目的地。

共同 fixture 位於 `contracts/media-delivery/v1/outcome-vectors.json`，有明確大小／事件／列數上限。它定義觀察與檢查點，不要求三端 enum 或 OS API 同名。測試來源與執行證據必須分開列示。

## iOS 新的手動 Files 觀察歷史

原本五個手動 Files 入口只在畫面記憶體保留結果。本候選全部經 `CameraAppState.beginMediaFilesExport`，在開啟 picker 前，先由原 `MediaDeliveryStore` 的同一受保護 writer 記錄 admission。

- Manifest schema 5 可讀原 schema 1–4。只在新增手動匯出時升級；原前景 Files 寫入不能把 schema 5 降回 4。舊資料不憑空產生匯出觀察。
- 每次記錄本機 UUID、來源顯示名稱、時間、整批狀態與有界回報數。最多合計 100 個來源名稱、沿用 256 KiB manifest 上限；不淘汰未解結果來騰出新工作。
- 不保存來源能力、相機識別、目的地 URL、provider 路徑、bookmark 或授權。名稱可重複，只供顯示，不是相機或目的地身分。
- 順序是保留 immutable source lease → 持久 admission → 再驗證來源 metadata → 呈現原本 asCopy picker。admission 失敗、清除世代改變、退到背景或取消等待時不呈現晚到的 picker。
- callback 第一個終態生效；寫入最終觀察與 UIKit 真 teardown 都結束後才重新開放該 controller。晚回呼不能覆寫新 attempt。
- 冷啟讀到 started 只投影為 unconfirmed，不自動重新匯出、不讀外部目的地、不推定未曾複製。
- 清除變更 epoch；排隊 admission、晚回呼都不能恢復已清除歷史。活躍 source lease 的生命週期規則不變。
- 最終寫入失敗時，當前畫面仍保留真正 provider 觀察，另顯示歷史不完整；下次冷啟只能保留不明，不捏造成成功或取消。
- 歷史只列整批觀察，沒有自動重播按鈕。再次選原檔、再次選目的地是新嘗試；先檢查 Files 避免重複。

## 本地驗證與尚待驗證

已執行：新增五項 Python source-wiring/privacy guards、現有 CI helper tests、physical-evidence verifier tests、版本一致性與變更檔 ruff。這些只證明來源接線／fixture 邊界，不會被稱為 Swift 編譯或 UIKit 執行。

新增原生測試來源涵蓋：持久 admission、同名不同來源、完整／部分／重複 provider 回報、取消／teardown、冷啟不明、清除晚回呼、容量、未知欄位拒絕、終態寫入故障、來源在 admission 中被替換、schema 5 與前景 Files 相容，以及共用 picker vectors 驅動真 controller/store。

本地沒有 Swift/Xcode/UIKit，這批新 Swift 測試為 NOT_RUN。真 Files／iCloud／第三方 provider、撤權、離線、滿碟、強制結束與外部檔案 bytes 仍待具名裝置驗收。較早 RTP 候選的 CI 已失敗終止，不能替本群較新的本地提交背書。

## 仍需保持的邊界

- 三個分支不是同一個整合 tree；共享 fixture／提交同步不等於三端功能全對齊。
- Android direct USB、iOS Bridge、PC libgphoto2 需各自 gate；沒有新增未廣告操作。iOS direct USB、真正 EDSDK provider、未知 vendor properties 仍是 Research。
- 原生 RTP 的 GPU／decoder／取樣驗收與實機色彩效能獨立追蹤；其一項待驗不能停止其他對齊工作。

Release Assessment：本群新增持久手動匯出觀察與修正交付結果行為，候選影響為 minor；本文件不變更版本、不授予 PR ready，不授權合併或發版。

## Apple API 來源

Apple 的 [documentPicker(_:didPickDocumentsAt:)](https://developer.apple.com/documentation/uikit/uidocumentpickerdelegate/documentpicker%28_%3Adidpickdocumentsat%3A%29) 說明 export 模式回傳目的地新副本 URL。本文採保守產品契約：API 文件未建立本 App 所需的逐輸入穩定映射、bytes 讀回或雲端同步證據，因此只保存整批 provider 觀察；這是本產品的證據分層，不是否認 OS 的回報。
