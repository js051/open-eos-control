# 2026-10-15 素材日期／拍後選片對齊

三個開發分支已各自繼續 AF、逐頁載入／取消、外部目的地／交付結果及 native RTP LUT 工作；不同分支的共享契約或綠燈，不等於三端實作已同步合併或實機驗收。

本次獨立修正群採同一個 `contracts/media-date-review/v1/date-review-vectors.json`（48 日期向量、3 展示時區、9 排序案例、4 拍後選片案例）：

- 日期區分明確 offset instant、浮動時間、純日期、unknown；不捏造裝置時區、午夜 instant 或日期精度。
- 日期篩選／排序／群組／詳細資訊使用同一 viewer-zone snapshot。時區變更保留使用者選取的 literal 日期、素材 ID 與傳輸 owner。
- 日期排序採固定精度分組及 loaded-order ties，unknown 在兩個方向皆最後，避免混合精度比較循環。
- 拍後照片／影片先排除已觀察 ID，再篩種類並保留相機順序。一般最近素材可含兩種。未增加 shutter／REC 重送或整卡 baseline。
- PC 實際 JavaScript、Android 純 Kotlin 各執行相同固定預期；iOS 已接相同 fixture XCTest，但其本地來源檢查不能替代 Swift／SDK／UI 執行。
- 正式 Android JSON resource loader、平台 UI／時區事件、實體相機日期格式與返回順序仍分別待驗；不由純邏輯結果推定完成。

本群未處理或消除既有 native RTP GPU／錄影切換驗收失敗，也不將單項待驗設成其他三端功能開發的前置 gate。平台 transport 差異、direct USB 研究項、真正 decoder／GPU／讀回及實機效能，繼續如實列出。
