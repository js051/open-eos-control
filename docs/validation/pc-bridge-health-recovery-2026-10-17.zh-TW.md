# G13 PC Bridge 健康檢查逾時恢復

基線：`6093cfbb956c993a8557567f963391b5127604b4`，前一筆 G12 shutdown 修正之後。對應原三端功能帳本 G13／122（PC control UI）的初始化與錯誤恢復，不改相機命令、一般 HTTP API deadline 或其他平台。

## 產品反例

`refreshHealth()` 設定 `healthAttempt` 後等待 `api("/health")`；原 `api()` 沒有此讀取的 deadline，只有 finally 才解除 attempt。HTTP fetch 或成功回應的 JSON body 若永不完成，畫面停在 checkingBridge，「重新檢查 Bridge」disabled，第二次呼叫被 guard 拒絕；scan／connect 的 guard 也不能進行。這與有明確 HTTP／網路 rejection 的既有 recovery case 不同。

本次用正式 `refreshHealth()`、`api()`、render 與 guidance functions，加上可控制的 fetch／合成 DOM／計時器重現，不等待真實網路 timeout，也不把「已送 abort」視為 promise 一定結束。

## 修正

- 每次 health read 持有自己的 AbortController；以 **15 秒** Promise.race 包住完整 `api("/health")`，包含 fetch 和 body 消費。這與既有 PC 控制讀取的 15 秒慣例一致，也高於內建 WSL list 5 秒＋gphoto2 version 5 秒的正常檢查預算；不替第三方 provider 假定無限等待。
- deadline 先 reject 專屬 `BRIDGE_HEALTH_TIMEOUT`，再 abort 原讀取，避免同步 AbortError 把逾時變成沒有說明的取消。即使依賴層忽略 abort，UI 仍能結束這一輪。
- 沿用 `healthAttempt` exact identity，清除自己的 timer；超時舊回覆／錯誤不覆蓋或解鎖新一輪。沒有自動重試，仍由使用者明確按「重新檢查 Bridge」。
- admission 時立即 renderAvailability，讓按鈕狀態與既有 scan／connect guard 一致。
- 英文與繁中新增明確 Bridge health timeout 說明，guidance action 是既有 `bridge`，所以新的成功檢查會清除該錯誤。全域 `api()` 不變。

## 證據

- 最初新增 5 個負向控制，在未修正來源上 **5 fail／0 pass**：沒有 deadline，且 admission 時控制狀態未同步。
- 完成後共 7 個新增 cases，覆蓋永不完成的 fetch、明確新 retry、舊成功／失敗對新 pending／completed read 的隔離、body 卡住，以及同步 abort rejection 不可蓋掉 timeout。
- `node bridge/tests/connection-guidance.test.js`：**104 pass／0 fail／0 cancelled／0 skipped**，包含 97 個既有 cases。既有 production API case 原本自行載入 ApiError，本次共用 harness 改為先載入該類別，因此移除該 case 重複宣告；原 API 狀態與取消斷言完整保留。
- 兩個變更產品 JS 的 `node --check`、`git diff --check` 通過。既有 test:modules 已包含此測試檔，不增加重複註冊。
- 整合前因新增 health caller 的 timer/render dependencies，執行一次完整 `npm --prefix bridge run test:modules`：3626 pass／0 fail／0 cancelled／0 skipped（51個Node runner summaries），另含既有direct-assert scripts；exit0。受驗來源 `ab3cc26f`，整合後Bridge source diff為空。沒有安裝、下載或操作真相機。browser／Windows／Android／iOS native 證據仍需由相應 exact source 的驗證另記，不能以合成 DOM 代稱。

## 範圍與 Release assessment

這是 **patch**：既有 PC 錯誤恢復的 backward-compatible 修正，不新增相機能力、wire contract 或版本號。本地 `CHANGELOG.md` 最新記錄／source 仍為 Development Preview 0.13.0；未查遠端最新 release，不以此離線紀錄決定發布。

G12／116 的一般 shutdown 例外修正另見 [Bridge shutdown receipt](bridge-shutdown-drain-2026-10-17.zh-TW.md)。117 的 Windows standalone 成品／OS 行為、126 的真正 EDSDK provider binding、128 的 iOS UI／可達性均不由本次 PC health read 變更證明；G13 的其他 picker／語言布局／初始化來源也未宣稱全面通過。沒有更動 optional local video 初始化、其他 API 的逾時、session 建立／取消責任或全域自動重試。

本地 implemented；推送、exact-SHA CI、PR ready、main acceptance、發布及實體相機狀態分開處理。

後續另以 [PC initialization readiness](pc-initialization-readiness-2026-10-17.zh-TW.md) 修正可選 local-video 列舉阻擋初次 health 的依賴。該追加變更及其測試數獨立列出，不回填本文件的 104／3626 證據為新來源測試。
