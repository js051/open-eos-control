# Bridge：三組 focus command response-body deadline

## 範圍與來源

基準為 dev `f9254312ba1d28ff227822cd97a3f2064148f88a`，tree `5680afd8e568e461a80c380fd03b9c2e65b77fed`。本批只改以下八檔：

- `bridge/open_eos_bridge/ccapi.py`
- `bridge/tests/test_ccapi_focus_ack_receipts.py`
- `bridge/tests/test_ccapi_control_body_budget.py`
- `bridge/tests/test_ccapi_status_body_budget.py`
- `bridge/tests/focus-drive-ownership.test.js`
- `bridge/tests/focus-interaction-ownership.test.js`
- `bridge/README.md`
- 本驗證紀錄。

修正既有三組普通 one-shot 命令的有限 response-body 消耗：`POST /shooting/control/drivefocus`、`PUT /shooting/liveview/afframeposition`、`POST /shooting/liveview/clickwb`。沒有新增相機命令或 capability，也不更動 recording、timeout 預設、redirect、strict-control、stream、workflow、Apple、Android 或 KVM。

來源可達性是現有 HTTP focus endpoint → `SessionManager.camera_mutation()` → `BulbRequests.mutation()` 的原 execution lane → `CcapiSession.drive_focus()`／`tap_focus()`／`click_white_balance()` 的 provider `RLock` → `_command_ok()`／`_request_ok()`／transport。這些普通呼叫不設定 `strict_control`。原本只檢查完整 ACK，成功 body 仍以一次 `read(max_bytes + 1)` 讀取；有限 slow-drip 能持續滿足 socket idle timeout，卻超過 request 的總 body 預算。

普通 Live View Stop 經 `MaintenanceLiveViewRequests.stop_ordinary()` 取得同一 execution lane，再取得同一 provider lock。DELETE 經 `SessionManager.delete()`、`finish_retirement(session.close)` 連到原 session 的 `close()`。saved Live View Stop 在 Start 前已保存，逾時不能把該責任交給新 session 或清掉它。

## 最小產品變更與 ACK 語意

`UrllibCcapiTransport.request()` 的原 `control_resource` 判斷只增加迭代既有 `_FOCUS_CONTROL_ACK_ROUTES`。沿用 `_read_ccapi_body_with_deadline()` 及既有錯誤映射；不進入 `_maintenance_controls()`，也不將 focus 改成 strict-control。除一段說明註解及這個 selector 外，production bytes 不變。

deadline 在 open 前以原 timeout 建立；read 前後都在 `>= deadline` 拒絕發表成功。預設仍為 15 秒，reader、byte ceiling、HTTP status、redirect、framing、receipt checks 與 Stop ownership 沒有重寫。

- **及時完整 ACK**：原先接受的空 body、plain／JSON、200／202／204 等契約維持。既有 206／Content-Range partial-response 拒絕維持。
- **晚到完整 ACK**：原 baseline 可能在有限慢速 body 結束後仍回報 accepted。候選改走原 HTTP 502 `CCAPI_UNREACHABLE`，訊息為「The camera CCAPI response was interrupted; command completion is unknown.」。這段映射已存在，不是本批新增 UI enum 或「確定失敗／沒有執行」的宣告。
- **晚到 partial body**：可能在既有 partial-response 檢查前先因 body deadline 得到相同未知結果；不得轉成 accepted。已收到非 2xx 的明確 HTTP status 仍保留，即使其 optional diagnostic body 逾時而留空。
- **沒有 replay**：Bridge 與 browser 均不自動重送，也不在未知結果後顯示 accepted feedback。合成 peer 先記錄可能已套用命令，再慢慢送 ACK，因此 timeout 不能當成安全重送的證明。
- **現有 UI 界線**：browser 保留 server code/message，以原 generic error feedback 呈現；tap 的 failure reticle 不是 durable unknown-state contract。本批不新增永久 focus retry fence，settlement 後的另一次明確使用者操作仍可能發送命令。

modern recording 是既有負向對照，不是本次缺口：`_perform_recording_outcome()` 已透過 `_maintenance_controls()` 設定 `strict_control`，body budget 及 10 秒 timeout 均不變；legacy recording 同樣未改。

## 可反駁驗證與當前狀態

2026-10-20 23:42 UTC 的 bounded offline gate 已完成：

- 原 38 個 focus cases 與 18 個原 control/status negative controls 全部 PASS。原 35 個 receipt/routing cases 的完整 function AST、原 Peer/run helper AST 與所有原 JS assertion lines 保留。
- 同一組 107 個新 focus cases 對凍結 `f9254312` production 取得精確 **42 個預先指定 assertion RED／65 PASS**，沒有額外 failure、error 或 skip。實際 producer-ordering cases 到達預期的 late-success receipt assertion，不是 fixture wait failure。
- 候選 **808 個 focused Python PASS**（含全 107 個 focus cases），另 **21 個既有 status contracts PASS、78 個 Node cases PASS**。Node 使用原 production API/parser/handlers，不是 browser visual 或 accessibility proof。
- 原完整 `ruff check --no-cache . ../scripts/validation`、`git diff --check`、八檔 hash/source-integrity guards 均 PASS。Production 還原 selector/comment 後與基準 byte-for-byte 相同；兩個額外 negative-control 檔還原唯一 method tuple 後也與基準 byte-for-byte 相同。
- 四次 collection-only setup failure 依序指出 baseline runtime 缺少 static assets、tests.fakes、JPEG fixture 與其既有 JPEG contract JSON；全部保留，不當成產品 RED。只補上 unchanged/hash-checked dependency closure，沒有改 source/test 或重設時限。
- Node 實際印出 `ℹ tests/pass` 而非 parser 原期待的 `# tests/pass`，因此 receipt verification 曾失敗。保留該證據，只修正既有輸出 prefix 辨識；78/78 原結果未變，沒有重跑 suite、調整 expected counts 或放寬 assertions。
- 從首次啟動起累積 **250.54 秒**，仍在原 295 秒限額內；2 CPU affinity，最大 sampled process-tree RSS **165,788 KiB**（逐 process RSS 加總，shared pages 可能重複計入）。結束 free space **2,248,159,232 bytes**，保留 2 GiB floor。沒有下載／安裝、camera 或 native runtime。

1. 保留原 focus receipt file 的 38 個基準 cases：35 個 receipt/routing cases 語意不變；最後三個 no-clock policy cases 刻意由新 deadline cases 取代。先獨立執行原 38，再以相同新 tests 對凍結舊 production 驗證預先列出的失敗 IDs 與 assertion categories，不能把 import、fixture、timeout-wrapper failure 當產品反例。
原 control/status regression 各有一個 `POST /shooting/control/drivefocus` no-clock negative control；本次精確政策改變後，改以同 suffix 的 `PUT` 驗證真正未選取的方法。兩檔都只替換該 tuple 的 method，原 assertions、其他 controls 與 case 數目不變，原 baseline copies 另行保留。不能把這兩個刻意更新的 policy cases 當成無關產品回歸。

預先指定的 42 個 RED 為 policy selector（3）、finite slow success（3）、exact／late boundary（6）、open 消耗預算（3）、late rejection diagnostic（3）、API rejection diagnostic（3）、late partial ACK（9）、三種 Stop／close producer 次序（9）與 failed Stop recovery 前置結果（3）。每個 category 的完整 IDs 與 failure marker 先封存，再執行基準。

2. 三組 method/path 各自以有限 localhost HTTP peer 驗真正 urllib slow-drip；另以可控 monotonic clock 驗 exact deadline、late bytes、open 消耗預算、原 HTTP status、partial ACK、redirect 與不相符 method/path。peer 僅合成資料，沒有真相機或外部網路。
3. 真正註冊 session／focus HTTP endpoint 發送一次命令。test-only transport observer 在 body close 後、原 request producer 離開前設 barrier；既有 Stop、DELETE／close 必須等待。放行後觀察 body close → original producer exit → same saved Stop dispatch，保留失敗 Stop 的原 owner／recovery 與不重播命令。
4. JavaScript tests 使用 production `api()` 與原 focus handlers，傳入相同 HTTP 502 error envelope，驗一次 request、原錯誤資訊、沒有 accepted feedback／automatic retry，以及 stale owner 不覆寫 replacement。原所有 tests/assertions 保留；沒有 production UI 改動。
5. 同時跑現有 control body／partial ACK、status body、focus receipt、legacy REC、Bulb ownership／recovery、shutter AF、recording lifecycle／readback regressions，原完整 Ruff command 與 source／hash／diff guard。

離線 gate 上限為累積 295 秒、2 CPU、768 MiB sampled process-tree RSS、2 GiB shared disk free floor，serial 使用既有 pytest／Node／Ruff。若 setup 修正，原 deadline 與失敗證據保留，不安裝或下載工具。不以 synthetic I/O 或 Node handlers 冒充真相機、browser visual／accessibility、Apple 或 Android 驗收。

## 保留界線與交付

這只是指定命令的 cooperative body budget，不是全 HTTP cancellation 或固定 Stop latency：DNS、connect、headers、某一次 blocking read、lock admission、逐 request 累積時間均未變成 hard deadline。若底層讀取永不返回，本批無法強制中止它。Stop 必須 join 原 producer；逾時不能犧牲此安全條件。

既有 saved Stop ACK／rejection／recovery 與 owner identity 不變。不能把 received ACK 的 deadline policy 變動解讀成相機拒絕，不能因未知結果自動 replay，也不宣稱物理 AF、白平衡或 Live View 已停。

本地驗證與獨立 diff review 已通過；normal hooks 及 fresh dev HEAD／無 open head:dev PR 檢查仍須在交付前完成。上述八檔按既有 path filters 只觸發 Desktop／Windows，約 12–20 分鐘另加 queue；不修改 workflow，也不觸發 Apple、Android、KVM。遠端結果在實際 terminal 前保持 pending，屬 supplemental dev validation，不等於 PR `ci-complete`、main acceptance 或 release。

先前基準 `f9254312` 的 Desktop run `39509772754` 與 Windows run `39509772758` 已於 2026-10-20 22:52:20 UTC 全部 terminal success。Desktop log 包含 3,875 個 Node PASS 與 2,987 個 pytest pass markers（後者由完整 progress log 計數，非 JUnit summary），另 Windows executable loopback 驗證通過。這是前批來源的證據，不預先證明本候選。

Apple picker 的既有 DOCWeakProxy crash 仍未解決；獨立 RTP accounting 修正已有 native App proof。Android native color 仍待另行明確批准的 device/KVM 範圍。這些不因本次 body deadline GREEN 而關閉。

Release impact 建議為 `patch`；本批不改版本、不合併 main、不建立 tag 或 prerelease。後續完整 release delta 仍須重新評估。
