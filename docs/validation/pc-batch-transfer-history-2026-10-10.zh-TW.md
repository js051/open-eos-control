# PC 最近批次儲存紀錄

> 此為原 started-attempt 批次的歷史證據。後續完整工作清單候選增加全部已選列，保留舊紀錄遷移與動態匯入邊界，見 [完整清單](pc-complete-batch-worklist-2026-10-11.zh-TW.md)；以下原 head／結果不改寫為新候選的驗收。

## 原始需求與界線

本批延續產品缺口矩陣 P2「原檔確實到達目的地、重啟後知道完成什麼」。基底為 PR237 exact5585ea7（本地等樹 abe2498），其日期／評分／資料夾、多選與循序目的檔保存 CI38091355470 已成功。此前 state.mediaBatch 僅在記憶體，新批次、reset 或頁面重新載入會丟失結果。

本次提供最近 100 筆**實際開始的批次嘗試**，不是完整可恢復隊列。尚未開始項目、單檔 Blob/anchor 下載不在此紀錄內；不因此聲稱整條 P2 或跨程序續傳完成。單檔交給瀏覽器不等於目的檔已保存，沒有套用批次 close 成功語意。

## 行為

- 連線頁與素材頁都可開啟原生 dialog 查看紀錄，無須連相機；關閉後焦點回到入口。英／繁中、窄畫面與大字體沿既有語言流程。
- 紀錄只保存在目前瀏覽器設定檔、同一 Bridge origin 的 IndexedDB。換網址／連接埠／瀏覽器不會自動搬移；瀏覽器清除網站資料會移除紀錄。
- 每批在使用者明選目錄後取得 epoch ticket，每項在 destination.open 前記 in-progress，隨後重核取消與 session；只有實際 writer.close 成功才記 completed。HTTP/read/write/close/cleanup 失敗依既有事實保留 failed 或 cancelled-or-unknown，並分列可能殘留／結果不確定。
- 冷讀未終態顯示結果未確認，不推測成功、不自動重試；讀取不把共享 pending 寫成終態，因另一分頁可能仍在執行。
- completed 代表當時 browser close 成功，不代表檔案現在仍存在、硬體 fsync、來源 hash、內容可解碼或相機端刪除授權。
- 清除需要明確二次確認，只清歷史，不刪目的檔、不停止傳輸。原子清除遞增 epoch；舊批次晚到 finish 或後續 start 不能重新建立已清除紀錄。
- 紀錄故障顯示 warning，但不增加 GET、不重播、不刪成功檔、不改實際 close 成功為失敗。寫入未確認時，冷讀可能仍是未確認；應由使用者查看目的地。

## 隱私與儲存防護

僅保存隨機 receipt/batch ID、清理後顯示檔名、固定目的地種類、時間、結果、已知 bytes 與不確定旗標。不保存 CameraMediaItem、wire ID、session/camera ID、URL、token、目錄名稱／路徑、handle 或原始 error.message。檔名去除 URI/path/query/fragment/control，無法安全顯示則用 placeholder。

單一有界 snapshot 放在 IndexedDB readwrite transaction，讀改寫由 browser 序列化，跨分頁不以 localStorage 整份覆寫。schema/欄位/型別/ID/檔名/Date 範圍/100筆/100KiB 驗證，未知版本或損壞拒絕而不清空。每個 open/transaction 預設 2 秒；timeout 嘗試 abort，若 commit 已發生而 abort 被拒，明記 transaction-unconfirmed，不假稱 rollback。較晚事件不能重複決定結果。

## 驗證

- store 34 項 Node 控制：欄位與名稱隱私、冷讀不寫、跨 instance 更新／清除 epoch、清除失敗保留、100筆與時間倒退、第一個終態不重寫、quota、corrupt/future schema、blocked upgrade、timeout 已stage/commit競爭、Date最大界線。
- production callback ownership 保留既有17項並新增14項：紀錄start/finish故障、晚ticket/admission、取消／disconnect時close真實成功、close拒絕與abort失敗、clear/eviction不補寫、不重試、warning/render例外不改傳輸結果，以及pending clear與refresh競爭、失敗恢復、close/reopen與晚list不得搶新dialog焦點。
- 完整23個Node模組腳本的結果另存本地 receipt；獨立來源審查指出的清除視窗owner/focus競爭已補最小修正與受控測試。正常 secret scan 在交付前完成。
- 原 browser 雙語批次旅程延伸真 IndexedDB reload、離線入口、窄200%、清除確認與焦點；新增 pending reload 不自動請求及第二分頁clear後晚finish不重建。此文件初版的 browser runtime **NOT_RUN**，待同 PR 下一完整 normal CI；synthetic目錄不代替真 OS provider 驗收。
- 後續 exact head `644ced49a74331b9fdbc4e67558b5e66cc4e54e4` 的正常 CI `38093214237` 已全適用成功；原始 desktop job `114333747212` 確認英繁中完成後 reload／離線／clear、pending reload、第二分頁 clear／late finish 四個紀錄旅程通過，另含 backend/package、Windows、JVM/APK、secret 及 aggregate。此證據只驗該 head，不繼承到後續前景 auto-import 修改。
- 完整 App 人工／實機驗收依使用者方向開發後集中。不新增workflow、不改KVM／安全設定、不移除既有gate、不發版。
