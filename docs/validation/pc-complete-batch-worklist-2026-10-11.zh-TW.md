# PC 完整離線批次工作清單

## 範圍

接續原 P2「重啟後知道完成什麼」的已選原檔批次。在使用者明確選擇目的地後、開啟目的檔或請求任何原檔前，一次保存凍結的全部已選列及順序。這是可離線查看的工作清單，沒有自動重連、恢復下載、保存目錄 handle、重試或相機操作。前景動態 JPEG 匯入沒有預知完整 roster，仍只記真實開始的獨立嘗試；單檔瀏覽器下載仍不當作確認已落地。

基底為 PR237 exact `80a3ce6aa4927558997374669d257c1da10abae8`（本地等樹 `b854481`，tree `021fdc9e52c147439cf3ccb37fba568338f5b0e3`）。該基底 CI `38111699034` 全適用成功，包含四個前景交付 browser 旅程；這不是本候選的 runtime 證據。

## 儲存與權責

- IndexedDB snapshot v2 為每一批及每一列產生本地 ID；名稱只做保守清理後的顯示，不能識別相機或來源。相同名稱仍是不同列。列不保存 wire ID、session、URL、憑證、來源大小、目錄或 handle。
- 全部排隊列、已開始的計畫列及其他獨立嘗試共用 100 列及 100 KiB UTF-8 上限。整份新清單原子接受或拒絕，不能截短、驅逐既有計畫或依靠清除 active receipt 湊額度。保守預留計畫全部終態的編碼空間。
- 既有 v1 嚴格唯讀可見，沒有偽造完整 roster；下次成功寫入時同一 transaction 遷移到 v2。舊程式遇 v2 會拒絕，不能把新清單覆寫成舊 schema。
- 每項在 destination.open／original GET 前確認該列 attempt admission。未確認 admission 就停止，佇列維持 not-started；若 transaction 其實已提交但回覆丟失，冷讀可保留 unknown，不能宣稱沒有嘗試。
- 整份 plan admission 若確認沒有 commit，可警告後保留原手動保存能力；若 commit 成敗未知，必須先精確唯讀核對完整 plan，仍未知則停止，不能把未知當不存在而繼續未記錄的下載。
- 取消、斷線或替換 owner 在每個 await 後重新檢查，不會啟動晚到的新 GET；已成功 writer.close 的真實 receipt 不因 UI 退休變成失敗。close 不是 fsync、永久存在、來源 hash 或相機端結果保證。
- 清除一次原子移除 plans／entries 並增 epoch，不刪除目的地檔案。已清除的舊 plan 不重建；同批尚可依原手動傳輸繼續，但不把之後的紀錄重新插入新 epoch。finish 只更新其原 receipt。

## 呈現

歷史視窗依批次保存順序展示每一列、批次建立時間與列序；已開始列只展示其既有 receipt 一次，尚未開始列標示排隊，未確認列保留目的地檢查提示。其他／舊嘗試明確沒有完整工作清單。可在離線連線頁讀取；顯示或清除都不提出資料夾授權，也不自動執行網路指令。英／繁中沿既有語言機制。

## 驗證界線

- Store 53 項 bounded Node 測試通過，含 50 項在 5 completed／1 unknown／44 queued 時冷讀不漏列、100 列容量、混合獨立嘗試、遷移、跨分頁 clear、重複名称、schema/欄位拒絕、transaction rollback／commit 回覆不明。
- 正式 callback／queue 的 81 項 Node 控制通過，含 plan／row admission 後取消／斷線／替換、clear、缺少確定不存在旗標時拒絕 I/O、完整 roster 不重複 receipt。另補 store 已 commit 但回覆未確認的 row 負例：不回 receipt capability，冷讀仍是 unknown，未開始列保持 queued。最終來源固定後完整 25 模組 exit 0，6.026 秒，單 CPU／256 MiB Node heap，來源核對未變；不沿用基底結果。
- 原 browser reload 旅程增加完整兩列與 unknown／queued 對照；第二分頁 clear 旅程保留晚 close、兩個原檔成功與不重建檢查。Browser runtime 目前 NOT_RUN，待同一原 draft 的正常 CI。
- 完整 App／真相機／實際 File System Access provider 驗收依使用者方向開發後集中。既有必要 CI 不刪除，不新增 workflow，不改 KVM，不合併或發版。
