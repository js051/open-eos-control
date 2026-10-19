# Windows 原生封裝補充驗證

## 首輪範圍

- 新增 Windows-only `dev` push 入口，復用原 `android.yml` 的完整 `desktop-windows-bundle` steps、action SHA pins、20 分鐘上限與 14 天同 repo artifact。
- 原 PR workflow、`ci-complete`、main 與 release gates 不變；此補充結果不能替代全 CI 或三平台／真相機驗收。
- 先執行原 Gitleaks 8.30.1 全歷史掃描（官方 archive SHA256 原 pin）與原 actionlint 1.7.12 校驗腳本。
- Windows Python 3.12、PyInstaller 6.21.0；其餘 runtime 依賴保留原 pyproject 範圍，並輸出本輪 `pip freeze`。這不是完整 lockfile 可重現性聲明。
- 打包後執行真 `.exe`，僅啟動 loopback、停用 browser，自動核實 UI／CSP／靜態資源／health／產品版本與三種 engine 名稱，再確認程序樹及服務已停止。
- 沒有發出相機命令、KVM／udev、安全設定、憑證或對外 release 操作。engine 名稱出現不等於硬體 provider 已驗收。
- 本地 YAML 結構與原 Windows steps 逐項比較通過；尚待首輪 Windows Actions 實際編譯／執行。

## English summary

This supplemental dev-only workflow preserves every original Windows bundle step and pinned action, preceded by the existing full-history secret scan and actionlint validation. It builds and launches the actual executable on a Windows runner, checks its loopback service and bundled resources, and records resolved dependencies. It does not replace PR CI, publish a release, change security settings, or validate physical camera providers. Native execution results are pending.
