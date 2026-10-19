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
