# iOS 前景 JPEG：逐次授權的檔案資料夾交付

## 本批產品行為

前景 JPEG 面板的主要操作改為「選擇檔案資料夾並開始」。每次選擇一個本機或雲端 Files provider 資料夾後，才進入既有 direct CCAPI 能力檢查、完整 baseline、新 ID、兩次新鮮穩定大小、單次原檔下載與 App 私有保留流程。既有檔案不回灌；Photos、folder bookmark、跨程序授權恢復、背景服務、自動重試與自動恢復不在本批範圍。

開啟面板或資料夾 picker 不讀取相機、不寫 probe。取消、關閉、背景化及連線退休使 picker token 失效；delegate 與 SwiftUI dismissal 都綁定原 token，舊 callback 不會開始新 run 或取消新 picker。UI 只顯示縮短、清理過的資料夾名稱，原始授權 URL 僅存在此次 run 的記憶體中。

每張原檔先成功保留至既有 MediaDeliveryStore，再取得該 receipt 的單檔 lease。私有保留失敗仍停在既有暫存分享流程，不繞過儲存契約直接外送。App 保留數、Files 複製完成數與外部未確認數分開顯示；外部失敗不把真實的 prepared 歷史改成下載失敗。

## 外部作業與生命週期

- 每次複製使用原始 scoped folder URL，成功取得 scope 才進行 file coordination。成功取得的 scope 僅在 accessor 與 coordination 真正返回後釋放一次。
- 同步 NSFileCoordinator 與 copy 在主 actor 之外執行。accessor 使用 coordinator 提供的 URL，支援 provider 改寫位置。
- 新的、無 delegate 的 FileManager.copyItem 使用 create-only 契約。固定目的檔名由清理後的原檔 stem、attempt UUID 與 .jpg／.jpeg 組成，最多 180 UTF-8 bytes。碰撞不覆寫、不換名稱重試、不刪除外部檔案。
- 來源 metadata identity 在 scope 前、accessor 開始與 copy 返回後核對。這不是內容 hash，也不是對所有外部競爭者的原子路徑保證；copy 期間來源改變時保守標為未確認。
- Stop 立即阻擋後續 admission，請求 coordinator 取消。已進入 accessor 的 copy 可能繼續；lease、scope、coordinator 與 drain gate 留到實際返回。成功 copy 先捕捉結果，晚到取消不覆蓋成功。
- writer gate 與相機 generation 分開。斷線可釋放相機控制，但舊 provider 作業未返回前不能開始另一個前景 run。舊結果不更新新 session 或 clear 之後的 UI。
- 未成功回傳的 copy 可能留下部分或完整檔案；App 不自動清除。複製完成只表示此次檔案作業返回成功，不代表雲端同步、斷電持久性、provider 可用性或外部檔案日後仍存在。

## 有界歷史與相容性

私有歷史維持 100 筆／512 MiB 限制，另最多 100 筆外部嘗試，每個 prepared receipt 最多一筆。只儲存本地 attempt ID、receipt ID、清理過的嘗試檔名、時間與固定 status/reason；不保存 provider URL、資料夾路徑、bookmark、相機 ID、憑證或原始錯誤。

v1、v2、v3 manifest 仍可讀，讀取不改寫；純私有歷史仍寫 v3。首次外部 admission 才提交 v4 與 externalAttempts。v4 保留 continuation origin 驗證，拒絕未知欄位、無效 status/reason 組合、dangling receipt、重複 attempt／receipt 與超限資料。

外部 started 必須在 copy 前持久化；無法寫入 admission 時不外送。重啟時未完成的 started 投影成 unconfirmed/interrupted，不自動續傳。copy 成功但終態寫入失敗時，記憶體保留成功、顯示歷史警告並停止 run；磁碟上的 started 在重啟後仍是未確認。這是 atomic manifest replacement，不宣稱 power-loss durability。

清除先提交新 epoch，移除準備與外部紀錄；已 pin 的來源由既有 tombstone 保留到 lease 釋放。晚到的 external finish 因舊 ticket 被拒，不能復活紀錄。清除 App 歷史不刪除 Files 副本。

## 測試來源

ForegroundFilesDeliveryTests 使用 synthetic local folder、注入 scope 與 coordinator，涵蓋：

- redirected accessor URL、scope balance、權限拒絕、coordination 拒絕
- lease 後來源 replacement、碰撞不覆寫、partial failure 不自動清除
- accessor 前取消、copy 中取消、晚成功、拒絕重疊 writer
- clear 時 pin／epoch 排除、成功後 history write failure
- v1–v3 相容與 v4 admission migration、重啟 interrupted 投影、嚴格 schema 負例
- UTF-8 檔名長度、JPEG extension 與不同 attempt 的同名來源

ForegroundMediaIntegrationTests 使用真正 App actions／CameraSession／CCAPI client，僅替換 HTTP、時鐘、scope 與 provider coordination，涵蓋：

- 選擇／取消不讀相機，以及 background／reconnect 後 stale binding／delegate／teardown
- 一次原檔 GET → 一筆 private prepared → 一筆 Files copy；重新 opt-in 不回灌
- 外部失敗後 prepared count 保留、未確認 count 增加、停止且不重試
- Stop／背景／disconnect-reconnect／clear 期間保留 writer 排他，相機控制不受 Files stall 鎖住
- clear 案例在真正 copy 前停住，只有 production worker 保有來源 lease；clear 後繼續 copy 並核對目的地 bytes

## 驗證狀態

| 項目 | 狀態 | 證據範圍 |
| --- | --- | --- |
| git diff --check | PASS | 修改文字的 whitespace |
| 英文／繁中鍵與 placeholder 對齊 | PASS | 兩份資源一致，不是畫面可讀性驗收 |
| 本批敏感路徑／持久授權來源檢查 | PASS | 未新增工作區絕對路徑、憑證、bookmark 或 Photos 流程 |
| Swift typecheck／App build | NOT_RUN | 本地無 Swift／Xcode；來源審查不能取代編譯 |
| 新增與既有 XCTest／UI tests | NOT_RUN | 必須由支援的 Xcode runner 執行必要檢查 |
| iPhone／iPad picker 與 provider | NOT_RUN | 需簽署裝置及實際授權 |
| 相機／雲端同步／斷電持久性 | NOT_RUN | 無相機或 provider runtime 證據 |

裝置驗收尚需 On My iPhone、iCloud Drive 與預定支援的第三方 provider；取消／重選、存取撤銷、離線／額滿、資料夾移動／刪除、碰撞、停滯 copy、Stop、鎖屏／背景／force quit、copy 中重新連線，以及實際目的地 bytes。mock、UI 或 copy API 成功不替代雲端已同步的證據。

## Release Assessment

本地 README 記載最新發布預覽為 0.12.0，來源宣告版本為尚未發布的 0.13.0；本批未另查核線上 release、未變更版本或發版。建議影響為 minor：新增每次明確授權的前景 Files 自動交付能力。尚待 native compile、必要 XCTest／UI／exact-head CI 與 provider 裝置驗收。來源不代表 PR ready、main accepted、preview released 或完整跨平台 parity；原必要安全與驗收檢查維持。

Apple API 參考：[Directory access](https://developer.apple.com/documentation/uikit/providing-access-to-directories)、[NSFileCoordinator cancellation](https://developer.apple.com/documentation/foundation/nsfilecoordinator/cancel())、[FileManager copy](https://developer.apple.com/documentation/foundation/filemanager/copyitem(at:to:))。
