# Android 最近下載紀錄與原檔完成邊界

日期：2026-10-05 UTC。範圍：`feat/android-download-history` 工作分支，Android 原檔下載；版本基準為 Development Preview 0.11.0。此頁記錄實作契約與同次凍結來源的本地驗證；不表示 PR ready、main accepted 或 preview released。

## 使用者流程

- 未連接相機時可從連線頁開啟「最近下載紀錄」；相簿的常駐篩選列也有入口，選取素材與下載忙碌時仍可開啟。
- 顯示此 App 最近 100 筆**實際開始**的原檔下載，包含單檔 SAF、SAF 資料夾批次與 MediaStore 相簿。每個開始嘗試的批次項目只有一筆，底層讀取重試不增加紀錄；picker 尚未返回或未開始的排隊項目不列入。
- 每筆僅有經清理的顯示檔名、目的地種類、開始／結束時間與結果。相機 ID、序號、媒體 ID、URI、資料夾路徑、帳密、token、原始例外文字不寫入紀錄。
- 「已完成」代表當次原檔已成功關閉輸出；MediaStore 另須成功解除 pending。這不是檔案仍存在、來源雜湊相同或跨平台傳輸驗證。
- 失敗或取消不承諾 provider 已清除部分檔案。重啟時未有終態的紀錄改顯示「結果未確認」，不推測完成或自動繼續。
- 關閉／Back 只關閉清單。清除需要獨立確認，只清除紀錄，不刪除原檔、不取消相機下載、不查詢或開啟目的檔，也不建立長期 SAF grant。
- 成功清除會更新 history epoch：清除前已受理的批次即使仍繼續下載，未開始項目與遲到完成回報也不會重新加入；新下載才可建立新紀錄。清除寫入失敗會保留既有清單與 epoch，並提供一般化錯誤。

## 完成邊界與資料責任

`DownloadHistoryProvider` 使用 application context 的絕對 `noBackupFilesDir/download-history`，每個程序／路徑只有一個 writer；不變更既有 manifest 的備份設定。儲存目錄若不可用，沒有工作目錄 fallback，也不阻止相機初始化或原檔下載。history 的 StateFlow 與依相機 session／item ID 作用的 `MediaSaveFeedback` 分離。

writer 先讀取快照再依序處理開始、完成、清除。每個實際開始的項目在首次目的地 I/O 前以 NonCancellable 取得 pending receipt 的責任，之後再次檢查取消。pending 已原子提交或有明確儲存警告後才開始下載；完成由程序 scope 處理，ViewModel／相機 session 關閉不會取消 writer。

快照最多 100 筆／256 KiB，以 temp write、flush、fd sync、atomic replace 提交；不支援原子替換時顯示警告，不以原地截斷 fallback。格式、版本、UTF-8、重複 ID、欄位與尾端汙損均做驗證；讀取失敗保留原始檔，僅經使用者清除可重設。不宣稱硬體／檔案系統掉電保證。

MediaStore 的成功 checkpoint 在 `resolver.update(IS_PENDING=0) == 1` 後立即、無 suspension 標記完成；SAF 在輸出 `use` 成功關閉後立即標記。先保護完成的原檔再通知 receipt／UI，避免從 IO dispatcher 返回時的 prompt cancellation 或完成觀察者失敗誤刪已發佈／已關閉的檔案。checkpoint 前的失敗仍執行既有清理；清理失敗附加至原始失敗。此修正只作用於原檔儲存，不變更共用相機 operation runner、預覽 Close 或其他命令。

history 讀寫錯誤只能造成明確紀錄警告，不可增加批次失敗、再試相機讀取、刪除已完成原檔，或把已儲存回饋改為失敗。第一個終態生效；已清除、淘汰或舊 request 的回報不可重新插入。

## 測試來源與驗證狀態

- `DownloadHistoryStoreTest`：真實檔案讀寫、原子提交各階段故障、恢復、100 筆容量、損壞／未來格式／尾端 NUL、清除 epoch 與程序 scope。
- `DownloadHistoryTransferTest`：production receipt／batch／retry／finalization helper 接 MockWebServer 與真實原檔；混合成功／失敗、單 receipt 跨 HTTP 重試、取消與未開始項目、pending 提交期間取消、完成後紀錄寫失敗、close 失敗、清除跨批次、損壞歷史與初始化失敗隔離。
- `MediaOutputFinalizationTest`：真實檔案與 coroutine 的完成後 prompt cancellation、未完成清理、觀察者故障、清理 suppressed failure、重複完成通知。
- `CameraMediaGalleryStoreInstrumentedTest`：真實 Android MediaStore，新增發佈當下取消後原檔保留、完成觀察者故障不影響儲存、驗證失敗不得回報完成；原有 pending、原始 bytes、日期、重名、取消／截斷與 CCAPI 串流案例保留。
- `DownloadHistoryDialogInstrumentedTest`：實際 Android Dialog window bounds、橫向與 320×320 dp 視窗、2×字級、完整觸控目標、100 個長檔名捲動、Back、清除確認與失敗保留。
- `CameraDownloadHistoryJourneyTest`：真實 ViewModel／HTTP／MediaStore 的離線重啟、terminal journal 故障、批次清除、損壞紀錄隔離與同 ID 重連保存。
- `CameraMediaGalleryPublishFailureTest`：受限 `ContentResolver.wrap(ContentProvider)` 真實檔案 provider 拒絕發布；不得通知完成，且只刪除本次建立的輸出。
- `CameraDownloadHistorySafJourneyTest`：真實 ViewModel 文件保存接受限本地 provider，驗證完成後紀錄寫失敗仍保留原檔與單一 HTTP 讀取，以及取消未放行 HTTP 前清除自有輸出、保留其他檔案並持久保存取消結果。這不是 Android 系統 picker 或第三方 SAF provider 的驗證。

2026-10-05 07:16 UTC 的整合檢查：focused App JVM 39/39 通過（store 22、transfer 8、finalization 6、gallery utilities 3）；`compileDebugAndroidTestKotlin` 成功，包含當時 5 個 history journey、10 個 Dialog UI 與新增 3 個 MediaStore regression 的來源。首次 run 為 38/39：新 fixture 誤將 HTTP 503 視為 transport IOException，既有 client 實際回報不可重試的 HTTP 錯誤；改為真實中斷 response body 後，驗證有界 IO 重試與每檔一筆紀錄。沒有放寬 production 重試規則。此為中間來源的 focused 證據，不代表後續新增 publication-failure fixture 已編譯，也不取代最終來源全 App JVM、Lint 與兩個 APK 建置。

07:24–07:29 UTC 首次全來源 aggregate 已以 terminal exit 0 完成：App JVM 725/725（64 suites，0 failure/error/skip）、Lint 0 error／55 warning／2 information、App 與 androidTest APK 成功；來源 manifest 前後一致。後續另補 2 個 SAF 文件旅程，以及將機密掃描攔截的人工 Windows 使用者目錄 fixture 改成一般人工私有目錄，保留同樣清理斷言，未修改掃描規則。最終來源驗證已涵蓋這兩個變更。

最終凍結來源檢查於 07:36:46–07:41:36 UTC 結束，terminal exit 0、4m49s，79 tasks（11 executed／68 up-to-date）。`testDebugUnitTest` 重新執行，725/725 通過、64 suites、0 failure/error/skip；Lint 0 error／55 warning／2 information；instrumentation Kotlin 編譯及兩個 APK 均成功。App production Kotlin／resources 與首次 aggregate 相同，App APK hash 亦相同；變更只有上述人工目錄 fixture 與新增 SAF 裝置測試。

- 最終來源 SHA-256 manifest：`8e006166aa3f49fb341eee029343ae29be5c3d4e835f1f613786c8ea6e7bc998`，建置前後完全一致。
- App APK SHA-256：`7714328f2434ffa317265739eba7f71580624916a00b4533385f22de6d03527b`。
- androidTest APK SHA-256：`e1c11ceafc55655fd8aef342686fc08a2ea7468c8f409a38f7106d26740e882b`。
- 本批新增裝置案例共 21 個（Gallery regression 3、Dialog 10、Gallery/離線旅程 5、發布失敗 provider 1、SAF 文件旅程 2）；均只完成編譯，沒有裝置執行結果。

本雲端未配置模擬器且沒有 `/dev/kvm`，沒有連接實體裝置；instrumentation 編譯不能宣稱已執行。API 34／36 裝置 CI 與實體手機、真實 SAF provider／相機仍待各自驗證；不跨來源加總，也不把合成 HTTP／MediaStore fixture 當實體相機證據。

## 發版評估與非目標

Release Assessment：相較 0.11.0，新增可離線查閱的使用者功能，建議 impact 為 `minor`；本分支不改版本、不合併、不發版。精確來源的本地 JVM／Lint／APK 已通過；API 34／36 CI、main acceptance 與 immutable candidate 仍是尚未達成的各自 gate。

不包含跨程序續傳、下載工作佇列、自動重試／匯入、目的檔查詢或開啟、重複下載去重、來源 checksum、iOS／PC 等效能力、實體相機驗證、預覽 Close 修正或既有 PR #202 的內容。
