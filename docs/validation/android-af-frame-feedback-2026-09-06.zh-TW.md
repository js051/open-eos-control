# Android 相機 AF 框與狀態

## 範圍與依據

延續 [遠端取景與對焦調查](android-focus-session-2026-09-06.zh-TW.md)，本輪只處理 Android CCAPI 相機回報的 AF 框與狀態，不改寫機身 AF operation、AF method 或 tracking 設定。PC、iOS、USB/PTP 及 Bridge 的 AF metadata 不在本輪範圍。

協定依據為 Canon Camera Control API Reference 1.3 §4.11.3、§5.2，以及 [Canon Android sample 的 LiveViewThread](https://github.com/Chieri-JN/Intervelo/blob/bb826a1211c49a6d3a569ec8c4b9934b585db270/CCAPI_Sample_Android_1.3.0e/Sample/app/src/main/java/com/canon/ccapisample/LiveViewThread.java)。該 sample 的鏡像不是 Canon 官方下載站；引用的是其中 Canon sample 的資料解析行為，並與參考文件核對。未複製或重新散布 SDK 原始碼或 PDF。

- 僅在相機公告 `GET /shooting/liveview/flipdetail` 且遠端取景已啟動時讀取 `?kind=info`；不使用機型猜測或盲目探測。
- 驗證 Canon binary information packet 的型別、長度、結尾及 256 KiB 上限。最多解析 512 個 AF 框，JSON 座標、狀態與選擇值必須是精確整數。
- `status` 低四位是 AF 狀態，高四位是一般／人臉／追蹤框種類；未知值不推定為合焦。
- 以 `liveviewdata.image` 的 position 與 crop size 將感光元件座標轉為影像內正規化座標，只顯示 `select=1` 的框。裁切到影像邊界，忽略無效矩形。

## 顯示與生命週期

四角細線框保持主體中央透明。綠色只代表相機回報 focus acquired；未合焦為紅色、Servo 進行中為青色、待命及未知狀態為中性色。HTTP 成功仍只是指令接受，不改成光學合焦成功。

metadata 使用獨立只讀 request，與 JPEG／multipart／RTP 的影像路徑分離。成功讀取後間隔 250 ms，無資料時 2 秒、讀取錯誤時 1 秒；每次最多等 1 秒。不改變影像要求 FPS。持續取樣對實機負載的影響仍需量測。

- 關閉取景、背景、媒體頁、拍攝中、Bulb 或取景切換中暫停讀取；斷線取消工作。
- 每次狀態切換及對焦操作使舊取樣失效，延遲回應不得跨 session 或切換重新顯示。已在途 request 最多受 1 秒 timeout 約束。
- 未收到新取樣時，框線在 1 秒內消失；重新進入畫面也不得復活舊取樣。
- 資料錯誤清除框線並在 Debug 顯示讀取狀態，不設置全域相機錯誤、不關閉影像串流。
- 本輪只在 1x 且 metadata／顯示影像比例相符時疊框。放大、anamorphic desqueeze 或比例不符時隱藏；不以猜測座標冒充正確位置。
- 診斷只有支援狀態、框數、狀態集合、取樣時間及讀取錯誤布林值，不含原始 metadata、影像或身份資訊。

## 驗證與限制

單元測試涵蓋 binary envelope、型別及大小邊界、高低位狀態、裁切座標、未知值、未公告端點與停用取景。Android instrumentation 使用本機 MockWebServer 驗證資料穿過 client、repository、ViewModel 到狀態，並測試失敗復原、背景／媒體／關閉停止及延遲回應。框線 rendering 測試涵蓋直向、橫向、平板尺寸、無障礙狀態及過期隱藏。

本地最終檢查：487 項 Android 單元測試、`lintDebug`、`assembleDebug` 與 `assembleDebugAndroidTest` 通過。HTC U24 Pro／API 34 的 15 項 instrumentation 通過，其中包含 9 項 lifecycle/metadata fixture、3 項框線 rendering/expiry，以及主畫面顯示條件、指令接受框線、Bulb 釋放回歸各 1 項。360×800、800×360、800×1280 畫面已檢查截圖及像素邊界。使用獨立 `.debug` APK，未覆蓋原有 Preview；本地 APK 不是已發布版本。

這些協定 fixture 與 UI 測試不是實體相機合焦驗收。操作者目前無法比較機身半按結果，因此「連 App 後容易失焦」原因仍未確認。仍需 R6 Mark III 實機核對框位置、各 AF 模式狀態、metadata 讀取負載，以及同一場景的實際成片。相機回報 focus acquired 也不等於已驗證成片銳利度。

本輪不加入按住 AF-ON／Servo、改變拍攝設定或宣稱 Camera Connect 功能等價；版本與發版需另外經過 Release Assessment 與 immutable candidate 流程。
