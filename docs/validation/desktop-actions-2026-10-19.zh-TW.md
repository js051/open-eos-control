# Linux Desktop Bridge 原瀏覽器／契約補充驗證

- 此 dev-only 入口完整復用原 `android.yml` 的 `desktop-bridge` steps；原 PR 與必要 gates 不變。
- 全歷史秘密掃描與 actionlint 通過後，沿原 Python3.12／Node22／npm lockfile（Playwright1.62.0）安裝官方測試工具。
- 執行原 Ruff、JS syntax/modules、十組 Chromium browser scripts、完整 pytest，最後建 wheel/sdist；原同 repo 套件與合成 screenshots artifacts 保留。
- Browser fixtures 使用 loopback fake runner／既有 simulator，不存取真相機、攝影機或使用者電腦；不新增 KVM、憑證或安全設定。
- 新入口不重跑 Apple 或 Windows workflow；本地已核原 job steps/action pins 全部一致，首輪真執行結果待補。
- 本輪不宣稱硬體 provider、三平台完整對齊或正式發布完成。

## English summary

This supplemental dev-only workflow reuses the original Linux desktop job without dropping lint, module, browser, contract, or packaging checks. It uses the existing locked Playwright dependency and loopback synthetic fixtures. Original PR gates remain unchanged. Native Chromium and full Python results are pending; the workflow does not publish a release or validate physical cameras.
