# iOS 中斷工作清單的手動接續

## 功能與操作

本批補上 P2 原計畫中，從已保存工作清單重新開始交付的實際入口。開啟「交付紀錄」，選取未開始、失敗、取消或結果未知的項目，再為每項明確選擇這次連線已載入的原檔。來源欄位初始全部空白；不依檔名、大小、日期、舊 IP 或重用 media ID 自動配對。同一目前來源一次只能選給一項。

來源選單只使用圖庫目前已載入的檔名、類型、資料夾、拍攝時間及大小，並在確認對應中保留相同脈絡，不自行讀取其他頁面。需要其他原檔時，取消接續、到圖庫載入，再重新選取歷史項目。畫面顯示舊項目及所選原檔的對應，並使用英文／繁體中文的原生清單、來源選單及確認對話框。

結果未知的項目可能已有副本，使用者須確認已檢查先前目的地，並了解可能建立另一份。使用者也須確認既有 iOS 交付方式：先在 App 內準備完整原檔，完成後使用「分享已準備原檔」，明確選擇 Files、照片或其他目的地。原檔準備、分享面板呈現及 provider completion 都不被當成外部儲存證明。

最後確認只消耗一次。App 先保存新的完整連結清單，再逐項 admission、下載、驗證原檔並保存準備結果。進度、停止、先前已成功原檔與分享動作沿用既有批次面板；在交付紀錄內即可完成整個流程。

已準備的歷史副本走離線再次分享。若同一組本機歷史連結已有 started 或 prepared 嘗試，祖先／其他相關列不提供另一個接續入口；原始列的歷史狀態並不改寫。prepared 副本 missing／changed／unsafe／notRetained 也不改成失敗或默認重抓，必要時仍可由普通圖庫流程明確另選原檔。

## 保存與所有權

manifest v3 在新 planned row 增加可選 origin，僅含本機隨機 batch UUID 與 row UUID。原始清單、原始準備成功／未知／其他結果保持不變。這是使用者本次選擇與舊項目的關聯，不是來源等價或檔案去重證據。不保存相機 ID、session ID、media ID、URL、token、目的地路徑、bookmark、provider 或權限。

v1/v2 仍可讀取；v1 不捏造完整清單。有效舊格式讀取只在記憶體遷移，下次成功 mutation 才寫 v3。舊版 App 遇到 v3 應以原有未知 schema 拒絕操作，不能把新欄位忽略後覆寫。未知欄位、不存在／循環／向後非法 origin、重複身分與不合法狀態均拒絕。

新清單 admission 在 store actor 同一交易檢查 epoch、每個 origin 的存在／狀態、選取唯一性與未知項目確認。全體原始及新列仍合計最多 100 筆，保留副本 512 MiB，manifest 256 KiB，不刪除或淘汰舊紀錄。容量、儲存或 admission 失敗時，本接續流程不退回無紀錄下載。

每次 GET 前重新檢查持久 row 已 admission，以及相同 App batch owner、connection generation、載入清單 generation、history generation、取消狀態與目前來源仍已載入。背景、取消、清除紀錄、更換連線或任何圖庫 generation 更新（含相機事件刷新與直接重用已載入資料的範圍切換）會退休並關閉本次選取；晚到回覆不得開始下一個原檔、污染替代批次或重建已清除紀錄。沒有自動重連、背景工作、Range、新端點或新增傳輸框架。

## 控制來源與集中驗收

新增 16 個 Store 與 14 個 App caller 測試函式；來源涵蓋：

- 50 列 cold reopen：5 prepared、1 unknown、44 queued；只明確配對的 subset 建立新清單並下載，舊列及結果不變。
- 每項必選、未載入來源拒絕、相同顯示名與 Unicode wire identity、不能重播確認、未知項目未確認不得 GET。
- started／prepared lineage 阻擋、重新分享不增加網路請求。
- plan／item admission 後取消、清除、容量／原子寫入失敗、late plan／late original、相同 wire ID 的新 session、背景、事件刷新與範圍重用的重新載入退休；item admission 後換連線會以原 ticket 收束 cancelled，不污染新 session 或永久封鎖同一 live store 的舊 lineage。
- v1/v2 migration、v3 精確 metadata 範圍、惡意 origin、epoch 與 byte／record 上限。

本地 `git diff --check` 通過，31 個工作清單 UI localization key 的 EN／zh-Hant 存在性、重複鍵與格式參數一致性檢查通過；獨立來源 review 發現並修正最終 retention 回覆後取消仍標 completed、測試 teardown 後 gate 仍可能重新阻塞、事件刷新只讓綁定失效而未關閉視窗，以及 item admission 回覆前換連線留下永久 started 的問題。這些都不是 Swift 編譯證據。環境確認沒有 Swift／Xcode，本批 Swift 編譯、XCTest、SwiftUI runtime 與 iPhone Simulator **NOT_RUN**。新增測試不是通過證據，重新建立 store instance 也不是真正 OS kill。

集中驗收須執行完整 iOS build／App tests／UI tests，並以 EN／zh-Hant、大字體、小尺寸畫面測試逐列選取、來源 picker、確認取消、停止、清除、分享 sheet 取消及再次開啟。真 iPhone 冷啟動／強殺、Files／Photos／第三方 provider、磁碟不足與實體相機驗收仍待補。必要 CI／安全檢查不變；沒有推送、PR 更新、main 合併、正式部署或發版。

Release Assessment：基線為 `70a180d424d60c13f7e60c6748b06bfa00754f6b`（repo 版本 0.13.0；未另外查詢最新 release）。此為新使用者可見能力，建議下一次有完整驗證的 Development Preview 以 minor 評估；本批不變更版本。精確 SHA 集中驗收與實機狀態仍未完成，不宣稱 PR ready、main accepted 或 release ready。
