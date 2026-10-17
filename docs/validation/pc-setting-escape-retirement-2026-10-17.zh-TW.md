# PC 設定 Escape 的同步 editor 退休

原 G03，基準 `f3373bb7`。這是既有設定 Apply／取消生命週期的 patch 修正；不新增設定功能、協定、版本或發布。

## 已重現的 caller 缺口

1. Production exposure button 開啟 ISO dialog，選 200，再 Apply。
2. `updateSetting` 已送出一次 outcome POST，回覆仍 pending。
3. 真正 production `cancel` listener 呼叫 `closeSettingDialog`。原碼只關閉 modal、記下 close owner，尚未取消 operation 或遞增 editor。
4. 在 queued native `close` event 前送達 matched receipt。原碼 `publishSetting` 仍認為 editor 有效，更新 current ISO，刪掉 submitted draft；歷史也沒有 cancelled 標記。
5. 同一空窗內，保留的舊 option／Apply callback 仍能新增一次 POST。

原有 pending Apply→Escape 測試先手動 dispatch `close`，再 resolve receipt；它覆蓋的是另一個順序。原有 owner 測試則直接呼叫 cancel，不會穿過這個 production caller 空窗。

## 最小修正

`closeSettingDialog` 保留 closing editor，於 native close 前同步遞增 editor，並只取消該 editor 擁有的 pending operation。原有 `close` owner queue 仍保留原 generation，因此晚到的 native event 不會重複退休，也不能取消新 editor。

成功 Apply 的 `updateSetting`／`publishSetting` 不變；它先完成原有 publish，再關 dialog。同步 return-focus 使用 captured closing generation 及其退休後 generation，維持成功 Apply 的同 key replacement focus，並排除關閉期間出現的新 editor、session 或 focus destination。

不取消獨立 advanced-setting Apply（editor 為 null）。取消不代表相機撤銷先前寫入；late receipt 仍可留作歷史證據，但不更新目前畫面或消耗 draft。沒有自動重送 write。

## Host evidence

使用既有 production-function／synthetic DOM fixture，執行實際 exposure、modal listener、Apply、update、publish、render 與 cancel/close registration。沒有 browser、真相機或外部 HTTP。

- 修改 production 前：原 69 案＋4 新案，共 **71 PASS／2 FAIL**。失敗分別是 late receipt 刪 draft、dismissal 後舊 callback 仍 POST。
- 修正後：該 suite **73/73 PASS**；加上 shooting settings policy／owner 與 capture configuration owner，共 **213/213 PASS**。
- 新隔離 controls：舊 close event 不取消新 editor 的 active Apply；獨立 advanced Apply 仍正常 publish。
- 既有 controls：成功 Apply 與普通 Close 的焦點回復、較新 session/editor/focus、unmatched receipt、reset、同 ID 新 connection 均保留。
- Static UI：**15/15 PASS**。`node --check app.js`、`git diff --check` 通過。

外部本地驗證資料夾 `pc-setting-escape-retirement-evidence-20261017` 保留 `red.log`、`fixed-focused.log`、`static-ui.log` 與正常 commit hook log。整套 modules runner 的完成狀態未確認，不計入以上通過數。

## 驗證界線

這些測試證明 production caller 的事件排序保護，不代表瀏覽器的真實鍵盤／焦點時序或實體相機驗證。父任務另行驗證 Firefox pending Apply→Escape→late receipt；本文件建立時該 GUI 對照仍 pending。Windows 原生包、screen reader、AX 與真相機 NOT_RUN。未推送、未執行 CI、未合併或發布。

## 20:20 UTC Firefox增量

[baseline/fixed圖與telemetry](evidence/pc-setting-escape-2026-10-17/fixed/assessment.json)：f3373bb7基準以真正ReadableStream合成body在native close返回後就緒，ISO被改成200/Ready。4faea09a fixed同流程保留ISO100，late receipt只留cancelled歷史，不自動publish。最初fetch-held未命中、json/text替身不符合production streaming reader，均保存而不當成功證據。

**仍有獨立缺口：** pending Apply時ISO按鈕disabled；Escape同步關窗時無可聚焦目標，之後response settlement重繪，焦點仍BODY，Enter不能重開。這不是先前成功Apply焦點路徑，也未因本次receipt修正解決，列為下一段修復。不能宣稱整段pending Escape鍵盤驗收通過。

整合產品1128b4b2完整PC modules3791、Python主608及相鄰77全過；4faea09a僅test格式整理，再驗34案及Ruff通過。未執行native/真機驗收。
