# PC 單次事件的狀態與媒體恢復

## 問題與基準

基準是原 PR #237 的 `de5706ffeafcb8c258d67f0b079177969731d958`。PC event loop 先消耗 `changedKeys`，再呼叫 `refreshSession`；該函式在 info、status 或 capabilities 任一讀取失敗時回傳 `false`。原迴圈沒有保留提示，下一筆空事件也不再刷新。即使控制狀態成功，後續媒體列檔失敗的 `false` 同樣被忽略。

在修改產品碼前，以實際 `app.js` 函式和可控回應重現：

- 首輪 4 例全部失敗：info、status、capabilities 分別單次失敗，以及狀態成功後媒體單次失敗。原迴圈消耗下一筆空事件，仍沒有恢復排程，保留舊狀態或媒體。
- 增加連線替換控制後，基準共 8 例，7 失敗／1 通過。原媒體列檔在不同 ID 的遲到成功、同 ID 的遲到成功或失敗時，會對替換連線再送一次 GET；不同 ID 的遲到失敗已有保護，正常通過。

獨立審查另外發現初版修正的 coalescing 缺口：舊 A 列檔被限制後，新 B 的明確 Refresh 仍可能誤接 A 的 promise。先以同／不同 ID × 遲到成功／失敗取得 4／4 紅測，再補 session-owned coalescing。最終再覆蓋 A／B 先後完成兩種順序，證明 B 不必等待 A。這是開發中的修正，不是額外的基準缺陷。

兩輪基準使用同一命令：`taskset -c 8 node --test --test-concurrency=1 tests/event-state-recovery.test.js`，工作目錄為 `bridge`；結束碼皆為 1。紅測不是測試初始化失敗，也沒有修改 timeout 或放寬斷言來取得綠燈。

## 修正與所有權契約

- 每個 event loop 只保存一份固定大小的待處理提示，記錄是否需要 contents 更新，不累積任意 key 集合。在權威狀態和需要的媒體讀取成功前，不消耗下一筆事件。
- 完整 info／status／capabilities 成功後才發布狀態，並在媒體完成前先讓控制狀態可見。媒體失敗保留提示，下次先重新讀取權威狀態，再重試列檔。
- 讀取回傳 `false` 和 poll 失敗使用 1／2／5 秒上限退避，失敗計數上限為 3；成功 poll 不會提前重設未完成恢復的計數。完整成功後才清除提示；無待處理提示的空事件不新增狀態或媒體讀取。
- loop 由原 session 物件及 event generation 擁有；取消、重啟及同 ID 的替換連線都不能繼承舊提示。事件的遲到 snapshot 也不能在取消後發布或覆蓋錯誤。
- 沿用 interaction revision、latest refresh owner 和 recording recovery owner；等待 busy、媒體傳輸、自動匯入讀取及錄影命令結束。被新操作或手動 Refresh 取代的 snapshot 不發布，原 contents 提示仍可稍後恢復。
- 只對已載入且仍支援瀏覽的媒體清單刷新。保留 `preservePreview`、同 session 的 refresh coalescing，以及 scope／interaction 改變後的既有重試契約。
- 針對已證實的跨連線列檔，`refreshMediaWhenCurrent` 捕捉原 session 物件；它不能發布或向替換相機重試，包括 backend 重用 ID。沒有廣泛改寫 listing。coalescing 也由 session 物件擁有；替換連線的明確 Refresh 立即取得自己的 promise，舊列檔不能清除新 promise。
- 取消 event polling 不取消已接受的同 session 獨立媒體列檔或預覽；該列檔仍可完成，但不啟動後續 event 恢復。斷線／替換連線則使其失效。

所有恢復都限於讀取。沒有新增 endpoint、Start／Stop／capture／setting 重送，也不把未收到確認的 REC 結果當成已確認。

## 本地驗證

環境：Node `v24.19.0`、npm `11.9.0`；以 CPU 8 限制小型 Node 正確性檢查，沒有效能結論。

最終 `event-state-recovery.test.js` 共 69 例（原事件恢復 43 例，加上明確刷新所有權 26 例），涵蓋：獨立 snapshot 欄位與媒體失敗；poll／snapshot／media 的精確 1／2／5／5 秒排程及成功後重設；各 busy owner；新 interaction／手動刷新／未確認錄影證據；poll、snapshot、busy、backoff 階段重啟；同 ID 連線替換；空事件及不需媒體刷新控制；獨立預覽，以及同 session 的 scope／interaction listing 重試。新增所有權案例另涵蓋 manual／quiet 的兩種啟動與完成順序、manual 成功／失敗的終態回饋、兩個 manual、退役 session／interaction／recording owner、錄影未確認警告、quiet 單獨執行與空事件不改變回饋。每例直接執行從產品來源擷取的函式，API fixture 拒絕任何非 GET。

最終命令：

- `taskset -c 8 npm run test:modules`（於 `bridge`）：結束碼 0。全部 28 個宣告模組依序完成；其中 18 個 Node test runner 模組合計 681／681 通過，0 failure／cancelled／skip；其他 10 個既有 assertion 腳本也全部成功。681 不是所有不同格式腳本的合併 case 數。
- `node --check bridge/open_eos_bridge/static/app.js`：結束碼 0。
- `node --check bridge/tests/event-state-recovery.test.js`：結束碼 0。
- `git diff --check`：結束碼 0。

既有 `capture-review`、`media-refresh-preview`、media selection、batch 和 auto-import controls 都包含在宣告套件內。新增測試沒有新框架、安裝、下載或瀏覽器依賴。

這是可控合成 API／timer 下執行真實 PC 函式的軟體證據，不是 Bridge HTTP、瀏覽器 DOM、實體相機、真網路斷線、Canon 列檔時序或硬體耐久性驗證。未結束的讀取沿用既有 transport 行為；本修正沒有新增 client timeout，測試不涵蓋永不結束的網路請求。此文件不宣稱 exact-head CI 或跨平台完整產品已通過。

## Release Assessment

### 首輪 CI 與靜態契約測試修正

head `8a6cc8d4f95d8847ee03c879fc3e2ebba7880de7` 的正常 CI `38391774253`：browser control、Windows bundle、JVM／APK 與 secret scan 通過，但 desktop bridge contract 在 `tests/test_static_ui.py:271` 失敗。該檢查仍要求舊字串 `await refreshSession({ quiet: true })`，未包含本批新增的 `eventGeneration: generation` 參數；原失敗保留。

最小修正只將此一字串期待對齊帶有 event owner 的確切 call，所有 API path、authentication 不持久化及其他原斷言保留。用標準 Python 載入原測試檔並逐一執行全部 6 個無參數 test 函式，6／6 通過；這不是完整 pytest／backend suite 或新 exact-head CI。該靜態修正當時沒有更動產品 JavaScript，完整 28 模組收據對應同一來源。這段記錄當時本地驗證，不以它取代後續 exact-head CI 結果。

### 後續 browser 等待失敗與明確刷新回饋

head `d32b3c58ee297b8d350d928ffb7031232f9bebf8` 的 browser job 在 `ccapi.browser.test.js:1286` 等待手動 Refresh 後的 `Ready` 超過既有 30 秒。現有 failure artifact 只有先前步驟的 6 張截圖，沒有失敗當下 DOM、label 或 request ordering；該次 browser 根因仍為 UNKNOWN。先前 `8a6cc8d` 的一次 browser 成功也不能證明此路徑穩定。

獨立受控重現則確認一個真實競態：manual Refresh 先送出，quiet Refresh 隨後取代 `refreshRequestOwner` 並先完成；quiet 成功不寫 `Ready`，manual 回應因失去所有權而退出，實際 `setOperationState` 保留 `refreshing`。manual 最後成功或失敗兩種情況，在未改 `d32b3c5` 產品碼前皆重現，2／2 紅測。擴充原 43 例與相關控制後，基準 65 例為 8 失敗／57 通過，並包含真正 `startEventLoop` 路徑；這建立機制證據，不補造 CI 當時的執行順序。

最小修正保留尚未結束的明確 Refresh 優先權，限定同 session 物件、同 interaction generation、同 recording recovery owner。quiet 先回傳 `false`，事件提示依原退避保留；manual 完成或失敗照原規則發布自己的回饋。`finally` 只清除自己的 request owner；新 manual、已退役 session／interaction／recording owner 不被舊請求阻擋。未收到確認的 REC 責任仍不由此解除。

本輪沒有修改 browser 的 `Ready`、能力檢查、等待時間或相機業務斷言，也沒有跑 browser 或 CI。完整 28 模組通過，並以標準 Python 重新載入及執行原 `test_static_ui.py` 全部 6 個無參數控制，6／6 通過。新增控制不涵蓋永不結束的 fetch；沒有新增 client timeout，也不宣稱該類請求可自行恢復。browser 故障與完整候選的驗收仍須後續實際結果。

沿用原 PR 的發布基準 v0.12.0 Development Preview；本修復是向後相容的可靠性 patch。原 PR 的完整媒體新能力仍屬 minor，不能以此降低整批影響。版本、既有 release HOLD、必要安全／驗收 gate 及真機證據界線不變；本地狀態為 implemented，PR ready 仍須推送後的精確 head `ci-complete` 成功。
