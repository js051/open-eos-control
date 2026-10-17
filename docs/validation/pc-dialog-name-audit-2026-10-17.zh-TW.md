# PC 原生對話框名稱與鍵盤路徑檢查

基準：`90174ba05cda310eae10526ad292ab9224130bd4`。範圍為 G13／原 ledger #122、#123 的既有 PC dialogs；沒有新增 UI 或焦點政策。

## 確認的名稱缺陷與修正

`index.html` 的八個原生 `<dialog>` 中，只有 `setting-dialog` 沒有 `aria-labelledby`、`aria-label` 或 `title`。它已有 `h2#setting-dialog-title`，但只有子 heading 不會自動建立 dialog 的名稱關聯。

官方依據：

- [HTML-AAM：dialog](https://www.w3.org/TR/html-aam/#el-dialog) 將原生元素映射到 WAI-ARIA dialog role。
- [WAI-ARIA 1.2：dialog](https://www.w3.org/TR/wai-aria-1.2/#dialog) 要求作者提供 accessible name，名稱來源是 author；可透過 `aria-label`／`aria-labelledby` 提供。
- [WAI APG：Modal Dialog](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/) 建議用 `aria-labelledby` 關聯可見標題，或使用 `aria-label`。

修正只在原 dialog 加上 `aria-labelledby="setting-dialog-title"`。原 production `openSettingDialog` 在 `showModal()` 之前就以 `settingLabel(setting)` 填入標題，因此名稱沿用目前語言及目前 setting，不另複製一份字串。沒有修改相機命令、owner、close/cancel 或鍵盤 handler。

## 驗證與界線

- 八個 dialog 的參數化 regression 檢查精確 title ID、唯一目標、heading/strong 與未標成 hidden；新增 dialog 也必須補入明確檢查。
- 修改 HTML 前：**1 FAIL／7 PASS**，唯一失敗為 `setting-dialog` 沒有關聯。修改後完整 `test_static_ui.py`：**15/15 PASS**。
- 既有 settings-owner suite 新增 production `openSettingDialog`＋`settingLabel` caller 案例：在 `showModal` adapter 被呼叫時已填入名稱；再次以另一語言開啟會更新名稱。字典及 DOM 是合成 adapter，並非完整 locale resource／accessibility tree 模擬。完整該 suite：**47/47 PASS**。
- Ruff、`git diff --check` 通過。未重跑整套 modules，未安裝工具或啟動瀏覽器。
- Logs：`.codex/validation/dialog-name-baseline.log`、`dialog-name-fixed.log`、`dialog-title-caller.log`。
- 這是 markup／production caller 證據。實際瀏覽器 accessibility tree、screen reader 宣讀與各平台原生 focus 行為 **NOT_RUN**；目前桌面可見性只有 x11，不能以截圖代替 screen reader 證明。

## 同輪 bounded dialog audit

檢查 `app.js`／`index.html` 的八個 dialog 開啟、close/cancel wiring、相關 disabled-control 與焦點路徑，以及對應 host fixtures：

| Dialog | 已查來源路徑 | 尚需原生瀏覽器證據 |
| --- | --- | --- |
| Monitoring | `showModal`、可用 Close、backdrop close；原生 Escape | Tab／Shift+Tab 包圍、關閉回到 opener |
| Setting | 填標題後開啟；cancel 轉入具 editor ownership 的 `closeSettingDialog`；close event 退休對應 editor | 下列 opener invalidation 候選；原生 Escape／Tab |
| Preview | 卡片開啟、Close/backdrop、close event 清資源；原生 Escape | Download 後 opener 被替換的焦點結果 |
| Details | 卡片 Manage 開啟、metadata owner guard、Close/backdrop 與清理；原生 Escape | metadata 回覆重畫清單後的焦點結果 |
| History | 原生開啟／關閉；確認取消會回到 Clear；成功清除會先 focus Close 再更新 rows | 實際 Tab／Escape 及返回工具列 |
| Date | 靜態 filter opener；無效值設 aria-invalid 並 focus From；正常 Apply 先 close 再重畫媒體 | 原生錯誤焦點、Tab 與返回 filter |
| Folder／Rating | 靜態 filter opener；原生 select autofocus；Apply 先 close 再重畫媒體 | 原生 Tab／Escape 與返回 filter |

八個 dialog 都有已接線的原生 close button；未發現應用程式停用它們的程式碼，也沒有 dialog Tab 攔截器。除 Setting 外，其餘依賴原生 cancel/Escape。這些是已查來源行為，不是跨瀏覽器驗收完成。

### 仍待 browser baseline 的具體焦點候選

1. **Setting 成功 Apply**：`updateSetting` finally → `renderSession` → `renderExposure.replaceChildren()`，在 Apply callback 接著 `closeSettingDialog()` 前，先移除原 exposure opener。**pending Apply 時 Escape/Close** 的變體，opener 已被 `renderAvailability` disabled。close handlers 沒有選擇替代焦點。
2. **Details metadata 成功**：卡片 Manage → `openMediaDetails` → metadata 回覆 → `renderMedia` → `mediaList.replaceChildren()`；dialog 仍開啟，原 Manage opener 已被移除。
3. **Preview Download**：卡片 Preview → Download → `downloadMedia` 開始時同步 `renderMedia()`；在首個 await 前就替換 thumbnail opener，即使之後 picker 取消／失敗也成立。

以上只確認 opener 失效的可達順序與缺少 app fallback；沒有聲稱實際 activeElement 或下一個 Tab 落點。[HTML dialog close algorithm](https://html.spec.whatwg.org/multipage/interactive-elements.html#close-the-dialog) 會嘗試聚焦保存的原元素；[APG](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/) 對已移除 opener 建議回到合理工作流程元素。仍需在 production UI 加合成 HTTP 回應的真瀏覽器先取得 baseline，再決定是否修正。現有 host `showModal/close` 只是 open flag，且 settings fixture stub `renderSession`，不能代替此證據。

另外，Setting pending Apply 時選項按鈕沒有同步刷新 disabled 樣式，但 click handler 的 busy guard 會拒絕操作，沒有多送相機 write 的來源證據；本批未把它擴大為新的修正。

## Release assessment

本地版本基準 `0.13.0 Development Preview`；本輪未查遠端 release。Impact：**patch**，修正既有對話框可存取名稱。沒有版本 bump、push 或 release。名稱修正的 focused checks 通過；PR ready／native acceptance 仍須各自證據，實體相機 **NOT_RUN**，此變更不增加相機相容性聲明。焦點候選尚未 browser-reproduced，不在本批宣稱已解決。
