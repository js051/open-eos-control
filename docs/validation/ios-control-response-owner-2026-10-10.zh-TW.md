# iOS 控制回覆的連線擁有權（本地準備）

## 來源及交付狀態

本批最初從 PR #231 已測 head `c7858f311fa731aa87b738aa3fd204e3764956dd` 準備。#231 正常合併後，已移至 accepted main `060ad3dd87778178b130b225aea916522f722576`；兩來源 tree 均為 `cca646ae8e6aea44de108d53c6b9e2a55c7bf9a1`，完整 diff 相等，main acceptance `38029532625` 通過。原本地準備 commit `988aff5` 保留，production／tests 内容不變。本地提交僅保存準備成果；尚未推送或觸發本批 CI。

本機沒有 Swift 執行環境。以下目前是來源證據及待執行回歸規格，不能稱為 runtime 紅綠驗證或真相機驗證。

## 問題及修正界線

13 個既有操作在 await 後未確認原連線，且 defer 無條件移除操作忙碌狀態：時鐘同步、建立資料夾、檔名設定、休眠、感光元件清潔、自動對焦、半按快門、錄影切換、點選對焦、點選白平衡、焦距驅動、Live View 放大及一般設定。

最直接的後續命令風險是休眠／清潔：A 的遲到回覆可能呼叫讀取當前 session 的 disconnect 或 startLiveView，或把 A 事件迴圈重新安裝成 B 的迴圈。一般操作也可能覆寫 B 狀態、錯誤、標記、能力及同類忙碌狀態。

修正於操作開始捕捉 session generation，每次非同步命令／讀取返回及錯誤處理前核對，並使用既有 generation-aware end。清潔恢復 Live View 後再次核對，才清除錯誤。已退休的操作不再啟動 AppState 後續能力讀取或恢復新連線。

原命令仍傳送至捕捉的 A client。忽略遲到結果不等於撤銷相機已執行的命令；不增加重送、盲目停止或全域取消。既有 client 原連線安全釋放、停止及 close 保留。

## 驗證規格

已新增 `ControlResponseOwnershipTests` 的 9 個測試方法、180 個參數情境，涵蓋 CCAPI simulator 與 Bridge。測試尚未執行；來源檢查及 `git diff --check` 通過。未撰寫此 diff 的 reviewer 已獨立核對 production、fixtures wire 格式與測試控制，未見 blocking source finding。gate 到達等待上限 5 秒，操作 Task 完成等待仍依測試執行器的整體限制，尚需 runtime 驗證。

使用會忽略取消的 synthetic HTTP transport，分開控制命令 ACK、狀態／能力讀取及事件回應。觀察確切操作 Task 結束與必要事件握手，不以等待一段時間當完成證據。

- 遲到成功／失敗不能改寫 B，也不能解除 B 的同類操作忙碌狀態。
- 多段讀取於各 await 邊界檢查；退休後不開始新的 AppState readback，已開始者不得發佈。
- 休眠與兩種清潔模式不得因 A 的回覆關閉 B、啟動 B Live View 或替換 B 事件來源。
- 當前連線正向操作仍更新狀態；當前休眠／自動關機仍正常斷線，保留原相機的必要清理。

## 仍開放的獨立界線

來源盤點另識別 Bridge 掃描的設定／請求擁有權、相同座標對焦標記的舊到期工作、診斷及實體驗證匯出資料跨連線混合、排隊 RTP 回呼的身分，以及 app-wide LUT 的最新使用者選擇順序。本批不把這些未驗證路徑宣稱已修正，也不把 LUT 狀態誤當應隨連線清除的資料。

## Release Assessment

- 最新已發布基準：v0.12.0；來源 0.13.0 尚未發布。
- 影響：patch，修正既有控制操作重新連線後的狀態與後續命令擁有權。
- 本批目前只有本地來源準備，需完整新來源正常 CI／main acceptance。
- Development Preview release HOLD 保留；PR #219 原始 Compose crash 根因仍 UNKNOWN；本批不改 KVM 權限、不包含觀察器。
- 所有 fixtures 是 synthetic，實體相機／iPhone 相容性及控制行為尚未驗證。
