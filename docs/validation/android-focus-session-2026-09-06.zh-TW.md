# Android 對焦與遠端取景調查

本次範圍是 Android 的對焦回饋及遠端取景生命週期，不是 Camera Connect 功能等價驗收，也不是機身手動失焦問題的結案。PC／iOS 本輪未同步修改。

## 實機觀察

2026-09-06 使用 HTC U24 Pro、Canon EOS R6 Mark III、直接 Wi-Fi CCAPI：

- 手機原本安裝的是 Development Preview 0.6.2；本次開啟 App 時停在連線頁，不能把手機已連相機 Wi-Fi 當成 App 已建立控制連線。
- 連接前後，GET 讀到的 AF operation 都是 `aifocus`，AF method 都是 `whole_area`，tracking 都是 `enable`。本輪未寫入這些設定。
- 0.6.2 連線並開啟取景後，`GET /shooting/liveview/flipdetail` 回傳 HTTP 200 與非空二進位資料。關閉「自動更新」後仍是 HTTP 200；回到手機桌面後也仍能取得資料。中斷 App 連線後回傳 HTTP 503，內容為 `Live view not started`。
- 本地修正版使用獨立 `.debug` application ID，沒有覆蓋原有 Preview。實機開啟取景後上述 GET 為 HTTP 200；關閉新的「遠端即時取景」後為 HTTP 503／`Live view not started`。
- 這證明兩個版本的遠端取景停止行為不同，不證明失焦的因果關係。操作者本次暫時無法比較機身半按／拍攝結果，光學合焦驗收仍待完成。
- 未透過工具拍照、錄影、執行 AF、改變機身 AF 設定或刪除媒體。影像、原始診斷及設備識別資訊未納入 repo。

以上為調查觀察，不取代既有 device-evidence verifier 所要求的完整診斷與 Operator-confirmed 拍攝證據。

## 協定依據

Canon Camera Control API Reference 1.3 的 4.8.8（AF）、4.11.10（AF Frame Position）、5.2（Live View incidental information）區分發送對焦指令、移動對焦框位置及真正合焦結果。文件來源沿用 [reference-projects.md](../reference-projects.md)，不重新散布文件。

- `POST /shooting/control/af` 接受 start／stop，不以 HTTP 成功表示光學合焦。
- `PUT /shooting/liveview/afframeposition` 移動 AF 框，不以空的成功回應表示已合焦。
- 本輪未盲目延長 AF 時間、寫死 R6 III AF 模式或修改已驗證的 Live View 參數 fallback。
- [Canon R6 III 手機連線說明](https://cam.start.canon/en/C022/manual/html/UG-07_Network_0030.html) 可作為產品功能比較依據，不能用來推定 Camera Connect 的私有協定實作。

## 實作與驗證

- 遠端取景開關經 ViewModel／Repository 到現有 backend stop/start；連線可在不啟動取景的情況下建立。
- 背景停止遠端取景；回到前景只在使用者仍要求取景時恢復。關閉狀態不被 Refresh、尺寸調整或 Debug 重啟命令偷偷打開。
- 切換序列化；忽略過期畫面與舊 native listener 回呼。JPEG stop 失敗會回報並保留清理責任，方便重試，不再吞掉錯誤。
- Bulb 曝光及進行中的拍攝／對焦命令不被取景切換打斷；結束後再處理最新取景需求。
- 四角細線框使用影像內縮邊界與短暫收合動畫，不遮蓋中央被攝主體。連續點按不共用舊計時器。
- 對焦、半按、焦距驅動及 AF 點命令成功只顯示中性的 ACCEPTED；沒有實際合焦證據時不亮綠色。失敗仍顯示錯誤，模擬器 `ok=false` 也不當成成功。
- 單元測試涵蓋可關閉取景的連線、停止／恢復、停止失敗與重試、框線幾何及連續點按計時。HTC instrumentation 使用本機 MockWebServer，涵蓋前背景、明確關閉、啟動中取消、停止失敗重試與 AF start／stop 回饋；這些是自動測試，不是實體相機 AF 驗收。
- 框線 pixel test 確認正常繪出青色、沒有錯誤的綠色成功訊號且不大面積遮擋。幾何涵蓋 360×800、800×360、1280×800 及極小 viewport。
- 本地最終檢查：477 項 Android 單元測試、`lintDebug`、`assembleDebug` 與 `assembleDebugAndroidTest` 通過；HTC 上 5 項協定 fixture 測試及 1 項框線 pixel test 通過。測試 APK 使用 `.debug` application ID，不是已發布的可升級 Preview。

## 仍需完成

1. 同一場景、鏡頭與設定比較：未連 App、連線且取景關閉、取景開啟、使用 App 對焦後的機身半按與成片結果。
2. 依 Canon incidental AF frame metadata 提供真正的合焦、未合焦及 Servo 進行中狀態，而非只確認指令送達。
3. 具取消／背景釋放語意的按住 AF／Servo 操作。現有 AF 指令仍是有限時間 start／stop，不宣稱等同機身持續 AF-ON。
4. 核對 AF 點選擇、主體追蹤與機身操控之間的實際關係後，再擴充拍攝操作；不以按鈕數量代表完成度。
