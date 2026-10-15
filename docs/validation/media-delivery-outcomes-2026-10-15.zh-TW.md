# 三端交付結果與中斷恢復：本地驗證範圍

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
