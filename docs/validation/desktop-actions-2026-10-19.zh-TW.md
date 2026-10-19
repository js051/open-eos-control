# Linux Desktop Bridge 原瀏覽器／契約補充驗證

- 此 dev-only 入口完整復用原 `android.yml` 的 `desktop-bridge` steps；原 PR 與必要 gates 不變。
- 全歷史秘密掃描與 actionlint 通過後，沿原 Python3.12／Node22／npm lockfile（Playwright1.62.0）安裝官方測試工具。
- 執行原 Ruff、JS syntax/modules、十組 Chromium browser scripts、完整 pytest，最後建 wheel/sdist；原同 repo 套件與合成 screenshots artifacts 保留。
- Browser fixtures 使用 loopback fake runner／既有 simulator，不存取真相機、攝影機或使用者電腦；不新增 KVM、憑證或安全設定。
- 新入口不重跑 Apple 或 Windows workflow；本地已核原 job steps/action pins 全部一致，首輪真執行結果待補。
- 本輪不宣稱硬體 provider、三平台完整對齊或正式發布完成。

## English summary

This supplemental dev-only workflow reuses the original Linux desktop job without dropping lint, module, browser, contract, or packaging checks. It uses the existing locked Playwright dependency and loopback synthetic fixtures. Original PR gates remain unchanged. Native Chromium and full Python results are pending; the workflow does not publish a release or validate physical cameras.

## 首輪執行與原 lint 阻塞

- Source `dad1f094dac5ecc86cef1d7322ea0a5ac8e88594`，[run39260670090](https://github.com/js051/open-eos-control/actions/runs/39260670090)。全歷史掃描、actionlint、Python／Node／Chromium 官方安裝均通過。
- 原 lint 真執行報 540 項：E501 293、E701 71、E702 132、I001 16、E401 8、UP017 6、SIM117 2、E402 10、F811 2。browser／pytest／package steps 尚未執行，不算產品案例失敗或通過。
- 37 個既有 Python 測試／驗證檔案僅格式整理；沒有排除檔案、ignore 規則或改變驗收斷言。長字串拆為相鄰 literal，原值逐字相同。
- CI Ruff0.17.1 與本地官方 Ruff0.14.14 重現相同 540 項；本地 binary SHA256 `d3c2fbc6bed57fd6c7395ec076a9cbe9a48926ea68872bc530f37c08bb972050`，符合原 `ruff>=0.6.0` 範圍。
- 本地整理後原 Ruff 命令通過、223 個 source guards 通過、diff check 通過。逐檔 AST 對照在僅正規化 import 次序／去重、`datetime.timezone.utc` 等價別名 `datetime.UTC`、相同順序巢狀 with 合併後完全一致；import 集合相同。
- Gphoto targeted pytest 本地缺 pytest，未執行，保留由原完整 CI gate 驗證；不以 AST 或 source guard 冒充 Python runtime 全綠。

The first native desktop run stopped at the unchanged lint gate with 540 findings; browser and Python runtime suites did not run. The scoped formatting correction preserves assertions, literal values, and normalized executable AST. Original lint rules remain enabled. The follow-up CI result is pending.

## 第二輪：lint 收斂，Chromium 實際 CSP 錯誤

- Source `282648ac9997b9576baf1d8ecd4d13f2baaf8a8f`，[run39262378403](https://github.com/js051/open-eos-control/actions/runs/39262378403) 的原 Ruff、JS syntax 與全部 module scripts 通過。
- 第一個 `local-video.browser.test.js` 原 `pageErrors == []` 斷言收到 11 個同樣 inline-style CSP 拒絕，hash `sha256-lmnlZV/+SXaJRvUJl58if6AWgpyfeGrlvujZ4tCEOgY=`。其餘 browser scripts／pytest／package 尚未執行。
- 保留 `style-src 'self'` 與全部錯誤斷言，不加入 unsafe-inline、不過濾 error；新診斷只於既有 console error 輸出固定 source 類別與 line/column，不輸出完整 URL。
- [Playwright 1.62 screenshotter 原碼](https://github.com/microsoft/playwright/blob/v1.62.0/packages/playwright-core/src/server/screenshotter.ts) 核對未證實本輪錯誤來自 screenshot，尚不能據相同 hash 判定產品或工具根因。
- 同 source 的 Windows 原生封裝再次通過（run39262378065）；Apple21正常執行中，未為本診斷取消或重啟。

The actual Chromium journey exposed repeated inline-style CSP errors. The strict error assertion and production CSP are unchanged. A bounded source-category/line/column diagnostic is added to establish the origin; this is not yet a product fix.

## 第三輪根因定位與最小修正

- Source `a3314b04641cade1e5249b9b8bd16029ae7e7b45`，[run39263435123](https://github.com/js051/open-eos-control/actions/runs/39263435123) 的固定來源診斷全部指向 `app.js:6609`（console location 為 zero-based line6608）。
- 真根因為 `pollLiveView` 把前張 image 全部非 src 屬性複製到新 decoded image，其中含原 CSSOM 幾何序列化後的 `style`，`setAttribute` 重新解析遭 strict CSP 拒絕。
- 最小產品修正僅排除 `style` 屬性複製，原 `renderMonitoringFrame → applyLiveViewLayout → positionMonitorLayer` 仍以 CSSOM 套用新幾何；不放寬 CSP、不重新指定 src、不改 owner／decoded-frame admission。
- 新 production-function 回歸實際先紅（多出 style setter），修後 23/23 通過；驗證 id/class/AX 複製、同一 decoded image、以及新幾何。真 Chromium 原空錯誤斷言待後續 gate，不將 Node synthetic CSSOM 當作瀏覽器 CSP 驗收。

The bounded diagnostic identified production attribute copying as the source. The correction skips serialized style replay while retaining fresh CSSOM layout and all frame-ownership guards. The new regression failed before the fix and passes afterward; native Chromium validation is pending.

## 第四輪：真 Chromium CSP 通過，設定旅程同步候選

- Source `30c7c27dc17f202f8dc5e4f7c2abc4bad1c1abed`，[run39264520372](https://github.com/js051/open-eos-control/actions/runs/39264520372) 已通過原 `local-video.browser.test.js`，包含原空 console errors 斷言；CSP 修正取得真正 Chromium 證據。
- 後續 `ccapi.browser.test.js:1439` 的時鐘按鈕被仍開啟的設定 dialog 擋住。原 ISO 流程只等 peer ISO=1600／事件送達，便外部修改 ISO=3200，沒有等第一筆 scoped outcome 及 editor 完成。
- `apply_setting_outcome` 寫入後另做 scoped GET，再產生 matched/mismatch receipt；peer 寫入／事件成立不能證明這個讀取已結束。因此有可成立的測試競態，但本輪沒有 response receipt，尚未證實它就是此次根因。
- 最小候選在原外部變更前明驗唯一既有 POST response 的 HTTP200、response-received、matched、setting.value=1600，再等待 dialog 正常關閉。沒有新增寫入、強制關閉、force click 或改變 timeout；若 receipt 本來失敗，會更早明確失敗。
- 原外部事件更新與 clock/AF/後續驗收全保留。此同步候選仍待真正 Chromium；其他 browser／pytest／package gates 尚未到達。

The original live-view browser journey now passes under the unchanged strict CSP. The next CCAPI journey is blocked by an open settings dialog. Its external ISO update previously started before the scoped transaction and editor were known to have completed. The candidate adds exact successful-outcome assertions and normal dialog completion before that external update. The race is plausible from the implementation, but its occurrence in the failed run is not yet established; native validation remains pending.

## 第五輪：ISO 成功前提通過，維護前置待定位

- Source `ff9cb9feb361cd264f08955573da025ec6e97ba8`，[run39266289706](https://github.com/js051/open-eos-control/actions/runs/39266289706) 已通過新增 ISO exact receipt／正常關窗前提，以及後續 clock、AF、錄影、外部事件與媒體詳情旅程。
- 更後段 sensor-cleaning 等待失敗：peer Live View Stop=1、event DELETE=1，但 sensor cleaning count=0。未取得各 Bridge control HTTP status，因此尚不能斷言是產品 admission、前置回覆驗證或 fixture。
- 最小失敗診斷只收三個固定 maintenance route 類別及 HTTP status（最多16列），原 peer predicate／等待預算與 error 完整保留；不記 URL、headers、body，不增加相機命令或重試。
- Windows 同 source 全過；Apple21仍在原 App/UI 執行中，未被取消或重啟。

The exact ISO receipt and normal editor completion now pass in Chromium, followed by the original control and media assertions. The next failure is maintenance admission: preview and event monitoring stopped, but sensor cleaning was not dispatched at the peer. Fixed-category response-status diagnostics will distinguish the preceding control stages without weakening the original assertion or issuing extra commands.

## 第六輪：維護 HTTP 分支已縮小

- Source `33f5427310f38aafd137943d78e4761abeee5a39`，[run39267392824](https://github.com/js051/open-eos-control/actions/runs/39267392824) 的固定診斷為 LiveView Stop HTTP200、event DELETE HTTP204，沒有 sensor-cleaning outcome 回覆，peer cleaning 仍0。
- 補充僅失敗時讀既有 UI history 的 Stop confirmed/unknown/cancelled Bool，及既有 diagnostics 的四個固定 transport error 類別索引；未知只記 -1。三個 route 的 Bridge response headers 只記四個 framing Bool，不輸出值或相機上游 headers。
- 診斷 DOM 查詢若失敗只記 unavailable=true，仍重拋原錯誤。沒有新增 request、重試、相機命令或 timeout。

The sixth run confirms successful HTTP statuses for the two preceding Bridge control routes, but no cleaning response. Additional fixed booleans distinguish strict representation validation from subsequent admission. They describe Bridge responses only. A failed diagnostic query cannot replace the original test error.

## 第七輪：清理中的 route handler 遮蔽原失敗

- Source `874ba55b5c7b4fcb753616840a00a9485f33ccdb`，[run39268510760](https://github.com/js051/open-eos-control/actions/runs/39268510760) 尚未到 maintenance 診斷，main journey 清理 browser 時仍有 `/capabilities` route.fetch 執行，拋出未處理 TargetClosedError（原1318行），原始旅程錯誤未印出。
- 最小 harness 修正：成功 close 前與失敗清理前，等待該 main page 原 route handlers 結束；先保留／重拋原 scenario error，cleanup error 也保留且不得變成功。
- 使用 [Playwright page.unrouteAll](https://playwright.dev/docs/api/class-page#page-unroute-all) 的 `behavior: wait`，不使用會忽略後續錯誤的 `ignoreErrors`。原互動、相機命令、timeout 與斷言全留。

An in-flight capability route handler failed during browser teardown and concealed the primary journey failure. The harness now reports that primary error and drains its existing page routes before closure. Cleanup failures remain failures. This correction does not establish the underlying maintenance cause.

## 第八輪與空 204 stream 反例

- Source `9e5c8d786021d2bb8be1d6a1d1ec97f7212c0a21`，[run39269514812](https://github.com/js051/open-eos-control/actions/runs/39269514812) 保留了原 maintenance 失敗；沒有再被 route teardown 錯誤遮蔽。
- event204 的固定 Bridge framing 診斷為空長度合法、無 transfer encoding／content range、identity encoding，UI Stop preparation=Unknown 且未取消。尚未取得瀏覽器 body 型態本身。
- [Mozilla Response.body 文件](https://developer.mozilla.org/en-US/docs/Web/API/Response/body) 明載瀏覽器對無 body 的204可能仍提供 ReadableStream。現 transport 只承認 body=null，對實際 EOF-only stream 誤作 JSON；新增 exact fake response 反例真紅 `Unexpected end of JSON input`。
- 最小修保留原 framing／status 守門，對非 null stream 只於真正 EOF 且0 bytes才接受；任何payload byte立即拒絕。未讀EOF不能承認，stream失敗不能變成功，未明確指定空status的回覆仍走原JSON驗證。
- 471項相關transport tests通過，含空格／JSON／換行／非零或非canonical長度／TE／range／gzip拒絕、pending EOF與read error。這是原碼與host證據，真Chromium maintenance仍待後續驗證。

The real browser failure retained valid Bridge framing but an unknown Stop-preparation outcome. A documented browser compatibility edge is now reproduced locally: an EOF-only stream on an expected empty 204 was incorrectly parsed as JSON. The correction requires actual zero-byte EOF and preserves all framing and nonempty-payload rejection. Native Chromium confirmation remains pending.

### 第九輪：204 真瀏覽器修復證據與連線 fixture 邊界

- `0abc8f63fc0c3ac84d03bd1b6a3f009f243d8fbe` / Actions `39270426756` 的真 Chromium 已通過原 sensor-cleaning、顯式 restore、sleep 暫停及事件退場 assertions；先前 EOF-only 204 的修正獲得實際瀏覽器證據。
- 後續 connection recovery 旅程在第 1136 行等待重新 Connect 超時。原七類錯誤共用同一頁，卻要求 generic HTTP 503 與網路失聯都解除 unknown-session gate，與現有 `sessionCreationRejected` 明確契約衝突。
- 將七類錯誤各放入新合成 context；兩個 unknown 案保留禁重試、編輯 token 仍禁用、精確單 POST 與零其他 mutation。其他確定 rejection 才等可重試；原掃描晚回覆、取消、成功實際 Bridge connect/disconnect 與雙語 guidance 均保留。這不是宣稱重新載入頁面能清理真相機。
- 本地 JavaScript syntax、diff check 與原 connection-guidance 104 項通過；新增 browser 結構尚待下一輪真 Chromium。
- 同 SHA 的 Windows `39270426818` 完成原生 exe 建置、啟動驗證及產物上傳。

### 第十輪：維護旅程的顯式 session 退場

- `fde902d0c98752201d130ec1c8d8d881c3e970ed` / Actions `39271697417` 已再次通過 maintenance 原流程，但掃描旅程之後的成功 Connect 在第 1121 行超時；七個獨立錯誤情境尚未執行。
- source 明確顯示 `suspendMaintenanceForLifecycle`／`beforeunload` 在 maintenance hold 時只退本地 receiver，刻意不送 session DELETE。原主旅程 sleep 後直接關 context，留下同相機 session；`SessionManager._register` 依法拒絕重複 owner。瀏覽器關閉不能當作已完成這項清理。
- 在原 sleep／Stop／event／零 page-error assertions 之後，用真 UI Disconnect 完成退場；先註冊 exact maintenance session DELETE，驗 HTTP204 空 body、connection view 回復與無 error，再關 context。產品 guard、後續 Connect 和原時間上限不變。
- 本地 syntax／diff check 與獨立窄審通過，新增真退場仍待下一輪 Chromium。Windows 同 SHA 的 `39271697588` 全過。

### 第十一輪：連線恢復通過，修正舊刪除回覆 fixture

- `cc69bfa7637d133dc8efda8ae3bdc7304bcaee73` / Actions `39272672982` 真 Chromium 通過 maintenance 顯式退場、英／繁中掃描與成功重連、兩語各七類拒絕／unknown admission，以及 capability disconnect/reconnect；Windows `39272672858` 全過。
- 下一個媒體刪除 ownership 案在等待 DELETE 派送處超時。fixture 無來源 ID 與現 `sourceFor` 拒 unknown 契約衝突；原 JSON consumption marker 也不對應現 stream 收據實作。
- 單一 synthetic item 改用合法 `gphoto2:` ID 與 exact URI 編碼 route；保留原200{}、500作不確定回覆，另加204空回覆及403明確拒絕。只在此測試忽略該 DELETE 的 abort，刻意交付退役回覆，驗真正 owner fence；不改產品 transport。
- marker 在真 stream EOF 後排入下一 task；若真204的body為null，只證 fetch 已返回與後續 continuation 可執行，不宣稱讀取不存在的stream。原B同ID媒體、dialog、enabled、diagnostics與零重送斷言完整保留。
- 本地 syntax／diff check 與565項原media-delete module測試通過，四個更新後真browser案例仍待下一輪。

### 第十二輪：CCAPI／Bulb 整支通過，capture-review 三項 fixture 修正

- `831a733d0d4fe5800c4c688bb0021dfe744ad03c` / Actions `39273766245` 真 Chromium 已通過完整 CCAPI browser（四類 late DELETE、雙語日期／錄影 review／原檔、held AF 等）及後續 Bulb/short-control recovery browser。Windows `39273771403` 全過。
- capture-review browser 執行到末尾，共三紅：not-ready recovery 的舊文案 regex；missing-proof／premature-entropy 已成功拒絕後，恢復 helper 卻要求最初二張圖與 picker=0。
- 原失敗圖顯示完整現行 `No new media was observed...` 狀態，改用 exact 文案；仍保留原四次查找／重查四次、單 capture 與無新 write。兩個 JPEG 失敗 artifact 均證 gallery COMPLETE、三張32×24縮圖、latest=SIM_0003、picker=1、單original且停止。恢復 helper 現顯式傳這個精確基線；預設二張／oldA／picker0不變，第二個明確快門才期待capture2。
- 原 JPEG 零輸出／failed history、恢復不重試舊ID、無自動快門與新照片才成功等斷言保留。原12秒與20秒等待未延長。
- 本地 syntax/diff與抽取原helper的隔離 fake-DOM red→green 檢查通過：舊helper拒三張基線，新明確三張基線通過，原二張預設仍過且拒未指定三張。這不等同真瀏覽器；新整批仍待 Actions。

### 第十三／十四輪：查找雙擊錯落半按的真產品缺陷

- `2ee2195520cea868a31d8557bef2642602c0ef47` / `39276025076` 已真通過兩個 JPEG 拒絕後恢復旅程；唯讀重查卻多出一次 `/shutter/half-press` POST，原零寫入斷言失敗。Windows `39276025176` 同 SHA 全過。
- `0bd2fd7a3f66b2296c4ee560baea7837e22a07cc` / `39277837013` 保留原雙擊與失敗，加入三個固定 controls 的 bounded 事件證據。首次 click 在 retry `(1275,738)`、top714；SEARCHING 文案縮短後，第二次相同座標的 pointerdown/up/click(detail2) 命中已上移至top720的半按按鈕，並實際發出 POST。新增即時零寫入斷言也真紅，排除最後一次 retry 才出錯的假說。
- 產品候選在這個 review lifetime 保留狀態文字與 retry 槽的實測最低高度，包含現行字級／語言；IDLE 或無 session 時重置。READY 仍隱藏且禁用 retry，但保留槽，避免相機 mutation 控制移入原觸點。沒有 sleep、改成單擊、吞掉半按事件或削弱能力／owner guards。
- 新原 renderer 回歸先紅（未保留63px），修後與 media-refresh/preview 共455項通過；新增真 browser 幾何斷言仍待下一輪 Chromium。原查找次數、單次 capture、零額外 write 與雙擊完整保留。

## Desktop26 實際進展與合成 maintenance peer 邊界

- `71628206da814aaa7c176ba28e110036d8ec8dd8`／run `39336454166`：22:46:07 UTC 前已通過原 metadata、12 upload、13 delete、10 capture configuration 旅程，隨後9個 ordinary setting 案含取消／重連／繁中也通過。configuration 的 retire 現沿原先 Cancel 解開 busy，再明確 Disconnect，原 late gate 與單次寫入保留。
- 最後 maintenance 的 clean／sleep 原旅程斷言通過，但 sleep 的測試退場得到409：普通 browser peer 是 `GPhoto2Engine(FakeRunner)`，其維護能力與outcome由瀏覽器fixture合成；底層真 gphoto provider沒有 local-only `maintenance_retire`，原 manager 正確拒絕。不能把這當產品 gphoto 已支援維護或允許 generic DELETE 代替安全退場。
- 新增僅 maintenance browser 使用的專用合成 server factory，固定 `FakeRunner`，idle才允許原session local close，並核零fakecamera命令。存在 short shutter、Bulb、live view或stream責任即拒絕，沒有Stop／重送。普通browser server與production不變。
- 新3個真TestClient方法涵蓋4種責任subcase、exact204空body／reservation釋放、重複404、拒退後owner仍在，以及普通gphoto仍409；本地通過。這是合成peer契約驗證，完整browser、後續Python與release candidate建置仍待新CI。沒有真相機驗收。

## Desktop 30：下載精確長度與失敗 USB owner / Exact download size and failed USB ownership

- Desktop 29 (`39340743281`, `213b40f34b86dca8235eb1872717b8098d6e4d40`) 全部原 Chromium browser suites 在 23:26:16 UTC 通過。後續 Python contract 在 23:29:02 出 7 紅，wheel/sdist 尚未執行，不能宣稱完整 Desktop gate 綠。
- All original browser suites passed on Desktop 29. The subsequent Python gate reported seven failures; packaging did not run.
- 4 紅確證產品問題：普通 gphoto `list-files` 將 13 bytes 上捨入為 1 KB，download 沿用 cache 的 1024 作精確長度，正常 13-byte EOF 被報截短。Playback 先取得 `media_info`，所以相同資料不受影響。修正普通 camera download 在串流前讀取同 folder/name 的嚴格 primary-file byte receipt，保留完整 stage 長度比較；未知、零、重複、非 byte 單位、錯 identity／UTF-8 均拒絕，沒有以 rounded estimate 或未知 0 繞過檢查。
- Four failures exposed a product defect: rounded listing sizes were treated as exact download lengths. Downloads now obtain a fresh, exact positive primary-file size with strict identity/grammar before opening the stream. Exact length validation remains unchanged; malformed metadata cannot start a transfer and truncated data still fails.
- `GPhoto2Session._lock` 是 `threading.RLock`（1390），允許同 thread 進 `_media_info`；取消前後檢查只讀 event，不取得另一把 lock。新增 read-only metadata 指令保留原 60 秒 provider deadline；這不是可立即中斷 CLI metadata 的新承諾。
- The nested metadata call uses the existing reentrant session lock. Cancellation checks read the event and add no lock inversion. The existing 60-second metadata command deadline remains; immediate interruption of that command is not claimed.
- 另外 3 紅為舊 ASGI 測試與目前 quarantine 契約不符。未確認 Stop 的 session DELETE 仍 502、舊 session 仍 404；新的 local USB session 必須 409 CAMERA_BUSY，不能繼承／重送舊 Stop。shutdown 的 ENGINE_COMMAND_FAILED 明確保留並驗證，ExitStack 精確捕捉該預期錯誤，不以一般吞錯遮蔽 request assertions。
- Three stale tests expected replacement after an unconfirmed release. They now assert admission quarantine, no additional CLI command, and the precise persistent shutdown error. Production retirement safeguards are unchanged.
- 凍結本地 gate：974 passed（17.63 秒），含原取消／queued／playback 矩陣、gphoto、Bulb／短控制、API／metadata；Ruff、diff check 過。使用與專案範圍相符的官方 pytest 8.4.2，共用 wheel hash 驗證後隔離到 EOS cache，無重下載。原紅與修後 logs 保留。JPEG 全檔本地收集因缺 PyAV 未執行，未重試已拒下載；完整 codec 與 package 仍交原 Actions 驗。
- The frozen local slice passed 974 tests plus lint and diff checks. Native codec tests could not collect locally because PyAV is unavailable; the denied download was not retried. Complete CI and package results remain pending.
