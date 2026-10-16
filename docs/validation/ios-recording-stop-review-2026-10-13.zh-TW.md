# iOS 停止錄影後的影片檢視

## 範圍與來源

- 基準：`js051/open-eos-control` PR #236，固定 commit `260b14e7d76876b1ab152c69d2814bea118c4f84`。
- 此批補齊 iOS「停止錄影 → 最近影片檢視」；沿用既有最近媒體、影片播放器及原檔下載流程。
- 不含 main 合併、發版、工作流程或權限變更；本地檔案與 patch 尚未推送，也未觸發 CI。
- 不改前景匯入、工作清單重綁、相機協定或 Android。既有 still capture 的 AF、已接受但狀態讀回失敗處理及獨立錯誤所有權保留。

## 行為

1. STOP 送出前只保存已載入／曾看過的媒體 ID，並使舊的最近清單與縮圖回應失效；不在停止錄影前掃描整張卡片。
2. 只有原連線、該次 STOP 正常返回且 `CameraStatus.recording == false` 才開始影片檢視。拒絕、回應遺失、`nil` 與仍在錄影都不啟動檢視。
3. 檢視與 still capture 共用既有 owner、最多四次讀取與退避，每次至多最近 8 個項目。影片模式先排除已知 ID 與所有照片，再沿用時間／相機順序選擇候選項。
4. 清單延遲、清單讀取失敗分別呈現「尚未找到」及「無法讀取」。重新查找只讀清單／縮圖，不重送 REC、STOP、快門或 AF。
5. 已知舊媒體仍可開啟，並保留先前媒體標示。事件不能搶走尚未完成或尚未開啟的檢視候選項。
6. 新 still capture、斷線或新連線會使先前檢視回應失效。媒體忙碌時不發出新的查找讀取，也不清除其他操作的錯誤。
7. 英文／繁中錄影文案獨立於 still capture，明說「新出現」且最近 8 個項目中的候選影片仍可能是舊檔；不宣稱候選檔一定來自剛結束的錄影。最近媒體按鈕的 VoiceOver 標籤也依項目型別區分照片／影片。
8. 點開候選影片走現有影片 playback ticket 路徑；原檔下載仍是獨立操作，不把縮圖或照片預覽當成影片原檔。

## 新增測試

`CaptureMediaJourneyTests` 增加 12 個案例，涵蓋：

- 延遲新 MP4、日期較新的 JPG、已知舊影片；STOP 回應前零清單讀取及每次 `limit=8`。
- 開始錄影不觸發停止後檢視；拒絕／遺失／未知／仍錄影 STOP；無 media browser 的正常 STOP。
- 已載入媒體庫中的舊影片排除、四次讀取上限、唯讀重試與 REC／快門請求次數。
- MEDIA 忙碌、排隊重試被下載打斷、讀取失敗保持，以及其他錯誤所有權。
- 已取消清單／縮圖仍晚到；退休連線 STOP 成功或失敗；still／video owner 雙向交接；內容事件。
- 現有影片 playback 與獨立原檔下載，使用 repo 既有有效 H.264 MP4 fixture。
- 英文／繁中錄影鍵唯一性及 still capture 文案對應保留。

`MediaLibraryTests` 增加 2 個 selector 案例，覆蓋已知影片／所有照片排除、影片副檔名、未知日期及相機順序。

`OpenEOSControlUITests.testCanonicalRecordingStopReviewsNewlyVisibleVideoAndPreparesOriginalInBothLanguages` 增加英文／繁中各一次正常 HTTP preset／Canon-shaped CCAPI 旅程：既有舊影片 → 切錄影模式 → 開始／停止錄影 → 尚未找到新影片 → 新影片出現 → 唯讀查找 → 開啟 viewer → 原檔可分享。檢查 REC start/stop 各一次、零快門、候選影片 VoiceOver 及原檔分享入口，保留三個階段的 screenshot；不宣稱外部 Files／Photos 儲存或影片解碼已驗。

既有 simulator 的兩個 REC/STOP 路由只更改狀態／計數，不產生影片。UI harness 因此沿用既有 `/ccapi/media` 上傳端點，在 STOP 前／後注入 repo 原有有效 H.264 fixture，Canon contents 路由提供同一組 MP4 bytes；沒有改 simulator，也不把注入檔當成該次錄影產物。UI target 僅新增既有 fixture 的 resource 引用，不複製或製造新影片。

來源審查修正了兩個 App test 的拍攝模式前提：STOP 與重新連線不會自動切回拍照，故後續 still capture 先經 `setCaptureMode(.photo)` 並確認模式，再驗舊 owner 晚到回應。

## 驗證狀態

- 已執行：固定來源 Git blob SHA 與 SHA-256 校驗；原始來源保存；diff 空白檢查；patch 可套用檢查；雙語各 10 個新鍵、無重複且既有翻譯逐行保留。
- 已執行既有、未修改 simulator 的 ASGI fixture 探查：Canon REC start/stop 各一次且不新增檔；有效 MP4 上傳後可由 Canon contents 取得完全相同的 1,813 bytes（SHA-256 `2a8036931d1d15c7c9d1e7d4412e6796dc0faa8517871cdc64c2800f8a776627`），快門計數仍為零。`ffprobe` 確認 H.264、160×90、2 秒。這僅驗 fixture 路徑，不是 iOS UI 執行結果。
- **NOT_RUN**：Swift 編譯、XCTest、iOS App／UI、Swift Core aggregate。此 Linux 環境沒有 `swift`、`swiftc`、`xcodebuild` 或 `xcodegen`，而這是按需恢復的部分 source tree。未安裝工具，也未以來源檢查冒充 red／green 或通過測試。
- **NOT_RUN**：iPhone／實體相機驗收。需在支援的完整 Xcode checkout 執行 App tests，並在後續整批裝置驗收確認停止錄影、檔案延遲出現、預覽／播放及原檔下載。
- 狀態：本地 implemented，尚未驗證到 PR ready／main accepted／preview released。

## 後續驗證

在完整 checkout 以 repo 的 XcodeGen／Xcode 流程執行 `CaptureMediaJourneyTests`、`MediaLibraryTests`、`ControlResponseOwnershipTests` 及新增的雙語錄影 UI 旅程，再跑既有必要 App／UI aggregate。既有 CI 已對完整 App／UI scheme 執行必要 simulator 測試，不需修改 workflow。執行結果須綁定實際提交 SHA；未取得結果前不得將本文件的 source-only 檢查當成執行通過。
