# 三平台功能對齊基線

本表把 Android、iOS、PC 的可執行能力、操作结果及 transport 限制分開比較。它不是發版驗收表，也不以測試數量代表功能完成率。來源為既有 `feature-status.md` 全部 89 列、`product-workflow-acceptance.zh-TW.md` 及三端候選程式；舊表的 Android-only 字句可能已被後續實作取代。

2026-10-13 核對基準：Android `23352065ff06947429e9fe3ebb3f800506097d85`、iOS `0023ad0994ad30c3717b235fbff0bda38bd26e5c`、PC `88fd46ce4f7d99edfe865b33e32f1c35f6f73f37`。這三份候選分別通過適用 CI；並不表示三端合併後的 tree 已執行完整驗證，亦不表示所有相機／OS 組合已驗證。

## 能力群與剩餘差異

| 能力群 | 三端目前有對應的範圍 | 仍需保留的差異 |
|---|---|---|
| 連線與能力發現 | direct CCAPI discovery、版本／同源檢查、能力證據、連線錯誤與空掃描引導 | Android direct USB、iOS Bridge、PC gphoto2 是不同 transport；iOS direct USB 仍 Research。 |
| 相機狀態 | 身分、電量、儲存、剩餘張數／時間、鏡頭、溫度 | 僅呈現 backend 有證據的欄位；缺欄位不補造值。 |
| 拍攝設定 | 曝光、WB、畫質、音訊、bracketing、movie quality、卡片／目錄、檔名設定 | 依相機廣告與有效 readback；不是所有 USB／gphoto2 設定都等同 CCAPI。 |
| 拍攝與 REC | Photo／Video、shutter AF 選擇、Bulb、短 AF／half-press、REC 確認遺失恢復、拍後唯讀查圖 | 回應成功不等於實際新檔或光學結果；停止責任與未知狀態必須保留。 |
| 持續 AF-ON | Android direct CCAPI 已有 held AF、停止重試、生命週期釋放與 30 秒 watchdog | 本批補 iOS direct／Bridge、PC Bridge 與 Android Bridge；不推定 USB／gphoto2 支援。 |
| 相機回報對焦資訊 | Android direct CCAPI 已有 bounded packet parser、選取框、狀態與過期處理 | 本批補 iOS direct／Bridge、PC 與 Android Bridge；命令位置標記仍與相機回報框分開。 |
| Live View | JPEG／multipart／RTP、AUTO 降級、事件刷新、停止責任；音訊預設靜音 | 實際 decoder、UDP、cleanup 管線不同，真機弱網與持續 FPS 仍需測量。 |
| 監看輔助 | decoded frames 的 LUT、histogram、waveform、zebra、false color、peaking，以及幾何 guides／safe areas／desqueeze | mobile native RTP 尚無同等像素分析／LUT；PC 可在 Bridge decode 後分析。PC local UVC 是獨立輸入，不是 Canon 相機操作。 |
| 挑片與預覽 | 日期／評分／資料夾／類型篩選、隱藏選取保留、縮圖、原檔詳情、靜態／影片預覽 | PC Bridge traversal 仍是同步請求，沒有 mobile direct CCAPI 同等的漸進清單／手動取消結果。 |
| 原檔與工作清單 | 單檔／批次、完整性、取消與清理、持久紀錄、使用者明確接續未完成項 | Android MediaStore／SAF、PC 支援瀏覽器的明選資料夾、iOS App 私有副本／分享，不能共用模糊的「已保存」結果。 |
| 前景自動交付 | 明確啟用、有限清單、fresh 原檔大小、狀態持久化與停止 | iOS 準備私有副本不等於外部 Files／Photos 保存成功；三端都未承諾背景常駐或任意跨程序 Range 續傳。 |
| 素材變更 | direct CCAPI 保護／評分／archive／rotation／刪除 | Android USB 的 protection／rating 不等於 gphoto2 也有；座標 AF／Click WB 亦須各 transport 的公開證據。 |
| 平台操作 | 英文／繁中、offline preview、觸控／large text、窄畫面／方向變化 | 原生 UI、目的地授權、分享與 browser API 支援不同；須驗操作與結果，不能只比按鈕名稱。 |

## 本批 AF 契約

持續 AF 與既有短 AF 是不同操作。只有明確能力旗標才呈現 held AF；每次 hold 使用獨立 owner，停止只針對原 owner。Start 回應遺失也保留停止責任。背景、控制離場、取消、斷線與 30 秒 watchdog 可要求釋放；停止失敗需呈現不確定狀態，不能自動重新按下或無限重試。

Bridge 能力採新增可選旗標，舊服務缺欄位時保持不支援。既有 bounded autofocus endpoint 不改成持續按住。API、owner 驗證與錯誤行為見 [Desktop Bridge protocol](desktop-bridge-protocol.md)。

AF telemetry 僅來自相機的有限回應：最多 256 KiB、512 個框，僅 1× 且選取中的有效框。狀態未知保持未知；座標需有限、裁切於影像範圍並符合影像比例。可顯示的回覆接收後一秒內過期；若客戶端有更嚴格的請求年齡界線，仍保留該界線。這不是相機曝光時間的證明。連線／Live View 世代改變即退休，不能覆蓋本機 UVC、舊畫面或另一個連線。AF 命令被接受不構成 FOCUSED 證據。

## 驗證與後續

本文件記錄功能範圍及判準。新 AF 實作的編譯、受控測試、CI 與真機結果應以本批驗證紀錄的 exact head 為準；source review 不能替代 runtime。沒有真機 AF／鏡頭回報、held AF 放開或 Bridge 主機消失的實測，就不能聲稱物理相機已停止或已合焦。

其餘對齊工作應以完整流程收斂：mobile native RTP 的像素監看、iOS 明確外部目的地與完成語義、PC 素材清單的漸進／取消，以及各 transport 的可證能力。Research 與平台專屬項保留明確限制，不以虛構 backend 支援或隱藏失敗追求表面一致。


## 2026-10-15 素材日期／拍後選片對齊增補

上方 2026-10-13 表格是歷史基線，不能用它推定較新候選的完成度。三個開發分支已各自繼續 AF、逐頁載入／取消、外部目的地／交付結果及 native RTP LUT 工作；不同分支的共享契約或綠燈，不等於三端實作已同步合併或實機驗收。

本次獨立修正群採同一個 `contracts/media-date-review/v1/date-review-vectors.json`（48 日期向量、3 展示時區、9 排序案例、4 拍後選片案例）：

- 日期區分明確 offset instant、浮動時間、純日期、unknown；不捏造裝置時區、午夜 instant 或日期精度。
- 日期篩選／排序／群組／詳細資訊使用同一 viewer-zone snapshot。時區變更保留使用者選取的 literal 日期、素材 ID 與傳輸 owner。
- 日期排序採固定精度分組及 loaded-order ties，unknown 在兩個方向皆最後，避免混合精度比較循環。
- 拍後照片／影片先排除已觀察 ID，再篩種類並保留相機順序。一般最近素材可含兩種。未增加 shutter／REC 重送或整卡 baseline。
- PC 實際 JavaScript、Android 純 Kotlin 各執行相同固定預期；iOS 已接相同 fixture XCTest，但其本地來源檢查不能替代 Swift／SDK／UI 執行。
- 正式 Android JSON resource loader、平台 UI／時區事件、實體相機日期格式與返回順序仍分別待驗；不由純邏輯結果推定完成。

本群未處理或消除既有 native RTP GPU／錄影切換驗收失敗，也不將單項待驗設成其他三端功能開發的前置 gate。平台 transport 差異、direct USB 研究項、真正 decoder／GPU／讀回及實機效能，繼續如實列出。

### 2026-10-15 拍後唯讀查找證據增補（本地 implemented）

三端共同契約加入 found／no-candidate-observed／unread 與本輪成功／失敗讀取計數、最後完成讀取。混合結果不再遺失最後失敗，取消／owner 替換不得發布半輪證據，HTTP 成功但無合法清單不得偽裝成空卡。PC 已執行 production-function 及完整本地回歸；native／瀏覽器 exact-head 仍待補驗。共享向量或共同提交不等於原生／實機功能全對齊。詳見 docs/validation/pc-capture-review-evidence-20261015.md。
