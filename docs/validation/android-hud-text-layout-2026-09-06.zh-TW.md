# Android 拍攝資訊旋轉與大字體顯示

## 重現與範圍

以 `v0.9.1` main `ec509c30b44c9a7ed0e1c06bae6f5c32655d1cb7` 為基線。前一輪截圖仍可見旋轉後 Photo/Video 標籤、儲存卡狀態與長白平衡文字截斷；FPS、放大倍率文字也繼承了不適合固定控制區的行高。新增基線測試在英文／繁中、三種尺寸、三種字體比例、四方向的單一 ready 狀態中報出 324 個文字 overflow，測試失敗。

這次只修正 Android 拍攝主畫面的顯示。控制位置、點擊範圍、選項 callback、系統旋轉政策、曝光與 AF 命令不變。

## 實作

- 共用 `CameraHudText` 在繪製前，以實際容器約束及一致的字體、行高、換行與省略規則量測。移除逐幀縮字的回授，避免中間幀先裁切。到達控制的最小字級仍無法容納未知字串時，使用明確省略號，不硬裁文字。
- 量測和 `Text` 都使用同一個 `AnnotatedString`。檢查專案使用的 Compose foundation 1.7.6 sources 後，發現 plain String 的 `GetTextLayoutResult` 另建 paragraph，可能與繪製路徑不一致；不以移除 overflow 斷言掩蓋差異。
- Photo/Video 保留完整文字與選中標記；FPS、AF-ON、放大倍率、AF OFF 與 REC/Bulb 計時使用各自有界的字體與行高。放大倍率改成圖示與文字上下排列，仍是原本 48 dp 點擊區。
- 曝光格內部明確分配標籤與值的空間。ISO、Tv、Av、剩餘時間及張數不從中間斷行；白平衡允許兩行。長白平衡採英／繁中短標籤，完整名稱保留於無障礙描述和設定選擇器。
- 記憶卡可用但沒有容量／張數時，HUD 顯示 `Ready`／`就緒`，詳細資訊不縮寫。內部排版增加空間，但外部標頭、模式、曝光列和快門配置不變。

API 依據：[TextMeasurer](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/TextMeasurer)、[TextLayoutResult](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/TextLayoutResult)。框架對照使用 Google Maven 的 [foundation-android 1.7.6 sources](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.7.6/foundation-android-1.7.6-sources.jar)，未更新依賴或將框架原始碼納入 repo。

## 驗證

- 新增三項 Compose 測試：主畫面文字／固定區域矩陣、縮寫後完整資訊與操作仍可存取、全部已知白平衡標籤。
- 主畫面矩陣共 360 個組合：360×800、800×360、800×1280；英文／繁中；1、1.5、2 倍字體；0／90／180／270 度；ready、高曝光值與 AF OFF、錄影、Bulb、AF-ON 五種狀態。
- 使用真正 `TextLayoutResult` 檢查 overflow／ellipsis、與可見邊界比對防止父容器裁切，並要求數字 token 位於同一行。十個主要觸控區在同尺寸下跨方向、語言、字體和狀態保持相同 bounds，且至少 48 dp。
- 21 個白平衡 raw tokens × 兩種語言 × 四方向，共 168 個大字體組合，保留完整無障礙描述。完整名稱仍可在 WB picker 找到；卡片細節、關閉、Photo/Video 切換和 FPS 設定 callback 均有回歸。
- 首輪截圖人工檢查發現 `2:00:00` 被拆為 `2:00:0` 和 `0`，雖未 overflow 仍不接受。加入禁止數字跨行的斷言並修正後，360 組合測試通過，更新截圖確認剩餘時間完整。
- 最終程式碼的 `testDebugUnitTest` 通過 507 項，failure／error／skip 均為 0；`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest` 通過。
- HTC 完整回歸通過：`CameraScreensTest` 114 項、`CameraFocusSessionTest` 21 項，共 135 項，執行時間 535.353 秒。這是最後禁止數字斷行修改之後的完整執行，不累計先前 focused 或重跑結果。既有 AF 釋放、失敗恢復、模式與設定操作均保留。

## 限制與 Release Assessment

使用 HTC U24 Pro／API 34 的隔離 `.debug` package 執行離線 UI／MockWebServer 測試，沒有覆蓋正式 Preview。尺寸矩陣是 Compose configuration override，不代表另外使用了三台實體裝置。本輪沒有操作 Serein 或其 emulator，沒有向實體 R6 Mark III 發送 AF、快門或設定命令；使用者暫時無法比較的機身失焦問題仍 pending。

未知相機名稱／設定值和超出矩陣的字體比例不保證完全不省略，詳細資訊入口仍保留。這不是全 App 排版驗收、PC/iOS 同步、相機協定或系統自動旋轉政策的修改。

最新發布基線 `v0.9.1 Development Preview`；既有拍攝資訊可讀性修正的 impact 為 `patch`。本 PR 不改版號或建 tag，仍須 exact-head CI 和 main acceptance。通過後可納入獨立版本 PR 的下一個 Development Preview，不能稱為 stable 或實機失焦已修復。
