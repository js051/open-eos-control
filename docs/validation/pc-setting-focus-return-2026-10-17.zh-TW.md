# PC 設定套用後的焦點回復

G13／原 ledger #122、#123。修正接續名稱關聯 commit `5c0e35394a34cbe95d1661433d0a5ceead8dc6f0`；browser baseline 為 `90174ba05cda310eae10526ad292ab9224130bd4`。

## 真 Firefox baseline

父任務使用既有 cloud Firefox、production HTML/JS 與合成 HTTP camera responses；沒有實體相機、USB、外部 Bridge 或 production 內部狀態修改。

1. Fixture 僅 focus 真正 ISO exposure button；桌面 Enter 開啟原生 settings dialog。
2. 選 200，再以 Tab 到真正 Apply 並用桌面 Enter。
3. 合成 matched write/readback receipt 觸發 production `updateSetting` finally → `renderSession` → `renderExposure.replaceChildren()`，然後 Apply callback 關閉 dialog。
4. Telemetry 確認 dialog closed、原 opener `isConnected=false`、`document.activeElement` 為 **BODY**。這是已確認的焦點遺失，不只是 source 推論。
5. 下一次 Tab 進入 fixture 的 Focus ISO button；因此只聲稱 BODY 結果，**不把 fixture 目標當作 production 下一個 Tab 目標**。

證據：[開啟](evidence/pc-setting-focus-baseline-2026-10-17/opened.png)、[套用後](evidence/pc-setting-focus-baseline-2026-10-17/after-apply.png)、[下一個 Tab](evidence/pc-setting-focus-baseline-2026-10-17/next-tab.png)、[telemetry](evidence/pc-setting-focus-baseline-2026-10-17/events.jsonl)、[合成 fixture](evidence/pc-setting-focus-baseline-2026-10-17/fixture.js)、[SHA-256](evidence/pc-setting-focus-baseline-2026-10-17/sha256.json)。檔案由父任務保留於 integration；browser/server 在 2026-10-17 18:09:36 UTC terminal，exit -15。

## 窄修正

- `renderExposure` 將真正被操作的 button 傳給 `openSettingDialog`。儲存精確 session object、editor generation、setting key 與 opener。
- `closeSettingDialog` 先保留並清除該 focus owner，使用原生 close，接著同步檢查 owner。session／editor／exposure render owner 必須仍相符，且仍在可見 live control view，沒有其他 open dialog。
- 原生 close 若已把焦點放到有效原 opener，維持其結果。原 opener 已移除時，才選目前 strip 中相同 setting key 的 enabled、connected、未被 hidden/inert 祖先遮蔽且有 layout rect 的 button，使用 `preventScroll` 聚焦。
- 若已有其他焦點目標、新 editor、新 session（即使 ID 相同）、disconnect 或其他 dialog，則不搶回焦點。reset 清除 focus owner。queued close event 與 late response 都沒有 focus restoration。
- disabled／hidden／不存在的 replacement 不被強制聚焦。沒有自動 click、重新送 write、改變 command owner，或新增其他 fallback 目標；media dialogs 未修改。

## Host regression

既有 `static-settings-owner-retirement.test.cjs` 增加專用 focus fixture，執行實際 production exposure listener、open/apply/updateSetting/outcome parser、完整 `renderSession` 與 `renderExposure`，並使用實際 exposure availability 片段及 cancel/close registration。其餘 render 葉節點與 transport 是合成 adapter。

DOM adapter 明確模擬 connected／disabled／hidden／layout 狀態；native close 的失效 opener→BODY 行為來自上述獨立 Firefox baseline。它不模擬瀏覽器 Tab navigation 或 accessibility tree。

- 修改 production 前第一版 20 個新案例：**2 FAIL／18 PASS**；加上既有 47 案共 67，其中失敗正是 matched Apply 與同 owner 重畫後普通 Close。Log：`.codex/validation/setting-focus-baseline.log`。
- 補入 late matched receipt＋同 ID replacement connection editor，以及 unmatched receipt 不關閉的案例後，最終該 suite **69/69 PASS**（22 個 focus cases＋原 47）。Log：`.codex/validation/setting-focus-fixed.log`。
- 鄰接 `shooting-settings.test.js`＋`shooting-settings-owner.test.js`：**124/124 PASS**。Log：`.codex/validation/setting-focus-adjacent.log`。
- 包含原 opener 保留、disabled/hidden/inert/layout-hidden/missing replacement、same-ID/different session、editor/view/render-owner 退休、較新 control/dialog/editor、queued close、reset，以及 pending Apply 的實際 cancel listener＋late matched receipt 不搶焦點。
- `node --check app.js`、`git diff --check` 通過；本次未重跑整套 modules。

## 界線與 Release assessment

本地 baseline `0.13.0 Development Preview`；未查遠端 release。Impact：**patch**，修正現有設定成功套用後的鍵盤焦點遺失；沒有版本 bump、push 或 release。

修正後的真 Firefox journey **待父任務驗證**。screen reader／AX／Windows 原生包／實體相機 **NOT_RUN**。Pending Apply→Escape 的 host 測試只證明不強制聚焦 disabled target、不讓遲來 receipt 搶焦點；其實際 browser recovery 仍待 baseline。Details metadata／Preview Download 的 opener replacement 同樣維持具體待驗候選，沒有在本批修正或宣稱已驗收。這不是完整 PC accessibility／三端驗收。
