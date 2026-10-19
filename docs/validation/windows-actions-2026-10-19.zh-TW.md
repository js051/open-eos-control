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

## 首輪實際結果（2026-10-19 13:24 UTC）

- Source：`ab2cb5b6ff14a358dfaadf34a93bc00f7e5cda02`。
- [Run 39259649939](https://github.com/js051/open-eos-control/actions/runs/39259649939)：全歷史 secret scan、actionlint 與 Windows job 全部通過。
- [原生 job log](https://github.com/js051/open-eos-control/actions/runs/39259649939/job/118083759062)：Windows Server 2025 10.0.26100，image `windows-2025-vs2026` / `20261007.264.1`；Python 3.12.10。
- 原真 exe verifier 於 13:24:07 UTC 通過，產品版本 0.13.0；所有原檢查與服務停止責任保留。
- 本輪 resolved runtime：av18.1.0、FastAPI0.148.0、Pillow12.3.0、Pydantic2.14.1、Uvicorn0.54.0；PyInstaller6.21.0。完整 `pip freeze` 留在原 log。
- 同 repo artifact `release-candidate-desktop-windows`，ID `12189692288`，50,959,494 bytes；GitHub archive digest `sha256:bfc34ed60d0852a0b15bfe492638e4a8b32929de16efe76d119e9f7fcb59aded`。此為 API／upload 收據，未另下載在 Linux 執行 Windows exe；有效至 2026-11-02。
- Windows 原生封裝／啟動門檻已收斂；實體 USB、WSL／gphoto2、Canon EDSDK binding 及真相機功能仍需各自證據。

Windows native packaging and executable verification passed on the real Windows runner. The artifact and resolved dependency inventory are linked above. This closes the native bundle smoke-verification gap, not physical-camera parity or release acceptance.
