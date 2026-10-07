# 已儲存 JPEG → Serein 離線交接驗收

這一批把「原檔已確實保存至手機」接到「相機斷線後仍能交給 Serein」。範圍是 Android 10+ 本次 App 執行期間，經 Open EOS Control 手動或前景自動儲存至 Gallery 的原始 JPEG；不是掃描手機相簿或重建歷史檔案索引。

## 使用旅程

1. 以既有手動保存或明確啟用的前景 JPEG 自動儲存，把 JPEG 原檔保存至 Gallery。
2. 從連線頁或相簿開啟「本次已儲存的 JPEG」。清單保留最近 100 張，顯示檔名、機型、日期與實際大小。
3. 自動儲存仍在執行時，先按 Stop，等待保存與清理責任結束，再選取並傳送。停止不會隱式傳送；重新啟用自動儲存與傳送也是不同操作。
4. 相機可以保持斷線。從手機原始檔建立這次交接的暫存副本，核對已記錄的完整長度與 SHA-256，然後交給已安裝且相容的 Serein。
5. 返回後分別呈現已匯入、重複、失敗、取消結果。已驗證的回執與暫存／讀取授權清理是兩種結果；清理未確認時仍保留前者，須明確重試清理後才可再送。

Close／Back 只關閉清單，不取消已開始的保存或交接。清除清單須確認，只移除記憶中的參照與選取，不刪 Gallery 原檔、下載紀錄或已開始的工作。較舊項目被擠出最近 100 張範圍時，也不會刪除原檔。

## 來源與生命週期規則

- JPEG 原本寫入 MediaStore 的同一資料串流計算 SHA-256。完成寫入、關閉與長度驗證，並把 pending row 公開後，同步記入清單；不在可被取消的 IO 返回後補登記。
- 已公開原檔不因清單 observer 或歷史紀錄失敗而刪除。重複的同一 publication 通知不重複加入，也不會在清除／eviction 後復活。
- 這是有界、記憶體內的 ViewModel 清單。旋轉與連線替換可以保留；真正結束 Activity 或程序重新啟動不保證保留。既有下載歷史 schema 1／2 的 URI-free 規則不變。
- 只讀取清單已持有的精確 Gallery row，不列舉相簿。仍為 pending、已刪除、長度不符或同長度被替換的原檔都不能交接；不回相機下載、不重試原檔、不重播控制命令。
- 選取提交時固定整批快照，最多 100 張；之後清除清單或有新照片進來不能替換已提交來源。準備前與逐檔均保留 64 MiB 可用空間；manifest 有 16 MiB 上限。
- 相機素材交接與本地 JPEG 交接共用同一個 lease、一次 launch admission 和 ActivityResult launcher。local staging／receipt IO 不屬於相機連線 jobs；Disconnect 或另一相機的狀態不會抹除它。
- 取消須等原 writer 結束，再清理這一 reservation。即時 dispatcher 下也必須返回可 join 的同一 cleanup job；清理失敗不自動再試、不提早開放第二個交接。
- 只撤銷本次 FileProvider session 及其子路徑的暫時權限；不撤銷整個 provider 或另一 session，也不碰 Gallery 原檔。仍在外部等待回執的 session 不會因初始化而立即刪除；遺留 cache 沿既有 24 小時到期規則處理。

## 契約與接收端界線

維持 Camera Import artifact 1.1.0／wire 1.0、manifest-first ClipData、暫時唯讀 grants、完整原檔 SHA-256 與精確 session/provider/item coverage 回執驗證。沒有新增持續授權、receiver/schema 或額外依賴。

本批最多 100 張，低於 Serein 現行有效可匯入 500 張的 preflight 上限；仍須接收端自己的空間、長度與完整雜湊驗收。跨 UID test APK 是 wire／Android grant fixture，不是 Serein catalog 的實機驗收證據。

## 自動驗收

| 層級 | 內容 | 證據界線 |
| --- | --- | --- |
| JVM 35 項 | 清單容量／不可變快照／重複通知、原始 bytes/hash、空間飽和、零進度／單次讀取失敗、取消、單一 owner/token、即時 cleanup job | 2026-10-07 07:48:47 UTC 的 frozen source focused runner exit0，35/35、0 failure/error/skip，551 來源 hash 不變 |
| AndroidTest 編譯 | Kotlin 與跨 UID fixture 的 Java | 同一 focused runner 編譯通過；不等於裝置執行成功 |
| 真 MediaStore | 9 項 publication observer 附加案例、6 項精確原檔 staging／清理／IO 返回取消案例 | 必須於 API34／36 runtime gate 驗證，不能由 JVM 推定 |
| 跨 UID 14 項 | 嚴格回執與缺 grant 反例、唯讀權限、兩個 active session 的撤銷隔離、真正手動／AutoJPEG VM 發布、Stop／Disconnect 後零相機讀取、Activity 重建、receipt IO 中換相機 | 使用 framework-only test receiver/provider、真 FileProvider 和 ActivityResult；不代表真 Serein 已匯入 catalog |
| 介面 7 項 | EN／zh-TW、2x font、真實直／橫向 Dialog 邊界、100 筆捲動、Stop／Send 分開、Cancel／retry、結果區隔、Clear 確認、Close／Back | 必須於 emulator runtime gate 確認實際可點與完整文字，而不只看 semantics 存在 |

交付仍須同一最終程式來源的完整 App／contract JVM、AndroidTest 編譯、Lint，以及該 PR exact-head 的 API34／API36 與 ci-complete。主分支接受與 Development Preview 發布另按既有流程；本文件不是 gate receipt。

所有圖片、相機 HTTP、provider 與回執都是 synthetic fixtures。沒有自動操作實體相機、沒有新光學對焦／弱網／大卡／手機相簿或 Serein 真機相容性宣稱。早期未知或中斷的 runner 保持未知，不以後續重建冒充原來源驗證。
