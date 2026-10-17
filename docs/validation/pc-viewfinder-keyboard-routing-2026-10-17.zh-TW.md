# PC 取景器鍵盤事件與內嵌控制歸屬

基準：`2f55affe8c69fa706c9fd3ac3e88092e80388b66`。對應 G05／G07／G13 既有取景器對焦、白平衡與 PC UI（原 ledger #122／#123）的鍵盤操作。

## 可重現缺陷

`index.html` 的 `#viewfinder` 包含真正的 `#live-magnification-button` 與 `#rtp-audio-button`。`renderAvailability` 在可點選對焦時讓取景器成為可聚焦 button；`renderLiveMagnification` 也允許在 photo Live View、支援 magnification、無 busy／bulb 時啟用放大按鈕。兩者可以同時啟用。

原 `bindEvents` 的 viewfinder keydown listener 沒有檢查事件目標。焦點位於內嵌放大按鈕時，Enter／Space 會向上冒泡，被取景器 `preventDefault()`，並呼叫中心點 `tapFocus`。結果可能把原本的放大／音訊意圖變成 `/focus/tap` 或 `/whitebalance/click` 相機指令。原 pointer listener 已排除 `closest("button")`，所以只用滑鼠點按的既有 browser 案例不會抓到此缺陷。

## 修正

在原 listener 最前面要求 `event.target === ui.viewfinder`。只有焦點在取景器本身的鍵盤事件才能送出中心點操作；內嵌原生控制保留自己的預設鍵盤動作。沒有改 pointer handler、座標換算、能力／busy／generation／Stop ownership，也沒有新增快捷鍵或相機指令。

## 驗證

- 新 `viewfinder-keyboard.test.js` 執行實際 production keydown registration、tap／pointer handlers、座標換算、能力與放大 render 邏輯，以及 tap-availability 片段。DOM／keyboard targets／API 回覆為合成 adapter，target 與 currentTarget 分開；這不是原生瀏覽器鍵盤事件或實體相機驗證。
- 修改 production 前：**18 案中 9 FAIL／9 PASS**。放大／音訊按鈕及其子元素的 Enter／Space 錯送實際 production `/focus/tap` 路徑；另一失敗為非聚焦 overlay 事件也被錯誤接管。
- 修正後新案例 **18/18 PASS**；加上既有 `focus-interaction-ownership`、`magnification-geometry-owner`，合計 **64/64 PASS**，0 skipped／cancelled。
- 正向控制包含取景器自身 Enter／Space 各送一次中心 focus／white balance、非操作鍵保留預設、inactive gate、舊 enabled 樣式下的 command admission、overlay pointer 原座標與按鈕 pointer 排除。原始 markup 確認控制位於事件 host 內。
- 已加入既有 `test:modules` script；`node --check app.js`、`git diff --check` 通過。本次沒有重跑整套 modules 或既有 pending-enumeration／health browser journey。
- 本地 logs：`.codex/validation/viewfinder-keyboard-baseline.log`、`viewfinder-keyboard-fixed.log`。

## Release assessment 與剩餘驗收

本地 baseline 為 `0.13.0 Development Preview`，本輪未查遠端 release。Impact：**patch**，修正既有鍵盤操作可能送錯相機命令。沒有版本 bump／push／release；本地證據不代表 PR ready。

真瀏覽器 Enter／Space 的原生 click 順序、焦點與 zoom／audio 操作仍待 browser 驗收；實體相機對焦／白平衡／放大／音訊仍未驗證。父任務可用 production UI 與合成 HTTP 回覆補上 browser evidence，不需要改 production 內部狀態或操作相機。

其他 read-only 觀察：`setting-dialog` 有 `setting-dialog-title` heading，但目前沒有其他 dialogs 採用的 `aria-labelledby`。本次未讀取瀏覽器 accessibility tree，未把其實際 accessible-name 行為當作已驗證結果，也未修改該獨立標籤問題。本次不是完整 PC keyboard／dialog／accessibility 驗收。


## 2026-10-17 Firefox 實際鍵盤增量

受驗 source `523da07990b9e856a633070e8f3273527d0eb3ac`，production app.js SHA-256 `c682e9ca6c97bee7bd3d006cbb4024567e70155db43a9129d565391b7a6964c1`。既有 cloud Firefox 157.0.1／build 20261005135250，80% zoom，使用 EOS 自有 profile。Production HTML/JS 未改；loopback fixture 模擬 camera metadata、能力、Canvas JPEG 與 API 回應，沒有真相機或外部 Bridge。

1. 實際連線與 Start Live View 後，tap enabled=true、zoom enabled=true。
2. fixture 按鈕只把焦點放到真正 zoom button，再由桌面輸入 Enter：只送 magnification=5；重新聚焦後 Space：只送 magnification=10。兩次都沒有 focus/tap 或 whitebalance/click。
3. 焦點放到真正 viewfinder 後，桌面 Enter 才送 focus/tap；座標為 x=0.5、y≈0.49967（實際內容幾何）。這不是相機完成光學對焦的證明。

[Ready](evidence/pc-keyboard-firefox-2026-10-17/ready.png)、[zoom Enter](evidence/pc-keyboard-firefox-2026-10-17/zoom-enter.png)、[zoom Space](evidence/pc-keyboard-firefox-2026-10-17/zoom-space.png)、[viewfinder Enter](evidence/pc-keyboard-firefox-2026-10-17/viewfinder-enter.png)、[request telemetry](evidence/pc-keyboard-firefox-2026-10-17/events.jsonl)、[synthetic fixture](evidence/pc-keyboard-firefox-2026-10-17/fixture.js)、[SHA-256](evidence/pc-keyboard-firefox-2026-10-17/sha256.json)。

18:01:33 UTC owned Firefox/server terminal exit -15，桌面與重型驗證時段已交回。此旅程不包含 RTP audio 真播放、white-balance 真操作、Windows包、native mobile、screen reader 或物理相機；audio/white-balance 分支本輪只有明示 host evidence。不能提升為三端全部驗收。
