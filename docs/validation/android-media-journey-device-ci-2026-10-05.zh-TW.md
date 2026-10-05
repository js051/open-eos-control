# Android 媒體旅程裝置 CI 與測試契約修正

日期：2026-10-05 UTC。本頁只記錄 PR #205 的日期／評分／原檔保存整合，不含其他控制分支或新的實機宣稱。

## 首輪精確來源結果

[CI 37292550056](https://github.com/js051/open-eos-control/actions/runs/37292550056) 對應 head `56e45ba709a2b97169b7418272819ba90cdd8175`、tree `45bf476e56a99625ae932848750fbfa638460528`。

- 機密掃描、JVM／Lint／debug APK job 通過。
- API 34：264 例，238 通過、26 failure、0 error／skip；job 27m30s。
- API 36：264 例，238 通過、26 failure、0 error／skip；job 30m38s。兩個 API 的失敗案例清單完全一致。
- `ci-complete` 失敗；未受影響的平台 job 依既有路徑分類跳過。沒有把本地 725 JVM 或 instrumentation 編譯當成這輪裝置通過。

## 按實際契約修正驗收

### 相簿與預覽的 13 例

相簿 tile 以可存取描述提供操作，沒有獨立檔名 Text；離線 fixture 未提供 MEDIA_PREVIEW 時，操作是 Select 而非 Preview。單一素材預覽不建立前後導覽按鈕。另有繁中測試沿用已變更的字串，與實際資源不符。

測試改為以 item key 捲動真實 LazyGrid，驗證能力相符的可存取操作與顯示；被篩掉的 ID 必須在完整 grid 的 IndexForKey 中不存在，不能把尚未組成的 offscreen item 當成已排除。1/1 預覽仍驗確切 item、位置與讀取歸屬，並驗前後導覽不出現。大字級操作仍驗完整 48dp、未裁切、視窗內、不重疊與實際點擊全選／清除／重選。Production 僅新增 grid 測試標籤。

### Dialog 的 11 例

原測試把語義 layout 尺寸當成實際 touch target。Material 按鈕的視覺尺寸與最小互動範圍不同；僅用 layout 高度與 Android View 的 density 換算會測錯對象。

此外，Dialog 由父 View 的 Android Context 建立另一個 Compose owner，會安裝自己的 density。外層僅覆寫 composition 的 FontScale，不能據此聲稱 Dialog 實際已使用 2 倍字級。新增共用容器以覆寫後的 Android Context 建立 ComposeView，讓主 Dialog 與巢狀確認 Dialog 取得真正的字級設定。

共用 helper 先斷言節點確實為 2 倍 fontScale，再分別驗完整 layout、按節點自身 density 計算的完整 48×48dp touch bounds、Android 視窗內全範圍與真實橫向。真實點擊與 exactly-once 回呼保留；History 的兩個按鈕另比較真正觸控區不重疊。這些是待裝置重跑的修正，不預先宣稱真正大字布局已全部通過。

### 非同步保存責任的 2 例

恢復 CreateDocument callback 後，durable receipt admission 可仍在排隊。驗收改為要求該確切 item 已進入 Queued 且 MEDIA operation 已受理，或確已開始／完成；之後仍等待完成、核對原檔 bytes、單一原檔 HTTP 讀取及正確相機歸屬。沒有把 AwaitingDestination 當成成功受理。

Disconnect 會立即清除 session UI，但原傳輸仍負責清理並提交 CANCELLED receipt。若 writer barrier 排在該終態提交之前，barrier 本身不代表取消已完成。測試改為在原有有界等待下取得確切 CANCELLED 終態，再驗舊／新 session 的獨立 receipt、完成原檔與原有狀態。沒有延長相機 timeout 或更動 production 取消語意。

## 後續驗證

本地同來源 aggregate 於 2026-10-05T10:28:32Z–2026-10-05T10:36:14Z 以 terminal exit 0 完成（7m41s，79 tasks，21 executed／58 up-to-date）：AndroidTest 編譯、重新執行的 App JVM 725/725（64 suites，0 failure/error/skip）、Lint 0 error／55 warning／2 information，以及兩個 APK 均成功。來源 manifest 前後一致，SHA-256 `b7f372e889744a5cc73b09c87e5eb0ba2ed45937ebb9f31aa3bc54545b42fd75`。

修正後的 API 34／36 裝置結果仍待重跑。首輪失敗證據保持原來源，不因測試契約更正而回寫為通過；新的裝置結果另記精確 head。

Release Assessment：本次測試契約修正本身為 `none`；PR #205 整體新增媒體旅程相對 v0.11.0 仍為 `minor` Development Preview 評估。沒有改版本、合併 main 或發布，也沒有新增實體相機／手機／外部 SAF provider 證據。
