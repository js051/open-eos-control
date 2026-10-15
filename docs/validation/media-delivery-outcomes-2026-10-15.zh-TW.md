# 三端交付／取消結果對齊候選（2026-10-15）

## Android 本分支增量

下載批次在第一個終態失敗後停止，包含不確定的清理結果。之前確認完成的輸出保持完成，未觸及的後續列保持 queued／NotStarted，分開顯示已確認、失敗、未開始數量。取消仍拋出原取消結果，admission 失敗仍在任何原檔／目的地 I/O 前停止；非下載的 metadata／mutation 批次保留原有繼續策略。既有同一次已准入操作內的有界傳輸重試不變。

不從檔名推斷相機或來源身分，不把歷史紀錄當權限，不自動重試。續接仍需目前 owner／epoch、重新映射來源、在需要時重新確認不確定輸出；clear 後的晚完成不能重建紀錄。清理失敗保留原錯誤與 cleanupUnconfirmed；已確認 close／Gallery publication 不因取消或歷史寫失敗而刪除／降級。

## 共用合成契約

`contracts/media-delivery/v1/outcome-vectors.json` 是 26 項有界觀察向量；最多 32 項、每項 12 個事件、100 列、32 KiB。不包含真實使用者影像、目的地 URL／handle、來源權限或自動續傳能力。

Android 的單元測試資源接線直接讀這份 fixture，檢查長度、版本、界限、唯一 ID 與保守不變量，再驅動正式 `MediaOutputFinalization` 的關鍵 checkpoint 測試。PC Blob 接手、writer close、Android SAF close／Gallery publication、iOS private preparation／Files provider callback 有意保留不同語義；不得都命名成外部已保存。close／publication 也不代表 fsync、雲端同步、未來存在或位元讀回驗證。

## 可核實本地驗證

- 固定官方 Kotlin 2.2.21、coroutines 1.9.0、JUnit 4.13.2：11 項通過。編譯真實 finalization 原始檔、未改寫的完整 batch／DTO 宣告抽取，以及本分支永久保存的 5 項契約測試與 6 項 batch policy 測試。
- 本地 harness 從同份 fixture 產生五個布林期待值。它不代跑 Gradle 的 org.json 資源載入器；資源接線與 loader 的原生執行仍 **NOT_RUN**。
- 新／更新的全量 planned-history JVM、磁碟歷史、Compose UI journey、SAF／MediaStore provider、Android SDK app 編譯／lint 均 **NOT_RUN**；本地沒有 Android SDK／Gradle。沒有用假 Android API 冒充驗證。
- 新的 UI journey 驗第一個完成、第二個失敗、第三個未開始；原檔請求不得觸及第三個，磁碟歷史保持完成／失敗／queued。
- 本文件不沿用上一個 exact-head CI 的綠燈替本增量驗收，也不宣稱實機測試已做。

## 尚待整批驗收

平台完整編譯與既有必要 CI；實際 provider 拒絕／撤權、取消後檔案存在與不確定清理、冷開與明確續接；支援 transport 的實機拍攝→監看→挑片→交付／取消恢復。分支或提交同步不等於功能與畫面行為完整對齊。

## Release Assessment

此候選修正可見下載停止／結果行為並增加回歸證據，沒有變更產品版本。整個跨平台交付觀察群含新的持久紀錄能力，建議 minor 評估；仍須以最後整合樹與當時 release baseline 核實。現有 draft PR 不因本文件變更為 ready；不包含 merge、發版或額外 CI 授權。
