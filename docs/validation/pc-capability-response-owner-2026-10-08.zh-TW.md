# PC 能力回應的連線歸屬

日期：2026-10-08 UTC。基底為 accepted main `b0b8d83e12af8a054efb0024682cba9b3bab891d`，tree `5509ea059d6abfd802f0be479149be3049708f95`；對應 [PR #221](https://github.com/js051/open-eos-control/pull/221) 與 [Main acceptance 37831713361](https://github.com/js051/open-eos-control/actions/runs/37831713361)。本批目前為 `implemented`，尚未取得自己的 exact-head `ci-complete`，不沿用基底的驗收結果。

## 問題與修正範圍

使用者開啟 Diagnostics 時，`selectView` 會開始讀取能力資料，並保留正常 Disconnect 操作。舊連線 A 的回應若晚於斷線，原本會把已清空的能力重新填入；若已連線 B，則會把 A 的能力發布到 B。診斷報告立即出現錯配，下次 availability render 也可能開放只有 A 支援的控制。

`refreshCapabilityEvidence` 現在保存發出請求時的 session 物件，先把回應留在區域變數，只有目前仍為同一個 session 物件才發布。`connectCamera` 每次從連線回應建立新的物件；`resetSession` 清為 null，其他目前呼叫端沒有就地改變該物件的 connection 身分。因此不依賴 wire ID 永不重用，也不需新增計數器或取消協定。

此檢查只處理連線歸屬。同一連線內的其他操作會改變 `refreshGeneration`，但不應因此丟棄這筆仍有效的能力證據；本批不改同連線多筆 refresh 的排序政策。成功更新、無連線時不請求、失敗時靜默返回的原行為保留。

所有呼叫端均已檢查：Diagnostics 選頁、時鐘同步、建立資料夾、檔名更新、不關機的 sensor cleaning、複製診斷、複製實機驗證紀錄。後六項已有 interaction lock；本批沒有改動其相機操作、鎖定、重試或錯誤訊息。

## Test-first 與本地結果

新 `bridge/tests/capability-evidence.test.js` 直接擷取並執行 production 函數，包含 connect、Diagnostics 選頁、disconnect、reset、availability 與診斷能力投影。API 回應使用可控制完成順序的 synthetic promise；與本問題無關的 rendering／teardown 為 stub。沒有 production export、測試開關或計時 sleep。

- 19:36:30，未修改的 accepted base：8 例中 **3 紅、5 綠**。實際失敗是斷線後回填，以及重連不同／相同 wire ID 後覆寫 B；不是匯入或測試環境錯誤。
- 19:36:57，相同測試檔加上最小修復：**8／8 通過**，0 failure／skip／cancel。
- 控制涵蓋正常同連線成功、同連線 generation 改變仍發布、同連線與替代連線的延遲 rejection 都保持靜默，以及無連線時零請求。反例另驗正常 UI 仍可斷線／重連、B 不取得 A-only capture、晚回應不增加相機寫入。
- 19:39:50–19:39:53，完整 `npm run test:modules` 的十個腳本按原入口串行通過，包含新 8 例；沒有把重跑相加。六項 `test_static_ui.py`、Bridge／diagnostic verifier 的 Ruff、全部九個 production JS 與兩個變更測試檔的語法、版本 verifier、`git diff --check` 均通過。版本 verifier 為 `0.13.0`。

執行固定一個 CPU，每項最多 60 秒，每 100 ms 監看 supervisor 與其全部可追蹤後代的 aggregate RSS，超過 640 MiB 即停止；這是觀測停止門檻，不是 kernel hard cap。紅／綠各約 0.206 秒；完整串行驗證的最大抽樣值為 126.18 MiB，未觸發時間或記憶體停止。各輪來源檔案執行前後 SHA-256 相同。

本地 Node 為 24.19.0；正式 CI 仍使用原設定的 Node 22。Python 3.12.14 與既有環境的 av 18.1.0、FastAPI 0.143.0、Pillow 12.3.0、Pydantic 2.14.0、uvicorn 0.54.0、pytest 8.4.2、Ruff 0.16.10 符合本 repo 依賴範圍；本批沒有安裝或更換依賴。

驗證來源 SHA-256：

| 檔案 | SHA-256 |
| --- | --- |
| 修復後 `app.js` | `a10117633e5ab75c1de561aab776573aa6b713a5435e45bd2da519152b821f02` |
| `capability-evidence.test.js` | `458e291e09e44ddae47848fcebd74561fa4d53a14242a3e63fa65d2d9045d2f3` |
| `ccapi.browser.test.js` | `19d93502410e8255944f98eaf63e5338fd512609ded1c3cc9bbcf8dcb5800e6a` |
| `bridge/package.json` | `e803efbdaa086f68a4c8d97ced1fc83dde7711b58999e92e62a85aba6600e3c6` |

原基底 `app.js` SHA-256 為 `8b865835ccd853120fd7bee316a44bb45e5bd3e50100bef095035f58f7cd6153`。紅／綠的 focused 測試檔完全相同。

## Browser 與剩餘驗收

既有 `ccapi.browser.test.js` 新增兩個獨立 context 的正常 DOM 旅程：Diagnostics → Disconnect，以及 Diagnostics → Disconnect → Connect B。沿用真 Bridge HTTP 與既有 fake USB backend，僅由 Playwright route 控制能力內容與延遲順序。測試沒有直接變更 app session／capabilities。

測試先證實 Disconnect／Connect 可操作，待舊回應完成後的 diagnostic render 發生才檢查結果，避免在回應尚未處理前提前判綠；重連例再透過正常 Photo 控制觸發 availability render，確認 B 仍不可拍照。HTTP 寫入只允許明確的連線建立／刪除，不發曝光或設定命令。

新 browser 旅程僅完成語法與來源審查，**尚未執行**。本地 Chromium 先前已確認在建立 page 前遭 `process_singleton_posix` socket EPERM 阻擋，本批沒有重試或改變 sandbox／安全旗標。正常 `test:browser` 與所有必要 gate 保留，需由本批自己的遠端 CI 執行；不能把 Node VM 或語法通過描述成 browser 通過。

## Release Assessment

- Latest release baseline：[v0.12.0 Development Preview](https://github.com/js051/open-eos-control/releases/tag/v0.12.0)。目前 source metadata 為 0.13.0；兩者是不同狀態。
- Target release channel：Development Preview；本批不改版本、不發布。
- Proposed impact：`patch`。向後相容修正既有 PC 診斷與控制可用性的跨連線污染，沒有新增協定或能力。
- User／tester value：慢回應不再將上一台相機的能力帶到已斷線或替代連線。
- Unresolved blockers：本批 exact-head CI／browser 尚待通過；既有固定 v0.13.0 候選仍 HOLD，Compose UNKNOWN 沒有由此修復解決；PR #219 仍為獨立、未合併的實驗工作。本批不構成發行許可。
- Physical-device status：pending。全部新增結果為 deterministic synthetic evidence，沒有相機、Windows、手機或實體 USB 驗收。

不包含其他 refresh helper 的擴大整治、同連線 refresh 排序、新相機操作、平台權限、版本或 required-check 變更。
