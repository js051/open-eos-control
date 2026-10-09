# PC 素材大小的證據邊界（2026-10-09）

基底：已驗收 main `65fbabf00c78924228ec7cbfe22d733ef806094f`。本批不包含尚未合併的日期篩選、RTP 或刪除 ownership 分支。

## 問題與修正

CCAPI 的僅路徑清單與缺少 metadata 的 gphoto2 回覆會使用 `sizeBytes: 0` 表示尚未取得大小；既有 PC 卡片、資訊與預覽卻顯示 `0 B`。同一欄位也可能來自實際空檔，因此目前 wire contract 不能區分兩者。

新增素材專用 formatter，只有正的數值大小才顯示容量。零、缺值與無效 metadata 不產生容量標籤，其他尺寸、種類與素材位置資訊保留。不推測未知容量，不增加 metadata 查詢，不修改 API、下載或相機控制。

一般位元組 formatter 保持原樣：傳輸尚未開始的 `0 B` 與儲存空間已知剩餘零仍是有效資訊。

## 本地證據

以 production function 擷取加最小 DOM 的 regression 測試：accepted source 78 案例中 49 個 rendered-text assertion 失敗、29 個控制通過；修正後同一測試全部 78 通過。涵蓋三個顯示介面、缺值與無效型別、正值、已知轉未知與未知轉已知，以及傳輸／儲存零值、下載 owner 保留與無新增請求。完整 `test:modules` 也通過。

執行限制為單 CPU、60 秒、640 MiB aggregate RSS；focused green 0.511 秒、51,996 KiB，完整 modules 1.721 秒、117,020 KiB。這些是合成 DOM 驗證，不代表 HTTP、真瀏覽器或實體相機通過。

## 正常瀏覽器驗收

現有 CCAPI 瀏覽器旅程增加：真實 path-only 清單不顯示容量；使用者明確開資訊後，以合成 metadata 回覆 4096 bytes，卡片／資訊／重新開啟的預覽顯示 `4.0 KB`；再次取得零值 metadata 後三者移除舊容量。記錄明確資訊請求次數，保留原有後續刪除與其他驗收。

此新增 DOM 旅程目前尚未執行，待正常 CI。未聲稱已開啟的預覽會自動同步 metadata；測試明確關閉並從最新清單重新開啟。下載控制／位元組完整性仍由既有完整測試負責。

## 發布界線

PR219 原始 Compose 原因仍 UNKNOWN；凍結 v0.13 的 HOLD 不因本批而解除。本批沒有合併或發版，沒有實體相機驗證。
