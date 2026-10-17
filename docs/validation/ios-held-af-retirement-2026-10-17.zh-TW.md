# iOS Bridge held AF 退役窄修（2026-10-17）

## 範圍與判定

- 基底：`65aaf59df68856d93c52d27b36ba3422ac1acd24`。
- 修正 iOS Desktop Bridge held autofocus 釋放與斷線流程；不改 direct CCAPI、Bridge server 的實體 Start/Stop 鎖定次序、通訊協定或其他操作的 join。
- 狀態：程式與原生 regression fixture 已撰寫，僅 supplementary source checks 已執行。未編譯 Swift、未執行 XCTest、未使用實機，不代表 PR ready 或相機驗收通過。

## 所修缺口

原先 App release、Core release、普通及 maintenance close 都可等待 ignored-cancellation AF Start。Start 或其補償 Stop 不返回時，釋放與退役會一起卡住。

現在 Bridge Stop 以原 owner、generation、URL 建立一次 immutable request，經既有 `BridgeBoundedExchange` 的 15 秒總期限與 dedicated no-replay transport 發送。相同 owner 的同時 Stop 合併；晚到的底層資料不能直接修改 client 狀態。Start/Stop completion 各用獨立 task ID 與 generation 防止清掉替代工作。

只接受完整 HTTP 200、正確 owner、`active=false`、`stopUnconfirmed=false`。409 即使附上 inactive body 也不是成功；timeout、錯誤或不完整證據保留 warning。Session DELETE 不是 AF Stop 證據。

即使取得匹配 Stop ACK，只要原 Start 仍未真正返回，live session 保留 focusHold 與 release warning，既有集中 mutation admission、held AF、Bulb、maintenance 都不能建立新 owner。這是必要的保守限制：server tombstone 是有界歷史，不能用來授權無限新 owner。Start 真正結束後，只有自身 ID 可釋放已 ACK 的 reservation。

Close 不再 join AF Start；仍先嘗試 bounded owner Stop，再走原 retirement route。曾使用 AF 的普通 session DELETE 也有總期限；maintenance 保留其既有專用 retirement。未解決的原 Start/Stop 在退役後只留下 previous-connection warning，不能重播到新 session。其他實體操作的既有 joins 完全保留，沒有宣稱所有斷線路徑都因此有總期限。

App 只對 Bridge 略過 Start join，direct CCAPI 保留原行為。獨立 late-settlement observer 以 retirement ID、原 generation 與 warning observation revision 前後雙重核對，只在 Core 新鮮結果明確 idle 時清除自己發布的 warning；不能清掉新觀察、新 owner 或新連線的警告。這個晚到的安全狀態同步不自動恢復 Live View；若 pending release 已暫停串流，使用者須明確重新啟動，避免晚回覆覆蓋之後的暫停意圖。

## 證據

已執行：

```text
python -m unittest scripts.validation.tests.test_ios_focus_retirement_source scripts.validation.tests.test_ios_owned_bulb_source scripts.validation.tests.test_ios_lifecycle_continuation_source scripts.validation.tests.test_ios_maintenance_restore_token_source scripts.validation.tests.test_ios_recovery_language_source scripts.validation.tests.test_ios_project_source_paths
Ran 31 tests: OK

git diff --check: pass
command -v swift: absent
```

上述 31 項都是 source-only，含負控制（重新引入 join 必須被偵測）與原生 fixture 重複名稱檢查，不能替代 Swift 編譯或並行排程測試。審查發現的兩組重複 native test 宣告已移除。

已撰寫、未執行的 native regression 包括：

- Start 回覆一直扣住，Close 先完成；重新初始化同 session ID 後舊回覆不能清掉新的 pending Start。
- 同 session Stop ACK 後仍保留未返回 Start 的 owner，普通 AF/Bulb/新 held AF 不得發送。
- 409 不可當 release proof，僅同 owner 的有效 retry ACK 可清警告。
- release 與 Close 合併同一 Stop；Start/Stop transport 都扣住時，生產 15 秒 deadline 仍容許退役。
- maintenance ownership 下 Start 保持扣住，409 留 warning，只使用 maintenance retirement route。
- AF 已正常釋放後，普通 DELETE transport 不返回也受生產 deadline 約束，晚到 204 不清退役 warning。
- App pending Start 釋放不等待 transport，晚到明確 idle 只清同一警告、不重啟串流；較新 observation 與斷線後的新 held owner／前次連線警告均受保護。

## 發布評估

既有功能的相容性修復，預期 impact 為 patch；不更動版本或發布。Swift/XCTest 與實機 AF、相機連線退役證據仍待取得，未新增任何裝置相容性或可靠性宣稱。
