# PC 已載入素材的日期區間

日期：2026-10-09 UTC。基底為 accepted main `65fbabf00c78924228ec7cbfe22d733ef806094f`。本批接續產品流程稽核 P2 的日期挑片缺口，目前為本地實作與驗證，尚未取得本批 exact-head CI 或 browser runtime 證據。

## 使用流程與日期意義

媒體頁新增英語／繁中「素材日期區間」對話框。開始與結束皆必填，包含兩端且可為同一天；空值、無效日曆日期或反向區間不套用，也不改變目前頁碼。取消、關閉與 Escape 保留原篩選；套用或清除回第一頁，保留素材類型、排序與 Recent／All 範圍。斷線或重新連線清除區間與尚未提交的草稿，舊 session 的對話框不能套用到新連線。

篩選只處理已載入素材，不新增 listing、逐檔 metadata 或相機控制請求，也不自動全卡掃描；重新顯示卡片仍可能觸發原有的 lazy thumbnail 載入。活動摘要同時顯示符合數、已載入總數與日期不明數；原來的載入中、讀取失敗與 Recent 不完整提示繼續顯示。未知日期只在區間生效時被排除，清除後恢復。零符合與相機空相簿分別顯示。

這是「素材日期」，不能一概稱為拍攝日期。現有 CCAPI listing 未必提供日期，既有詳細資料讀取可能以 last-modified 值補上；USB 或 host 檔案的值也不保證是原始拍攝時間。本批不猜檔名日期、不新增補查。剛載入的 CCAPI 素材可能全為日期不明，篩選因此得到零項，這是需要明示的資料限制。

帶 offset 的時間使用瀏覽器顯示時區分日；未帶時區的時間保留瀏覽器原有 local-time／DST 解讀，不推定相機時區。純日期保留回報的日曆日，顯示時不虛構午夜。共同 parser 驗證年月日、時分秒與 offset，拒絕 Date.parse 原本可能正規化的無效日期。支援 ISO 日期、ISO 時間與 compact PTP 時間；不支援的格式歸為未知。

## 既有操作的邊界

一般預覽導覽遵守區間。明確從拍後回看開啟區間外的新素材時，該素材獨立顯示為 1／1，不偷偷混回篩選清單。套用／清除不改變正在下載的項目、檔名、寫入對象或取消控制。PC 目前是單檔操作，本批不新增多選、持久化區間或下載排程。

## 驗證狀態

- 共用 parser／range 測試先在原來源呈現缺少新 API 的紅燈，再於 UTC、Asia/Taipei、America/Los_Angeles、Pacific/Apia 通過；含閏日、無效日期、offset 跨日、純日期與未知值。
- 獨立來源 review 發現純日期的 UTC carrier 與瀏覽器顯示日可能造成分組交錯。新增洛杉磯時區反例先紅，再修為先按顯示日期、同日才按時間排序；新舊排序方向控制通過。DOM 旅程也加入跨日分組連續性斷言，尚待執行。
- 17 個直接執行 production 函數的 UI 狀態測試通過，涵蓋草稿取消、session 物件歸屬、頁碼、警告、預覽與下載狀態保持。這些是 synthetic source-level 證據，不是瀏覽器執行。
- 原入口的全部 11 個 module 腳本串行通過，未刪減既有 assertions。
- 獨立來源 review 的上述排序缺口、空鍵盤斷言與文件範圍修正已收斂，沒有剩餘 blocking source finding。修正後再次通過四時區 helper、完整 11 個 module 腳本，以及所有 production JS 與四個變更測試檔的語法檢查。
- 完整 Bridge `pytest -q` 通過（29.88 秒），Bridge 與 validation scripts 的 Ruff 通過。保留原有 Starlette deprecation 與 OpenAPI duplicate-operation-ID warnings，沒有修改依賴或警告設定。
- 既有兩套 browser suite 已加入普通 DOM 旅程，涵蓋雙語、鍵盤、200% 文字的直橫向小視窗、日期篩選與清除、詳細資料補全、拍後回看，以及下載期間改區間／取消。寫入 fixture 使用最多 20 秒的 gate，成功路徑比對精確 bytes；只完成語法及 fixture 來源核對，尚未執行 browser。本地 Chromium 的既有環境阻礙未繞過，正式 browser 與必要 gate 待本批正常 CI。畫面截圖尚未檢視，不能宣稱小畫面或大字級已驗收。

本地 Node 24.19.0，正式 CI 仍沿用原 Node 22；未更換依賴。輕量檢查限制單 CPU、60 秒與觀測 aggregate RSS 640 MiB 停止線。完整 module 輪次 1.62 秒、peak 約 111 MiB；17 個 UI 狀態案例約 0.20 秒。後續最終來源與結果另補，舊結果不自動驗證新修改。

### 首輪正常 CI 與鍵盤測試修正

Head `34b71e21bcb2799547295ed2ca89051248c4de4b` 的 [CI 37962105591](https://github.com/js051/open-eos-control/actions/runs/37962105591) 必要 gate 失敗。JVM／debug packaging、Windows standalone、secret／workflow 通過；原 classifier 判定未變動的平台 skipped，沒有修改 gate。Desktop browser 在日期旅程的第一組大字級鍵盤巡覽中，於 2026-10-09 16:59:25 UTC 失敗：`dialog.contains(document.activeElement)` 為 false。後续旅程與該輪 desktop backend／packaging 尚未完成，不能記為通過。

原 artifact `11632871402` 為 762,486 bytes，SHA-256 `ce3993d4dfeb16834cab232bbc41216680bc8514243588ebd5e7c7898fc228de`。其中只有前置案例的 11 張圖，沒有失敗當下的日期對話框畫面或 focus trace，故無法從此輪確認焦點究竟去了哪裡。

[HTML sequential focus navigation](https://html.spec.whatwg.org/multipage/interaction.html#sequential-focus-navigation) 允許在文件邊界轉移至瀏覽器控制；原本要求每個樣本必須位於 dialog 內，超出原生 modal 的保證。獨立 review 後只修正測試：仍要求每步為 open／`:modal`；若焦點不在 dialog，僅允許文件失焦且 activeElement 恰為 body／documentElement 的根節點，任何背景 App 控制仍失敗。這只辨識「文件失焦且回報根節點」，不把它當作已確認的瀏覽器 chrome 原因。

保留 28 次原生 Tab 並檢查最後一次的結果，六個必要控制必須在文件有焦點時實際走訪；幾何、可操作性、Escape、回到原開啟按鈕與 12 秒 timeout 不變。每步先保存有界 JSON trace，開始巡覽前保存畫面；沒有新增 App focus-trap、關閉安全檢查或延長 timeout。修正後的 browser runtime 仍待新 exact-head CI。

## Release Assessment

- Latest release baseline：v0.12.0 Development Preview；目前來源 metadata 0.13.0 不代表已發布。
- Target release channel：Development Preview。
- Proposed impact：minor，新增向後相容的 PC 日期篩選操作。
- User／tester value：在目前載入範圍內按日期挑片，清楚分辨未知日期、零符合與載入失敗。
- Unresolved blockers：本批 exact-head CI、DOM 與畫面驗收待完成；既有 frozen v0.13 HOLD 及 PR #219 原因 UNKNOWN 不因本批解除。
- Physical-device status：pending；沒有相機、手機或 USB 實體驗收。

本批不改版本、required checks、權限、Android／iOS 來源，也不包含其他待批准 PR 的合併。
