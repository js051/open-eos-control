# iOS 倍率變更後的幾何與座標控制驗收

日期：2026-10-15。原始產品基準：`9bde4450046ebd465f5cc873b80c91f0edd064ff`。倍率變更提交基底：獨立 test-only reconciliation commit `722258d48edf23cf02f199982532c983efca1be9`。

## 範圍與狀態

修正既有 1× → 5× → 10× → 1×、重新取得詳細影格幾何、Touch AF／Click WB 的 owner 邊界。主要影響 direct CCAPI RTP／multipart；JPEG 的舊讀取及 App 共用影格讀取也受相同生命週期保護。未增加傳輸或相機能力，未依倍率比例推算裁切範圍。

狀態為 implemented 候選：產品修改及 focused 測試已撰寫，Python source guards 已執行；Swift 編譯、Swift 單元測試、原生解碼／呈現、UI、真機全部 **NOT_RUN**。本輪禁止 native／device 執行，不能把來源檢查計為 Swift runtime 驗證。尚非 PR ready／main accepted／preview released。確切檔案 SHA-256 與命令終態另列本輪 evidence receipt。

## 修正前的來源反例

基準 `CCAPIClient.setLiveViewMagnification` 只有 PUT／readback，沒有退休 `latestLiveViewGeometry`；`ensureLiveViewGeometryForNativeStream` 僅在 cache 為 nil 時讀 detail。因此 initial rect `(100,200,1000,800)` 已被使用後，即使 zoom 5× 的新 rect 為 `(220,350,200,160)`，下一個 normalized `(0.25,0.75)` 仍可由舊 cache 得到 `(350,800)`，而非 `(270,470)`。這是可由實際分支與寫入點確認的來源反例，沒有聲稱已在相機或 Swift executable 重現。

基準原生 detail 讀取也沒有 await 後的 session／source／revision 檢查；JPEG 舊讀取可在完成時重新寫回幾何。最初八項來源檢查全 FAIL，其中七項為當時缺少防護，一項為尚未撰寫新 native 測試檔。此 FAIL log 保留；缺少測試檔本身不算產品反例。

## 實作邊界

- Direct zoom dispatch 前退休幾何與 local frame mailbox；settle 時再退休 mutation 期間的讀取。成功、拒絕、失聯、不確定 readback、取消均不恢復舊幾何。
- detail／JPEG／focus metadata 擷取 session、source、geometry revision，await 後再驗證。座標命令在建立 request 後、真正 transport send 前再次驗 owner；舊控制回應也不能更新新 App marker／status／error。
- magnification attempt 只封鎖其精確 session／source／revision；Stop→replacement 可在舊 PUT／readback 尚未完成時繼續，舊 defer 不得清掉 replacement attempt 或幾何。
- fresh detail 使用既有有界影格讀取，要求完整 HTTP 200、無 Content-Range、完整 Canon packet 與有效整數 rect。負數、布林、字串、分數、缺欄、零尺寸及溢位不提供座標權限。
- App 先退休 reader ID、舊 JPEG、focus marker 與舊呈現進度，再執行倍率。settle 後只替同一 lifetime 恢復 reader；倍率期間切換 refresh 不會重啟舊 reader。暫停 reader 不會發送 camera Stop。
- RTP 本地 flush／decode epoch 在 zoom begin／end 更新。receive admission 在 await 前擷取 revision，partial AU 跨 revision 會 reset；舊 receive、舊 queued Task 與 decoder callback 不能重標成新 crop。只在本地新 decode／submit 且 flush barrier 完成後允許座標 UI，audio／camera receiver 的既有 owner 保留。
- multipart session 在同一 lock 中處理 parse、mailbox publication 與 retirement。跨 revision receive 的完整輸出及殘留 partial part 都丟棄；partial boundary／header／body 保留 Content-Length framing 與驗證，讀完舊 part 但不發布，接收下個新完整 part。沒有以重設 parser 猜測 JPEG 中間位元組。

## 共用 fixture 與 authored native 測試

`contracts/magnification-geometry/v1/geometry-transition-vectors.json`：12 個 authoritative rect／point vectors，SHA-256 `e73afb6875e8d1819e2e57d2e0f509ece850f68fb69fc925e5b88cfeffcd7609`。四個 crop 各涵蓋 origin／interior／edge；return-1× 的 origin 刻意與 initial-1× 不同。

新 authored native 方法共 21 個，全部 NOT_RUN：

- `MagnificationGeometryTests`（10）：三種既有來源 × 12 vectors × Touch AF／Click WB；mutation 期間封鎖；舊 detail／JPEG completion；五種 zoom 失敗；取消；retired source；舊 readback 仍 pending 時先啟動 replacement／執行新 zoom／新座標；無效 rect 與不完整 detail。
- `MultipartGeometryOwnershipTests`（4）：逐 byte split 的 boundary／header／binary body；舊 receive 夾帶下一段 partial part；正常下一 frame；被退休內容仍須通過 JPEG／terminal 完整性檢查。
- `ControlResponseOwnershipTests` 新增（2）：bridge／simulator App 共用 reader 的舊圖→zoom→新圖、success／failure、refresh toggle、控制封鎖，以及舊控制 completion 不回寫。
- `RTPEventDeliveryOwnershipTests` 新增（2）：舊／期間／新 video revision、audio session 不退休、晚到 finish 不干擾新 pause／replacement。
- `RTPGeometryDepacketizerTests`（3）：partial FU-A 跨 crop、receive 跨 crop、parse 中跨 crop；無 socket／decoder／camera。

## 可執行證據與限制

Focused source suite 共 16 項，內含 19 個 guard-removal 反例與獨立 fixture 算術檢查。這些檢查的是來源結構／順序與 fixture，不執行產品 Swift。最終 expanded suite、diff check、來源穩定 hash 與命令 exit code 以 receipt 為準。

三項原有 RTP decoder／presentation 改版後的舊字串斷言，另由 test-only reconciliation 修正；檔案為 `test_liveview_lifecycle_source.py`、`test_liveview_lifecycle_guard_mutations_source.py` 與獨立 reconciliation 文件。這三檔已先獨立提交於 `722258d48edf23cf02f199982532c983efca1be9`，原三項變綠歸屬獨立 test-only receipt。其後本倍率 producer 改為在 iterator await 前擷取 revision，讓另一項全檔禁止 `iterator.next()` 的斷言過時；本倍率只新增該測試的精確範圍修正：UI `nextFrame` 仍禁止直接驅動 iterator，producer 則必須先取得 revision、再 await、再攜 revision publication。故本倍率 receipt 包含 `test_liveview_lifecycle_source.py` 的這個新 hunk，不含前三項 reconciliation；其餘兩個 baseline 檔案排除。兩次 202 PASS／1 FAIL 的中間來源結果均保留，沒有透過改變 iterator 名稱迴避斷言。

本地 owner／epoch 可排除明確舊 buffer、已排程 delivery、partial assembly、舊 displayed image，無法證明 camera／network buffer 在 wire zoom ACK 前後的像素因果，也不能把 decode／submit 當作實際螢幕呈現。實體相機、RTP decoder、顯示層、音訊與完整 native tests 的驗證仍待後續授權環境；現有 KVM pending evidence 未替換。

## Release Assessment

- 類型：`patch`，修復既有倍率與座標控制的相容行為。
- 發布基準：本輪未查詢或改動 live release／版本；不能由本文件推定下一版號。
- blockers：native compile／runtime 未執行、exact-SHA CI／main acceptance／candidate 未執行。
- physical-device：NOT_RUN，沒有新增相容性或可靠度聲明。
- 本輪未 commit、push、觸發 CI、安裝工具、改安全設定或發版。
