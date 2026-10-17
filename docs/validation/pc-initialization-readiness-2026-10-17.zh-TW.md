# G13 PC 初始檢查不可等待可選視訊列舉

基線：`597dc6fc9de0155010aa34ee46183efb05dc1c12`（產品來源含 `ab3cc26f` 的 health deadline）。對應原功能帳本 G13／122。

## 反例與窄修正

前一筆 [health recovery](pc-bridge-health-recovery-2026-10-17.zh-TW.md) 已處理 `/health` fetch／body 卡住；但 production `initialize()` 仍先 await 可選的 `refreshLocalVideoInputs()`，再啟動 health。若 `enumerateDevices()` 不結束，mandatory health 根本沒有開始，所以其 15 秒 deadline 也不會啟動。既有事件已綁定，手動「重新檢查 Bridge」可以繞過等待，但初始 readiness／自動 scan 被不相關的視訊列舉拖住。

修正只把 initialize 的該呼叫改成非阻塞。沒有重寫 local-video helper：列舉仍由原 request identity／generation 擁有，成功只更新裝置清單；失敗仍在 helper 內處理為 quiet local-video diagnostics；已退休請求的晚成功／錯誤繼續被忽略。

health 仍被 await；初始相機 scan 仍只在 health 有可用 USB engine、且不要求 token 時進行。沒有因為可選列舉完成就掃相機，也沒有自動重試、忽略 health 失敗、跳過 token 或增加相機控制命令。

## 本地證據

`bridge/tests/local-video-ownership.test.js` 新增 8 案，以 production initialize、refreshLocalVideoInputs、stop 與 connection-guidance 搭配合成 DOM、可控制列舉／health completion 驗證：

- 可選列舉永不結束，health／initial scan 仍完成。
- 列舉先成功時，scan 仍必須等 health；不能由列舉自行觸發。
- mandatory startup 已完成後的可選列舉 rejection 仍被處理，不成為未處理 rejection／startup toast。
- 退休的初始列舉晚成功／失敗不覆寫較新的裝置結果。
- health 失敗、token required 時不自動 scan。
- local video 不支援時，既有 health／scan 路徑不退化。

未改產品的負向控制：**7 fail／1 pass**。修正後新 8 案及原 preview 8 案合計 **16 pass／0 fail／0 cancelled／0 skipped**。`node --check` 與 `git diff --check` 通過。測試檔本來已在 `test:modules`，沒有重複註冊。

此證據不包含真 browser 裝置權限、UVC／HDMI、Windows／WSL、Android／iOS 或實體相機。沒有新增安裝、下載、網路操作或把此前 3626 完整模組結果冒稱為此新產品來源重新執行。相應 GUI／整合證據由 exact-source 驗證另列。

## Release assessment 與界線

影響為 **patch**：修復既有初始化依賴，不新增相機能力、API 或版本號。本地來源／CHANGELOG 最新記錄仍為 Development Preview 0.13.0；未在本次離線任務重新查詢遠端 release。

G13／122 的已知這兩個初始化／health recovery 反例現各有本地修正。這不是整個 G13 或 89 列全功能通過：117 的 Windows 成品／OS 操作未在此驗證，126 的真 EDSDK binding 仍屬原帳本 D/E，128 的 iOS UI／可達性仍未由本 PC host 工作驗證。正常 hooks、後續整合／CI／平台／實體證據與發布狀態分開。
