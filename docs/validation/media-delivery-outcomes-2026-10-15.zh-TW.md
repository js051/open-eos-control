# 三平台交付結果與取消恢復（2026-10-15 候選）

這是下一個功能／行為對齊群，不是原生 RTP LUT 的驗收結果。本群尚未推送或取得 exact-head `ci-complete`；既有三個草稿 PR 與已保存的 RTP 候選驗證保持獨立。沒有合併 main、發版或實機通過聲明。

## 要對齊的使用者結果

同一個受支援 transport 上，使用者能選原檔、明確交付、停止、查看留下的結果，再明確接續。各平台保留實際證據的差別：

- PC Blob 路徑只有「已交給瀏覽器下載」；沒有 writer.close 或外部檔案證據。
- PC File System Access 與 Android SAF 的成功是所用 writer 的完成檢查點；Android Gallery 還有其 publication 檢查點。不是雲端同步／日後檔案仍存在的保證。
- iOS 私有原檔的 prepared 是 App 副本準備完成；前景 Files writer copied 與手動 Files picker 的 providerReported 是不同事實。
- UIDocumentPicker 回報的 URL 數量及順序不提供可信的逐輸入檔案映射；即使數量完整，也不證明外部 bytes 或雲端同步。重複、缺少、過多回報與無回呼 teardown 仍是不明。
- 已確認的完成結果不能因較晚的取消變成未完成；無法記錄結果不能撤銷已完成的交付。清除歷史也不刪除外部目的地。

共同 fixture 位於 `contracts/media-delivery/v1/outcome-vectors.json`，有明確大小／事件／列數上限。它定義觀察與檢查點，不要求三端 enum 或 OS API 同名。測試來源與執行證據必須分開列示。

## iOS 新的手動 Files 觀察歷史

原本五個手動 Files 入口只在畫面記憶體保留結果。本候選全部經 `CameraAppState.beginMediaFilesExport`，在開啟 picker 前，先由原 `MediaDeliveryStore` 的同一受保護 writer 記錄 admission。

- Manifest schema 5 可讀原 schema 1–4。只在新增手動匯出時升級；原前景 Files 寫入不能把 schema 5 降回 4。舊資料不憑空產生匯出觀察。
- 每次記錄本機 UUID、來源顯示名稱、時間、整批狀態與有界回報數。最多合計 100 個來源名稱、沿用 256 KiB manifest 上限；不淘汰未解結果來騰出新工作。
- 不保存來源能力、相機識別、目的地 URL、provider 路徑、bookmark 或授權。名稱可重複，只供顯示，不是相機或目的地身分。
- 順序是保留 immutable source lease → 持久 admission → 再驗證來源 metadata → 呈現原本 asCopy picker。admission 失敗、清除世代改變、退到背景或取消等待時不呈現晚到的 picker。
- callback 第一個終態生效；寫入最終觀察與 UIKit 真 teardown 都結束後才重新開放該 controller。晚回呼不能覆寫新 attempt。
- 冷啟讀到 started 只投影為 unconfirmed，不自動重新匯出、不讀外部目的地、不推定未曾複製。
- 清除變更 epoch；排隊 admission、晚回呼都不能恢復已清除歷史。活躍 source lease 的生命週期規則不變。
- 最終寫入失敗時，當前畫面仍保留真正 provider 觀察，另顯示歷史不完整；下次冷啟只能保留不明，不捏造成成功或取消。
- 歷史只列整批觀察，沒有自動重播按鈕。再次選原檔、再次選目的地是新嘗試；先檢查 Files 避免重複。

## 本地驗證與尚待驗證

已執行：新增五項 Python source-wiring/privacy guards、現有 CI helper tests、physical-evidence verifier tests、版本一致性與變更檔 ruff。這些只證明來源接線／fixture 邊界，不會被稱為 Swift 編譯或 UIKit 執行。

新增原生測試來源涵蓋：持久 admission、同名不同來源、完整／部分／重複 provider 回報、取消／teardown、冷啟不明、清除晚回呼、容量、未知欄位拒絕、終態寫入故障、來源在 admission 中被替換、schema 5 與前景 Files 相容，以及共用 picker vectors 驅動真 controller/store。

本地沒有 Swift/Xcode/UIKit，這批新 Swift 測試為 NOT_RUN。真 Files／iCloud／第三方 provider、撤權、離線、滿碟、強制結束與外部檔案 bytes 仍待具名裝置驗收。RTP 候選正在跑的 CI 不能替本群未提交變更背書。

## 仍需保持的邊界

- 三個分支不是同一個整合 tree；共享 fixture／提交同步不等於三端功能全對齊。
- Android direct USB、iOS Bridge、PC libgphoto2 需各自 gate；沒有新增未廣告操作。iOS direct USB、真正 EDSDK provider、未知 vendor properties 仍是 Research。
- 原生 RTP 的 GPU／decoder／取樣驗收與實機色彩效能獨立追蹤；其一項待驗不能停止其他對齊工作。

Release Assessment：本群新增持久手動匯出觀察與修正交付結果行為，候選影響為 minor；本文件不變更版本、不授予 PR ready，不授權合併或發版。

## Apple API 來源

Apple 的 [documentPicker(_:didPickDocumentsAt:)](https://developer.apple.com/documentation/uikit/uidocumentpickerdelegate/documentpicker%28_%3Adidpickdocumentsat%3A%29) 說明 export 模式回傳目的地新副本 URL。本文採保守產品契約：API 文件未建立本 App 所需的逐輸入穩定映射、bytes 讀回或雲端同步證據，因此只保存整批 provider 觀察；這是本產品的證據分層，不是否認 OS 的回報。
