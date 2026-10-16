# Media 重連與畫面 owner 隔離（2026-10-16）

基線：共同 dev `4ec8471f8099bb9935cba393c2bbaf84ea3bbaff`。範圍為 PC Bridge UI 既有斷線／重連及媒體流程；沒有新增相機能力、協定、下載依賴或實機操作。

## 已證缺口

前一批 Firefox 實看切換 fake USB 至 synthetic CCAPI 時出現舊媒體卡片。隨後以 production JavaScript functions 執行 Node 反例：22 案基線 9 pass / 13 fail。失敗為產品行為斷言，不是 fixture runtime error。

- `resetSession` 清掉 `state.media` 卻不清舊卡片 DOM；保留 Media tab 後 `connectCamera` 沒有載入新相機清單。
- 舊卡片捕捉 item，但 click/change callback 使用當前 session。不同相機即使重用相同 session ID／media ID，也可能錯發新相機的 info／preview，或選取新相機同 ID 檔案。
- 已排隊的 IntersectionObserver entry 只憑 media ID 找當前資料，可能把舊卡片的 lazy thumbnail demand 套到新相機。

## 修正契約

- 退役時同步清卡片 DOM，撤銷 rendered owner；移除 DOM 後仍被持有的 callback 也不能操作。
- 連線成功仍停在 Media 時，顯示新清單並開始一次新 listing；沿既有 connection busy 邊界等待。新相機不支持 Media browser 時回 Live。
- 每次 render 具有 session-object + render-owner 身分，三種 card handler 先核 owner。換頁／同 session 重新 render 也撤銷舊 handler。
- lazy thumbnail entry 核 session-object、media generation、render owner；當前頁仍可正常載入。
- 普通 status render 不自動重試 FAILED/CANCELLED/COMPLETE 清單；原 info/preview/listing 晚回隔離維持。

## 驗證

新增 production-functions Node 案：reset DOM、真 connect 成功路徑、same/different session ID 同 media ID、空 session、當前正向、busy boundary、capability fallback、非 Media lazy、舊 queued observer、換頁及重繪、舊 info completion。

獨立 review 發現第一版修正仍有相鄰 caller 交互：connect 在啟動 gallery 後呼叫 `refreshLatestMedia`，後者會取消 gallery owner；原單函式測試將 latest refresh stub 成 no-op，當時 795 綠燈不能代表此整合已通過。加入 production latest refresh／cancel／publish 聯合路徑後先得到 37 案 33 pass / 4 fail，保留紅結果。

修正為 gallery-first：僅清單完成、同 session 與 interaction generation、無 busy／替代 listing／既有 latest owner 才開始 latest enrichment。取消或失敗不引出其他自動讀取；非 Media 連線保留原 latest observation。

相關回歸涵蓋 listing、auto-import、mutation、delete、preview、connection、capability、writable UI、capture、batch、date/folder/rating filter。六份舊測試的 minimal DOM 補上標準 `replaceChildren` 操作，沒有刪除原斷言；補前的 mock API 失敗記錄保留。

iOS source：CameraAppState 連線／斷線清 `mediaItems`、失效 media library generation 並回 control；Android source：disconnected state 清 `mediaItems` 並增加 `mediaSessionGeneration`。本群不修改 mobile，也不以 source 核對替代 native 執行。

本修正尚未在新產品 HEAD 進行 Firefox 實看；前一批 Firefox 僅是發現線索。iOS/Android native、實體相機、CI 均 NOT_RUN。Release Assessment：patch（既有 PC 重連流程修復），未合 main、未產生版本／發布包；不能稱 PR ready 或 released。

## 最終回歸與 review

獨立只讀 review 復核修正後無 blocking；另把聯合成功案加入真 state.busy 與受控 sleep 解鎖。最終 14 份 Node 測試檔共 **805 pass / 0 fail / 0 cancelled / 0 skipped**（新增 42 案），3,347.379 ms。測前 manifest 與測後來源一致；產品 app.js SHA256 `ab3e20ec314309be401afb3c74ed5d0c0b80a65ce02b0a2efb34d2d84538ddb3`。文檔此段是在測後新增，未修改受測產品或測試。
