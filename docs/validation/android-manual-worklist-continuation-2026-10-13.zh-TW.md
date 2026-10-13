# Android 手動繼續未完成工作清單：來源候選

日期：2026-10-13 UTC。基準：`cd1afe568448bc8252351054c6b05a381e865e47`。
本頁記錄來源實作與已撰寫測試，不代表編譯／裝置測試通過、PR ready 或已發版。[English](android-manual-worklist-continuation-2026-10-13.md)。

## 使用流程

使用者先連線、在相簿載入原檔，再從下載紀錄選擇「繼續未完成項目」。開啟紀錄、冷啟動或重新連線都不會自動請求／保存原檔。每份新草稿的對應皆為空白，使用者須逐列指定這次連線已載入的原檔；沒有指定的列不會啟動。已完成列及有已完成／仍活動之連結嘗試的列不列入。同一份草稿不能將同一來源指定給兩列。

先前結果未確認或可能留下檔案時，須另行勾選已檢查原目的地。這個判斷涵蓋整個本機連結家族；不能從更早的 queued 列或兄弟嘗試繞過後續的完成、活動或未知結果。之後確認對應並選擇 Gallery，或開啟既有 Android SAF 資料夾選擇器。資料夾流程支援一個或多個原檔，只有新工作清單與列已持久記錄後才建立文件，不恢復舊路徑、URI 或 grant。一般單檔 CreateDocument 流程維持原用途；繼續工作使用資料夾選擇器，避免 picker 在新清單落盤前就建立輸出。

新嘗試有新的工作清單與 receipt，紀錄顯示手動連結的原工作清單／列。舊列與舊結果保留。這些本機關聯用於避免意外重複嘗試，並非相機／來源身分、檔案雜湊或舊檔仍存在的證明；檔名、大小、日期不會自動配對。

## 寫入與所有權契約

- Store 在同一序列化 admission 中確認 request epoch、來源列仍存在、整個關聯家族資格、重複 origin、未知結果確認與容量，再原子替換 snapshot。繼續工作不會在失敗後退回無紀錄保存。
- 完整新清單及當前列皆須落盤，才允許 create/open／原檔 GET。失敗或無法確認的寫入、已失效 owner、清除紀錄皆阻止後续新 I/O。
- 維持所有舊紀錄與清單共用 100 列、256 KiB snapshot 上限，不驅逐舊證據或截斷選取。嚴格讀取 schema 1–4，新寫入為 schema 4。
- 關聯只保存本機隨機 worklist/row ID。草稿對應、media/camera/session ID、路徑、URI、grant、確認能力均不存入紀錄。
- 取消、清除、程序重建或換連線使草稿／picker 能力失效。過期 picker 不得變成一般保存，也不得覆寫較新保存 UI。清除立即退役傳輸能力，但保留原 owner 的清理結果回報，取消與清理結束後才結束 pending UI。批次 release 禁止再開始列；真正已開始的 receipt 仍只記錄原嘗試結果。

本批沒有新增 endpoint、metadata fan-out、背景佇列、自動重連／續傳、Range、相機 mutation、framework 或 dependency。

## 已撰寫的測試

`DownloadHistoryContinuationStoreTest` 涵蓋冷讀 50 列中 5 完成／1 未確認／44 queued、明確挑選子集、新舊結果隔離、schema／隱私／位元組邊界、各寫入故障、100 列拒絕、clear epoch、當前程序工作排除與 release，以及祖先／兄弟完成、未知與活動狀態。

`DownloadContinuationDraftTest` 涵蓋空白預設、同名不同原檔、拒絕重複來源、原列順序、重新對應後撤銷確認、queued 祖先的未知風險。`PlannedMediaDownloadBatchTest` 補上 strict admission 失敗、clear 與失效 owner 不得降級繼續 I/O。

真正 App/ViewModel 旅程測試涵蓋對應與未知確認後只保存兩個所選 Gallery 原檔、取消／重複、容量已滿、admission 故障、傳輸中 clear、舊 picker 晚回及同相機重用 ID 後重連。SAF 旅程使用真正 DocumentsProvider dispatch，並在 create 前讀取磁碟斷言新連結清單與 pending 列已落盤；另驗 clear 清除紀錄後，拒絕刪除的清理風險仍可見。本機包裝 provider 不等於 OS picker、跨 UID grant、第三方 provider 或實機驗收。

## 首次本地來源準備結果（歷史快照）

- PASS：`git diff --check`；兩份文字資源 XML 可解析、名稱唯一，新增翻譯 keys／placeholder 一致。
- NOT_RUN：Kotlin 編譯、定向／完整 JVM、Lint／APK、Android instrumentation／OS picker。現有 JDK 不足以建置，未找到本任務可用的既有 Gradle distribution、Android SDK 或 Kotlin 相依套件工具鏈；沒有安裝、下載或觸發新 CI。
- NOT_RUN：真相機、手機／第三方儲存提供者、斷電／程序強殺、各 transport 實體驗收。冷 snapshot 重建不冒充 OS 強殺證據。

PR ready 前仍須受影響的 JVM、Android runtime、一般聚合 gate 與 exact-head CI，保留失敗與平台界線。本次來源批次沒有 commit、push、main merge、版本變更或發布。

Release Assessment：可見的手動恢復能力在接受交付時屬 **minor**。程式版本未改，本次離線來源任務未重新查詢目前發布基準；編譯／runtime 與正常交付 gate 仍待完成，不宣稱實體相容性。


## 首次正常 CI 與取消責任修正（2026-10-13 UTC）

原來源 `b11bf4b1132919898351f7a0181d49c519527a6e` 已正常推送原 PR #240。[CI 38432632605](https://github.com/js051/open-eos-control/actions/runs/38432632605) 的 Kotlin production／unit test 編譯完成；JVM 1198 例中 1 例失敗：`stoppedStoreReleasesQueuedContinuationAndClearsProcessOwnership` 在等待取消後回覆時產生 `UncompletedCoroutinesError`。API 34／36 在記錄時仍執行，不能先報整體通過。

來源核對確認 Channel 的 prompt cancellation 邊界：已交付給暫停 receiver、尚未進入 actor body 的 command 可以在取消時離開 queue，既有 completion drain 因而無法完成其獨立 deferred。最小修正加入 `onUndeliveredElement`，與原 buffered command drain 共用純同步 STOPPED 拒絕；尚未執行的 admission 回覆 planAbsent，已開始／已保存操作不走此 callback。保留 stopped state、active ownership 清除和原持久化／clear 語意。

原受控負例現在於 scheduler drain 後先斷言 pending 已完成，再讀原 STOPPED 結果；失敗時取消自有 pending 防止測試本身再等完整一分鐘。未增加 timeout、刪除斷言或修改必要 CI。此修正目前僅來源審查／diff check，新 head runtime 尚待同群正常 CI；原失敗完整保留。
