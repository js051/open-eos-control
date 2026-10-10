# iOS 診斷與實體驗證匯出的連線歸屬

## 狀態及來源

來源是 accepted main `92c4bdce2d226c8ede3a148c3c164655b4a355e6`（PR #232、main acceptance `38030918221`）。本批目前為本地準備，尚未推送／執行 CI。本機沒有 Swift；來源核對不可當 runtime 通過。

## 已確認的缺口

`diagnosticReport` 先把 A 的 snapshot／Live View metrics 傳入 client，等待能力資料後卻讀取當前監看／媒體狀態。`physicalValidationRecord` 再把當前 info、transport、可驗證功能與人工確認加到該診斷的 hash，因此重新連接實體 B 時可能產出跨連線混合證據。同名相機也不代表同一個 session。

DebugView 的 Task 不因關閉頁面或重新連線自動結束；只有 preparation 結尾檢查也不夠，呼叫端恢復到 clipboard write 前仍有排程邊界。

## 本批契約

- 診斷監看欄位在第一個 await 前捕捉。physical summary／info／transport 同樣在開始時固定；人工確認是當時的快照，不把稍後變更倒填到已開始的匯出。
- 匯出結果包含 immutable text 及不可由其他檔案任意建立的 匯出 owner identity（在 connect admission 與每次 session 指派更新，包含 disconnected→connecting 及失敗後 nil）。退休或取消的準備拋出取消，不回傳混合資料。
- DebugView 在主 actor 同步核對結果 owner，之後不經 await 就寫 clipboard 及 copied-success 狀態。已準備但在發佈前退休的結果也不得發佈。
- 當前 disconnected/offline 的普通診斷仍可取得；實體驗證維持拒絕 disconnected/offline/simulator 的原規則，且不做無用的診斷讀取。
- 不取消相機命令、不增加重試、不產生真機驗證；hash 僅綁定實際診斷文字。

## 驗證界線

保留既有監看欄位／offline traversal assertions，只配合有 owner 的回傳值。新增 9 個 XCTest 方法、38 個參數情境；目前尚未執行。獨立 reviewer 核對修正後 production／tests，未見剩餘 blocking source finding。新增 synthetic capability gates 須涵蓋不同／同名替代 session、遲到成功／失敗、同 session 正向快照、已完成結果退休後的 fake publication sink、取消，以及實體驗證拒絕 controls。fake sink 不操作系統剪貼簿，不能冒稱已跑真 iPhone clipboard journey。

獨立 source review 指認原 sessionGeneration 只在 disconnect 更新，無法淘汰 disconnected 時已準備、隨後 connect 的診斷。因此本批用專屬 export identity，不改控制操作 generation；補上 connect success／failure、connecting report、disconnect 後重連等負例。connecting A→connected A 可保留 A 的時間點快照；A 失敗清除或被 B 替換時必須失效。gate 到達有 5 秒界線，所有 export／connection Tasks 在 cleanup 取消、釋放 gate 並 await；整體 Task 終止仍需 runtime gate 驗證。

## Release Assessment

最新發布基準 v0.12.0 Development Preview。影響 patch：修正既有匯出資料的連線歸屬及驗證證據完整性。需完整本批 exact-head CI 與 main acceptance；release HOLD、PR #219 根因 UNKNOWN、#223 KVM 待批准及實體相機限制保留，不改版本、發版或安全設定。
