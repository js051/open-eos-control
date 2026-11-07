# PC 對焦互動歸屬與回饋

本批接續原 P1「命令接受不等於光學合焦」及換連線後舊回覆不可污染新操作的契約。基準為原 PR #237 的 `ede2f579a6722e9cfff69f95ef10a83211b5e375`。不新增相機命令、連線方式或 held AF 功能。

## 已重現的軟體反例

從基準直接擷取 production `tapFocus`，以可控 Promise 與 timer callback 執行：current-session 正控制通過，四個反例失敗。舊成功會清除新 busy、舊失敗會覆寫新提示、click white balance 舊回覆會覆寫新 status，舊 marker timer 會隱藏新連線的 marker。另對短 AF／half-press 驗證新 session 物件重用相同 ID，兩個舊狀態回覆反例失敗。

這些是 Node 中執行原函式的受控 UI-state 反例；不是實體相機故障、真瀏覽器 disconnect 操作或光學結果的證明。

## 修正範圍

- 三個操作綁定原 session 物件及既有 `refreshGeneration`。只有同一 session、同一互動世代能套用回覆、顯示失敗或在 finally 清除 busy。
- tap 的延遲隱藏也核對相同歸屬。同一 session 開始下一個操作會同步隱藏舊 marker；或 disconnect/reset 退休原世代後，舊 timer 不再碰新 marker。
- 保留 current-session `SHUTTER_RELEASE_UNCONFIRMED` 警告與原安全停止責任；不重試命令，也不把忽略舊回覆宣稱為取消相機端 I/O。
- AF-ON 的英文／繁中回饋明說命令已接受、實際合焦尚未確認；tap AF 接受使用中性色，click white balance 保留原成功回饋。

## 驗證

新增 24 個 Node 受控案例，含成功／失敗、current release risk、不同及重用 ID 的新 session、同 session 較新操作、disconnect、忙碌拒絕、white balance 與 marker expiry。新模組加入原 `test:modules`，保留原 26 個模組。

Focused 最終 24/24 通過；最終來源的完整 27 個 Node 模組全部通過（各模組獨立計數，不宣稱瀏覽器旅程通過）。首次 source 恢復測試因缺 index.html 未進入產品 assertions，已補齊固定 head 檔案，保留該環境失敗。

既有 browser Canon 旅程新增 AF 文字與 tap 中性 marker 斷言，原 AF start/stop、half-press/release 及座標斷言保留；本地 browser／真相機／完整 App 集中驗收尚未執行。正常遠端 CI 必須綁定實際發布 head，尚未取得前不稱 PR ready。保留先前各 head 的失敗及成功記錄。

## Release Assessment

目前發布基準為 v0.12.0 Development Preview；本修正屬向後相容 patch。原 PR 的完整媒體新能力仍屬 minor，不能以此 patch 降低整體影響。無本批 main accepted 或發版；光學合焦、實機斷線與真瀏覽器視覺結果仍未驗證。
