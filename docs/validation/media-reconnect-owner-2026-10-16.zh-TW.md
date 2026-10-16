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

產品封存當時尚未進行新 HEAD Firefox 實看；後續結果見下節。iOS/Android native、實體相機、CI 均 NOT_RUN。Release Assessment：patch（既有 PC 重連流程修復），未合 main、未產生版本／發布包；不能稱 PR ready 或 released。

## 最終回歸與 review

獨立只讀 review 復核修正後無 blocking；另把聯合成功案加入真 state.busy 與受控 sleep 解鎖。最終 14 份 Node 測試檔共 **805 pass / 0 fail / 0 cancelled / 0 skipped**（新增 42 案），3,347.379 ms。測前 manifest 與測後來源一致；產品 app.js SHA256 `ab3e20ec314309be401afb3c74ed5d0c0b80a65ce02b0a2efb34d2d84538ddb3`。文檔此段是在測後新增，未修改受測產品或測試。

## 新產品 HEAD 的 Firefox 實看（12:42–12:49 UTC）

固定產品 `42e20f8fb91df04db29f4dc6c0563843189854b7`，雲端桌面既有原生 Firefox 157.0.1；不是 Playwright Chromium。正常 sandbox、新 disposable profile，僅 loopback 18765 Bridge／18766 合成 CCAPI。EDSDK provider 關閉、gphoto 使用 FakeRunner、CCAPI origin 白名單且禁止 redirects／credentials，RTP 關閉。沒有真相機。

測試 wrapper 保留 production session/list/info parsing，僅為兩次連線提供 A/B 名稱、同 opaque media ID 的不同 metadata（111111／222222 bytes，rating 1／5），並加入 gallery 3 秒、latest 2 秒、info 5 秒延遲。這是可辨識的合成契約資料，沒有證明兩部實體相機或檔案內容。

實看結果：
- A 的 Media 顯示 A_SAME_ID.PNG、109 KB。仍在 Media 時斷線，再連 B，全程沒有 reload，也沒有重新點 Media 或 Refresh。
- 新 session 自動顯示 B_SAME_ID.PNG、217 KB，沒有沿用 A 卡片。wire 記錄確認兩者 opaque ID 相同；B gallery complete 12:45:30.442，latest start 12:45:30.461，順序符合修正。
- B 詳情真正在等 info 時顯示 Reading metadata，控制 disabled；5 秒回覆後恢復 Protection／Rating／Rotation／Archive 控制，rating 為 5，沒有停在 loading。
- 沒有執行 mutation-inflight、下載／刪除；stale detached callback 與 queued observer race 的證據仍是上述 Node 反例。mobile native、實機、RTP codec 未驗。

六張持久影像由 CUA 回傳的原始 JPEG bytes 保存，並非引用已消失的暫存 screenshot 路徑：

1. [A 連線](assets/media-reconnect-20261016/01-camera-a.jpg)
2. [A Media](assets/media-reconnect-20261016/02-a-media.jpg)
3. [斷線](assets/media-reconnect-20261016/03-disconnected.jpg)
4. [B 自動新清單](assets/media-reconnect-20261016/04-b-media.jpg)
5. [B info loading](assets/media-reconnect-20261016/05-b-info-loading.jpg)
6. [B info 恢復](assets/media-reconnect-20261016/06-b-info-recovered.jpg)

12:42:35.660 啟動，12:48:35.631 主動停止，owned exit 為 -15／-15（不是自然 exit 0）。原 stop receipt 當時仍列兩個剛結束的 children，保留不改；12:48:42 ps 及 12:49:21 /proc 再核都沒有 owned processes。清除已停 disposable profile 41,136,979 logical bytes；free 5,711,646,720 → 5,753,155,584 bytes。監測最低 free 5,710,118,912 bytes，未碰 4 GiB floor；這是 sampled guard，非 hard quota。其餘 fixture、wire、receipt、截圖 hash 均保留於 [證據目錄](assets/media-reconnect-20261016/)。server-receipt 的 NOT_YET_RUN 是啟動時原始狀態，終態以 post-stop-observations 為準。

上傳的 wire-events 將合成 opaque id／folderId 以一致別名表示，避免 secret scanner 把高熵識別字當 key；沒有改 scanner／allowlist。原始 wire 留本地驗收證據，SHA256 `c21a17575208323b9fcd490d7cca7e43231af7230cdedf307d7ff8a6abb635b9`；首次正常 hook 因這些欄位拒絕的 redacted report 也保留。別名仍可比較 A/B 同 ID。
