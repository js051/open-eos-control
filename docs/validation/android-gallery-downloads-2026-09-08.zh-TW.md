# Android 相簿與下載位置

## 範圍與依據

本輪僅推進 Android。相機未連線，不發送相機命令，不宣稱 R6 Mark III 大卡片讀取、Wi-Fi 吞吐、光學對焦或 PC/iOS 已驗收。

- 在 HTC API 34 僅查詢媒體索引的資料夾、來源套件與 MIME；確認 `jp.co.canon.ic.cameraconnect` 的 JPEG 與 MP4 均位於 `Pictures/Canon EOS R6 Mark III/`。未讀取或提交使用者照片、檔名、裝置識別碼或原始查詢輸出。此證據不代表所有 Camera Connect 版本都有相同路徑。
- [Android 共用媒體儲存](https://developer.android.com/training/data-storage/shared/media)：Android 10 以上可透過 MediaStore 新增 App 擁有的媒體，不需要讀取整份相簿的權限。
- [Android 14 MediaProvider](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/android14-release/src/com/android/providers/media/MediaProvider.java)：圖片／影片 collection 會拒絕系統 MIME 表不認識的格式；generic Files 的允許頂層目錄不同，不能假定可任意放進 Pictures。
- [MediaColumns.DATE_TAKEN](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#DATE_TAKEN) 是系統從原檔 metadata 擷取的唯讀欄位。保留原檔與 EXIF，不以下載時間或相機檔案修改時間偽造拍攝時間。

## 行為

1. Android 10 以上，系統認識的影像／影片預設直接儲存至 `Pictures/<相機型號>/`，單張與多選共用循序、可取消的原檔下載流程。無型號時用 `Pictures/Open EOS Control/`。
2. 每個檔案先以 `IS_PENDING=1` 建立，串流結束、實際寫入位元組與宣告長度一致後才公開。失敗／取消刪除該次建立的項目；重試建立新目的地，不覆蓋其他 App 或之前已下載的同名檔案。不聲稱相同長度等於來源 checksum 驗證。
3. 不支援的格式與 Android 8/9 保留 SAF。HTC API 34 不接受 CR3 圖片 MIME，因此 CR3 仍以原始 `.CR3` 透過系統目的地選擇器另存，不轉 JPEG、不偽造 MIME，也不宣稱 Google Photos 能顯示所有 RAW。混合多選包含未支援格式時整批使用同一個自選資料夾，不偷偷拆到不同位置。已支援的格式仍可從單張／多選的更多操作另存其他資料夾。
4. 下載期間暫停未完成的卡片遍歷與縮圖請求；離開格子的縮圖請求會取消，更新／傳輸結束後可重新載入。併發仍為 2、記憶體快取仍為 96 個，不新增背景全卡片縮圖預載。
5. 最近項目的日期資料每 8 筆漸進發布，仍標示載入中，最後才稱為完成。全卡片模式保留已快取的日期；不因顯示「最近 60」而限制整張卡片容量。未知日期不被假定為最近。
6. 大量同日／未知日期項目採線性分組；排序預先解析各項日期，不在每次比較時反覆解析。

## 驗證

- 新增 HTC API 34 測試：10 項通過。涵蓋待完成／公開狀態、含時區 EXIF 索引、原始位元組、重名、取消、截斷、暫時錯誤重試、圖片／影片／RAW 儲存能力閘門、直接下載 UI、縮圖請求生命週期及儲存位置文字。另有真實 CcapiClient 從本機 MockWebServer 下載到 Android MediaStore 的串流測試，只有 fixture HTTP GET，沒有相機連線。
- 測試只建立獨立、隨機命名的測試相簿；結束只刪除該測試建立的媒體。JPEG 為程式生成；MP4／CR2 的測試內容是合成位元組，只證明儲存不轉碼，不代表影片或 RAW 解碼驗收。
- MockWebServer 驗證前 8 筆在其餘 metadata 請求之前送出、取消即停止後續請求，以及全卡片切換不丟棄日期快取。既有跨照片／影片容器日期排序及超過 100 頁遍歷測試保留。
- 513 項本地 Android 單元測試、`lintDebug`（0 errors）及兩種 debug APK 建置通過。HTC 既有 `CameraScreensTest` 全部 114 項通過，連同新測試共 124 項；包含英文／繁中、大字體及旋轉畫面回歸。以 exact commit 的 CI 為合併門檻。

## 限制

不新增背景／跨程序可恢復下載佇列，不自動刪除相機原檔，不把儲存回報視為 Serein 匯入回執。程序強殺後未公開項目由 Android 的 pending 項目到期機制處理；不宣稱即時跨程序清理。相機韌體、各機型的檔案排序與原始 EXIF 完整性仍需真機矩陣。缺少時區或 metadata 的舊檔，其系統相簿日期由 Android 決定。

## Release Assessment

- Baseline: `v0.9.2` Development Preview。
- Impact: `minor`，新增直接存入系統相簿的完整使用者路徑，並改善相簿讀取負載。
- 獨立版本 PR 與 immutable candidate 通過前不宣稱已發布；不修改 Camera Import artifact 1.1.0／wire 1.0。
- 真機相機驗證待補，不以 HTC 合成檔案測試取代。
