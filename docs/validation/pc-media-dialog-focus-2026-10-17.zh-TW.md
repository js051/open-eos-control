# PC 媒體 Details／Preview 鍵盤焦點

原 ledger G08/G09/G10/G13：#56、#73、#75、#76、#122。此文件先記錄獨立 browser baseline；修正與驗收另列，沒有把 baseline 當作完成。

## Firefox 基準

2026-10-17 18:35–18:39 UTC，production source `dd5befe326df2cd3cfb0c2b7c33cac56218adaf7`，既有 cloud Firefox 157.0.1／build 20261005135250／80% zoom。自有 profile、loopback fixture，合成單張 SYNTHETIC.JPG 與有效 JPEG；無實體相機、USB、私人素材或外部相機網路。Fixture 只提供初始聚焦輔助、合成 HTTP 回覆及 activeElement telemetry；開啟、下載、Escape 均由真桌面輸入執行。

- Details：聚焦真正卡片 Manage 按鈕後 Enter；metadata info 200 引發 production renderMedia，原 opener `isConnected=false`。Escape 關閉後 `document.activeElement` 為 BODY。
- Preview：聚焦真正卡片 preview 按鈕後 Enter；有效 JPEG 解碼呈現。點原 Download，original GET 回傳刻意的 503，沒有實際下載或 OS picker。下載進行狀態令目前 Download control disabled；telemetry 在 dialog 仍 open 時已見 BODY、原卡片 opener 已 detached。Escape 後仍 BODY。
- 下載錯誤 toast 正常顯示 Synthetic download unavailable。這個回覆只驗證失敗分支，不代表成功檔案交付。

證據：[Details 關閉](evidence/pc-media-focus-baseline-2026-10-17/details-closed.png)、[Preview 下載後](evidence/pc-media-focus-baseline-2026-10-17/preview-download.png)、[Preview 關閉](evidence/pc-media-focus-baseline-2026-10-17/preview-closed.png)、[telemetry](evidence/pc-media-focus-baseline-2026-10-17/events.jsonl)、[fixture](evidence/pc-media-focus-baseline-2026-10-17/fixture.js)、[source/terminal receipt](evidence/pc-media-focus-baseline-2026-10-17/receipt.json)、[SHA-256](evidence/pc-media-focus-baseline-2026-10-17/sha256.json)。

Firefox/server 已於18:39:15 UTC terminal（exit -15）；原 PID 已不存在。AX 僅 x11 window/container，沒有宣稱 screen reader／accessible tree 驗收。

## Release assessment

本地 baseline 為 0.13.0 Development Preview；本次沒有查遠端 release。已確認現有媒體操作鍵盤焦點缺陷，若完成窄修正 impact 為 patch；沒有新功能、版本 bump 或發布。Android/iOS native、Windows installer、真相機與實際目的地寫入仍 NOT_RUN。

## 修正設計與審查

- 每個 dialog 保存精確 session 物件與本次 opener。卡片按鈕標記 media ID 與 preview/manage 角色；Preview 內下一張／重試保留起始 opener，轉 Details 時傳遞同一來源。
- 關閉同步清理並回復焦點；僅同一 session、沒有其他 modal／新焦點接手時才處理。優先有效原 opener；卡片來源另外要求 media view、目前 render owner、item 仍存在、同頁同角色按鈕可見且 enabled，才選 replacement。不改 filter、page 或排序，不用檔名或 index 猜測。
- 在 focused dialog action 即將 disabled 時，先移到該 dialog 已存在且可用的 Close，避免 busy/render 流程令焦點掉出 modal；其他控制或背景更新不搶焦點。
- resetSession 在關窗前退休 focus owners；Escape 走同步 close helper。移除舊 queued close 清理對新 opening 的干擾，沒有 queued focus restore。
- Browser baseline 與 source 審查是兩種不同證據；修正後 Firefox 結果尚待下節記錄。

## Host 回歸與來源

修正來源 `9dd9cf38691970f94302ecc8715910f2e411faba`，整合 `308c035b2a399297d8a6be4e82e1d3d31e95559b`；app.js SHA-256 `bb296257f9616cb4bffb479a71e5eac19e84cebd643811f9778ce66460c8702b`。

- 初版17案在未修改production時10 PASS／7 FAIL。當中四個是detached或原invoker回復行為；其餘三個為待新增helper／Escape wiring契約，不冒稱三個獨立browser缺陷。真失焦依上述Firefox基準。
- 最終新增34案：focus helper／guard29案，production reconnect fixture新增4案，production download renderer新增1案。涵蓋實際Manage listener→metadata回覆→renderMedia替換→close、Preview navigation/retry/轉Details、reset退休、queued舊close、下載focused action先移Close。
- 受影響鄰接suite合計286/286 PASS。VM抽取fixture因新增helper補齊dependency，原斷言未移除或放寬。
- 完整`npm run test:modules`最終exit0：63個明列command，53組node:test報告，共3709/3709 PASS，fail/cancel/skip皆0；其餘直接assert contract scripts亦成功。最終log為來源worktree `.codex/validation/pc-media-focus-modules-final-20261017.txt`，不是較早dependency缺漏的中途失敗log。
- 整合source static UI15/15、node syntax、diff whitespace均通過。原commit正常pre-commit完成官方cached gitleaks checksum及staged scan，無洩漏。

這些為host／合成adapter證據，不替代native或真相機驗收。

## 第一輪修正後 Firefox：未通過而保留

19:07–19:09 UTC 在整合 `6153463ba2bf9e2a9f8cc8195c3ee814f78c6474` 實測：Preview Download 503後焦點已留在 `media-preview-close`，但 Details Escape後仍 BODY，直接Enter未重開。因此沒有把host全綠當作browser通過，也未交付這個候選。

調查發現原guard沒有涵蓋native close返回時仍保留於剛關閉dialog內的焦點；既有setting dialog復原已處理此種狀態。補上僅允許「本次剛關閉、仍closed的精確dialog」內activeElement，其餘session／新owner／其他open modal／外部新焦點守衛保留。新增4案，修正前33案中的2案失敗，修正後受影響suite290/290 PASS。來源 `60344bfafa761a02402757227a947250ec012cab`，整合 `36dfdac6944fdcbeef8ba555cc752e48e6fb322b`。

[第一輪telemetry](evidence/pc-media-focus-intermediate-2026-10-17/events.jsonl)、[Details失敗畫面](evidence/pc-media-focus-intermediate-2026-10-17/details-closed.png)、[下載後Close保留](evidence/pc-media-focus-intermediate-2026-10-17/preview-download.png)、[terminal receipt](evidence/pc-media-focus-intermediate-2026-10-17/receipt.json)。`details-reopened.png`檔名表示嘗試重開，畫面實際仍closed，不當成功證據。19:09:35 UTC terminal，exit -15。

## 最終 Firefox 對照通過（窄範圍）

19:17–19:18 UTC，固定source `abbcde3bd247db8ee277fafe8f34edc2564168b6`，app.js SHA-256 `224ecb55fd6e22f37b8ba59d90aa2507717c9891c1ca5bbfd31f2a10f4e97d4c`。同一合成相機／有效JPEG／original503，沿用既有Firefox及自有profile。

- Details metadata回覆替換舊Manage，Escape後activeElement是目前同ID的manage按鈕；直接Enter重開Details。
- Preview Download503期間activeElement是media-preview-close；Escape後是目前同ID preview按鈕，直接Enter重開有效JPEG。
- Fixture本次另以純觀察wrapper呼叫原HTMLDialogElement.close，再記錄返回瞬間activeElement：Details和Preview皆仍在剛關閉dialog的Close內。這提供前一輪診斷的直接browser證據；wrapper沒有改close參數／結果或呼叫focus。
- 五張畫面、telemetry與fixture保留；19:18:45 UTC ownedFirefox/server terminal exit -15，PID已不存在。AX仍僅x11，不宣稱screen reader通過。

[Details關閉](evidence/pc-media-focus-final-2026-10-17/details-closed.png)／[Enter重開](evidence/pc-media-focus-final-2026-10-17/details-reopened.png)；[Preview下載503](evidence/pc-media-focus-final-2026-10-17/preview-download.png)／[Escape返回](evidence/pc-media-focus-final-2026-10-17/preview-closed.png)／[Enter重開](evidence/pc-media-focus-final-2026-10-17/preview-reopened.png)；[telemetry](evidence/pc-media-focus-final-2026-10-17/events.jsonl)、[fixture](evidence/pc-media-focus-final-2026-10-17/fixture.js)、[hash](evidence/pc-media-focus-final-2026-10-17/sha256.json)。

同最終source完整test:modules exit0，53組node:test報告3729/3729、fail/cancel/skip0，加其餘直接assert scripts；static UI15/15、syntax/diffcheck通過。最終log `.codex/validation/pc-media-delivery-final-20261017.log`。新測試總增量54案，涵蓋本文件焦點38案與[遲來upload publication/preview](pc-single-download-owner-2026-10-17.zh-TW.md)16案；不是新增54個產品功能。新media race是host證據，未冒稱全部在Firefox觸發。
