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
- 六個真系統 DocumentsUI 測試已實作，Kotlin 與 Java 編譯通過，尚待執行。第一輪測試對 nullable provider array 的編譯錯誤已保留，改成明確 requireNotNull 後通過。測試提供者位於獨立 test APK，使用平台文件權限與實際 picker URI grant；merged manifest 與 APK 核實提供者只在 test APK，App 沒有 MANAGE_DOCUMENTS 權限；實跑成功時另須驗證 provider UID、呼叫端 UID 和不同 UID 邊界。不得用同 process resolver wrapper 結果取代此證據。
- 不包含實體手機、第三方雲端文件提供者或真相機驗證。程序被系統終止後不保證自動清理或續傳。

## 官方依據

- [DocumentsContract.deleteDocument](https://developer.android.com/reference/android/provider/DocumentsContract#deleteDocument(android.content.ContentResolver,%20android.net.Uri))
- [AOSP DocumentsProvider](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/DocumentsProvider.java)
- [Android 共用儲存文件流程](https://developer.android.com/training/data-storage/shared/documents-files)
- [kotlinx.coroutines 1.9.0 JobSupport](https://github.com/Kotlin/kotlinx.coroutines/blob/1.9.0/kotlinx-coroutines-core/common/src/JobSupport.kt)
- [kotlinx.coroutines 1.9.0 StackTraceRecovery](https://github.com/Kotlin/kotlinx.coroutines/blob/1.9.0/kotlinx-coroutines-core/jvm/src/internal/StackTraceRecovery.kt)
- [CoroutineStart.UNDISPATCHED](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-start/-u-n-d-i-s-p-a-t-c-h-e-d/)

## Release Assessment

- 最新版本基準：v0.11.0 Development Preview。
- 本批影響：patch，修復既有 SAF 保存的清理與取消行為；前置媒體挑片能力的版本影響由其批次另行評估。
- 尚未解除的發版阻礙：本批精確 head 裝置 CI 未完成，相依批次未合併，main acceptance／candidate 尚未執行。
- 實機狀態：待驗證；自動文件提供者與 HTTP fixture 不是相機或手機相容性證明。
