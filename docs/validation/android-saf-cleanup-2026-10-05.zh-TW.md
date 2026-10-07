# Android SAF 原檔保存與取消清理

## 使用者流程與驗收邊界

此批修正「選擇儲存文件／資料夾 → 傳輸原檔 → 完成、取消或失敗 → 檢查下載紀錄」。相機原檔不會因清理動作而刪除；清理對象限本次已接受保存操作建立的 Android 輸出文件。過期或被拒絕的 picker 回覆維持不存取目的地。

驗收條件：

- 取消系統 picker 尚未取得目的地時，不讀取相機原檔、不新增下載紀錄。
- 單檔 picker 已建立文件後，取消即使發生在工作派送、等待既有媒體讀取或紀錄入列期間，回傳工作也須等待該文件清理完成。
- 傳輸失敗或取消後，使用 SAF 文件 API 清除新建的不完整文件，保留同一資料夾內無關文件。
- 文件提供者拒絕清理時，停止自動重試，顯示「可能留下不完整檔案」並保存在該次下載紀錄中。使用者可檢查目的地後明確重試；程式不會為隱藏清理失敗而截斷文件。
- 成功關閉輸出串流是 SAF 完成檢查點；之後取消或紀錄通知失敗不得刪除已完成原檔。

## 因果證據

Android `DocumentsProvider` 的一般 `delete()` 不支援文件刪除；文件刪除須經 `DocumentsContract.deleteDocument()`。原先單檔與資料夾保存的清理路徑使用一般 `ContentResolver.delete()`。MediaStore 的清理路徑另依其契約處理。

本地真 HTTP／真檔案故障測試先重現清理被拒後仍進行三次原檔讀取並建立三個輸出。改成 typed cleanup failure 後，同一情境只讀一次、只留一個待使用者處理的輸出。測試使用超過內容嗅探緩衝區的 64 KiB 回應，確保不完整檔案實際有資料，並逐 byte 檢查無關文件未改變。

另一個受控排程測試在清理已開始後取消實際 coroutine Job，再讓清理失敗。舊處理會讓返回已取消 dispatcher 的例外取代原傳輸例外，遺失下載紀錄的清理風險。修正以獨立清理結果保留風險，並附到實際離開工作邊界的例外；取消仍維持取消語意。

## 紀錄格式與隱私

紀錄新增 `cleanupUnconfirmed` 布林值，只能出現在失敗或取消的終態。保留原本第一個終態生效、清除後晚到結果不得復活的規則，不儲存目的地 URI、提供者位址或例外內容。

讀取支援 schema 1 與 schema 2；舊紀錄預設沒有「已知清理失敗」標記，後續寫入使用 schema 2。較舊 App 不能理解 schema 2，會走既有不可讀紀錄提示並保留原始資料，不宣稱可無損向下相容。未知版本或不合法欄位同樣不得覆寫損壞資料。

## 驗證狀態

- 已保存兩項修正前失敗證據：自動重試累積輸出，以及實際 Job 取消遺失清理風險。
- 已保存 64 KiB 真 HTTP 測試的單次讀取／單一不完整輸出通過證據。
- 完整所有權修正的 26 例定向 JVM 測試已通過（0 failure/error/skip、exit 0）。前一輪 24/26 的失敗另行保存：例外身份與 Job completion cause 不等同實際 escaping throwable，修正由同一輸出 owner 保存清理結果，並分別驗證例外、完成通知與紀錄重載。整合 #207 的已驗收 `601c801f56dd5530817204559d12ec3afafeaa03` 後，同樹完整 JVM 通過 808/808、70 suites、0 failure/error/skip；220 個 Android 來源檔案在 JVM、Lint 與兩 APK 間未變動。Lint 為 0 error、55 warning、2 information；App 與 test APK 均建置成功。API 34／36 裝置結果仍待完成，不能據此宣稱 PR ready。
- 六個真系統 DocumentsUI 測試已在 CI 執行。第一輪 [37346664856](https://github.com/js051/open-eos-control/actions/runs/37346664856) 的 API36 為 290/290、API34 為 289/290，0 error/skip；API34 的截斷傳輸案例在根目錄選擇步驟找不到可點擊祖先，未到傳輸斷言。該輪 API36 的六個完整旅程均通過，包括 provider UID、Binder 呼叫端 UID、不同 UID 邊界及原始 bytes／receipt 斷言。這是自動系統 picker 證據，不是實體裝置。
- 第二輪 [37352397647](https://github.com/js051/open-eos-control/actions/runs/37352397647)，head `a6d1140abfbfe775034ba77dbadb75646fe25d15`：API34 289/290，改為拒刪情境在根目錄 `ACTION_CLICK` 返回 false；前一輪的截斷情境已通過。API36 271/290，19 failure、0 error/skip；六個 SAF 案例的實際視窗文字皆為「Quickstep isn't responding」，另十三個 history 案例在前景焦點條件逾時。最後留下的焦點失敗截圖同樣顯示 Quickstep ANR，但因舊截圖檔名會覆寫，不能倒推最早三個失敗的唯一原因。此輪仍是失敗，不以歷史通過或系統異常改算成功。
- 測試入口改用官方 UI Automator 2.3.0 的單次實際觸控，保留目前 AndroidX Test 依賴世代；只在讀取階段重新取得過期節點，等待同一根目錄項目的可見位置穩定後再送一次操作。根目錄列須有可操作祖先，排除同名 toolbar 標題；所有互動另確認 DocumentsUI 仍是前景視窗，不點擊覆蓋它的 ANR。Save／Create／Allow 不重播，原逾時與六個 production 斷言不放寬。根目錄 fixture 補合法 framework icon，避免無效資源查找；沒有證據將該 warning 當作點擊失敗根因。失敗時另保存不互相覆寫的視窗階層與截圖。這筆測試修正仍須以新的精確 head CI 驗證。
- 測試提供者位於獨立 test APK，使用平台文件權限與實際 picker URI grant；merged manifest 與 APK 核實提供者只在 test APK，App 沒有 MANAGE_DOCUMENTS 權限。第一輪 nullable provider array 編譯錯誤改成明確 requireNotNull 後通過；不得用同 process resolver wrapper 或僅編譯結果取代跨 UID 實跑證據。
- 獨立原始碼檢查確認 UI Automator 2.3.0 的查找、可見位置、click／setText 各有預設 10 秒隱式 idle wait，會越過外層期限，並拉長前景確認與觸控之間的間隔。因此只在 driver 入口的 try/finally 範圍將該 idle timeout 設為 0，改由既有明確期限／前景／位置穩定與操作結果條件判定，離開必定還原；不更動 selector timeout 或其他測試設定。XML 與 PNG 各自嘗試保存，前者失敗不妨礙後者，仍重新拋出原失敗。恢復環境後第一輪編譯中斷未取得 terminal；修正相同已接受 SDK 條款的本地 hash 後，Kotlin／Java AndroidTest 完整編譯在 274 秒 exit 0。這是相容性檢查，包含最後兩項 review 修正的完整來源仍待最終編譯／Lint／APK 及 CI。
- 最後兩項 review 修正落地後，本地凍結來源的 Kotlin／Java AndroidTest tasks 已完成，下一次恢復執行回報它們為 up-to-date 並進入 `lintAnalyzeDebug`，但兩輪執行 session 均中斷且沒有 terminal result。保留來源 manifest 與中斷紀錄，不把 task 進度當成整體成功；本次最終本地 Lint／APK 驗證未確認，也沒有重新執行 808 JVM。停止重複本地重建後，將由既有 PR 必跑的 JVM／APK／API34／API36 CI 驗證新的精確 head；CI 沒有 Lint step，因此即使後續 CI 通過，也不代表本次本地 Lint 已完成。合併仍須正常 `ci-complete` 全綠。
- 不包含實體手機、第三方雲端文件提供者或真相機驗證。程序被系統終止後不保證自動清理或續傳。

## 官方依據

- [DocumentsContract.deleteDocument](https://developer.android.com/reference/android/provider/DocumentsContract#deleteDocument(android.content.ContentResolver,%20android.net.Uri))
- [AOSP DocumentsProvider](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/DocumentsProvider.java)
- [Android 共用儲存文件流程](https://developer.android.com/training/data-storage/shared/documents-files)
- [官方 UI Automator 版本與 2.3.0 發布紀錄](https://developer.android.com/jetpack/androidx/releases/test-uiautomator#2.3.0)
- [UiObject2 的實際觸控與可見位置契約](https://developer.android.com/reference/androidx/test/uiautomator/UiObject2)
- [kotlinx.coroutines 1.9.0 JobSupport](https://github.com/Kotlin/kotlinx.coroutines/blob/1.9.0/kotlinx-coroutines-core/common/src/JobSupport.kt)
- [kotlinx.coroutines 1.9.0 StackTraceRecovery](https://github.com/Kotlin/kotlinx.coroutines/blob/1.9.0/kotlinx-coroutines-core/jvm/src/internal/StackTraceRecovery.kt)
- [CoroutineStart.UNDISPATCHED](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-start/-u-n-d-i-s-p-a-t-c-h-e-d/)

## Release Assessment

- 最新版本基準：v0.11.0 Development Preview。
- 本批影響：patch，修復既有 SAF 保存的清理與取消行為；前置媒體挑片能力的版本影響由其批次另行評估。
- 尚未解除的發版阻礙：本批目前精確 head 裝置 CI 失敗，測試入口修正待驗；相依批次正在依序驗收，main acceptance／candidate 尚未完成。
- 實機狀態：待驗證；自動文件提供者與 HTTP fixture 不是相機或手機相容性證明。
