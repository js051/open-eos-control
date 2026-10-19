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
