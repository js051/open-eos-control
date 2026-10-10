# PC 媒體刪除回應的連線歸屬

## 問題與修正

基底為 accepted main `65fbabf00c78924228ec7cbfe22d733ef806094f`。A 連線的刪除請求尚未完成時，使用者可以關閉素材詳情、正常中斷並連接 B。舊回應沒有驗證 owner；A 成功會移除 B 相同 ID 的列、撤銷 B 縮圖及關閉 B 詳情／預覽，A 拒絕則覆寫 B 的錯誤回報。這是畫面狀態歸屬問題；反例只發出對 A 的一筆 synthetic DELETE，沒有刪除 B 相機素材的證據。

DELETE 現在捕捉起始 session object，成功與拒絕都在任何 UI 寫入之前核對該 object 仍是目前連線。比較物件而非 wire ID，涵蓋重用 ID。原同連線操作、手動 Refresh、其他互動的 busy 狀態及單次 DELETE 行為保留，沒有自動重試或全域鎖變更。

## 驗證

- 相同最終19項來源測試在未修改 accepted source 為13通過、6個 stale-response 失敗；修正後19/19通過。
- 測試執行真實 production connect／disconnect／reset／refresh／availability／delete／error／dialog清理函式，使用受控 synthetic promises，不連真相機。涵蓋離線、不同及重用 wire ID 重連、成功／拒絕、同連線手動刷新與其他互動、無連線／不支援／busy／傳輸中／取消。
- 全部11個 Bridge module suites、backend pytest全套與 Bridge/validation Ruff 通過；Node syntax 通過。使用1 CPU及640 MiB聚合RSS停止線；module最高抽樣RSS約62.5 MiB。
- 新增兩個正常 browser DOM旅程：UI刪除→關閉詳情→中斷→重連→開啟B同ID詳情→交付A成功／拒絕。等待實際response body被消費及下一個browser task，避免交付前斷言造成假通過。合成DELETE由route直接回應，不送往backend或真相機。
- 新browser旅程尚待exact-head正常CI執行。本機Chromium先前在啟動階段受環境限制，沒有宣稱本機browser pass。

## Release Assessment

- 最新發布基底：v0.12.0 Development Preview。
- 本批 impact：patch，修正既有素材刪除操作的晚到回應污染新連線畫面。
- 完整候選相對v0.12的版本分類須包含其他已接受的新功能，不能僅據此修正降為patch。
- exact-head必要CI與main acceptance仍待本批交付；實體相機驗證pending。
- PR223普通App驗收與PR219未知Compose根因另案；frozen v0.13 HOLD不因此解除。本批不修改版本或release資產。
