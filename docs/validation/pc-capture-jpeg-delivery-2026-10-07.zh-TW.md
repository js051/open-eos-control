# PC 單次拍照、最近素材與原檔保存候選

日期：2026-10-07 UTC。基底為 accepted main `1137bee3a35db89bc8fdb1d45f6529e7cafa0e7e`，tree `f9fdef829d189aaaf70fcd37a942d4c987831d47`。本批對應[產品流程矩陣](../product-workflow-acceptance.zh-TW.md)的 P1 拍攝確認及 P2 原檔交付；目前只有下列本地證據，尚未達到 PR ready，也沒有實機驗證。

## 使用者流程與查找邊界

- 單次 still 命令送出前保存已載入素材、目前最近素材及既有最近候選的已知範圍，不額外掃描相機建立 baseline。
- 命令確認後沿用最近 8 候選與 250／750／1,500 ms 間隔，最多四輪查找。先排除已知素材，再保留相機回傳順序；本次 still 查找排除明確影片，保留 RAW 及其他既有靜態格式的能力旗標。一般連線的最近素材仍包含影片。
- 舊 A／B 換序不能把 B 顯示成新結果；新素材在第二筆、日期較早也能找到。四輪耗盡後保留「先前可見素材」入口，另顯示尚未找到與「重新查找」。
- 無法讀取清單與成功讀取但尚未找到有不同的英／繁中提示。重新查找只讀素材，不重新拍攝、不變更快門 AF；重複點按沿用同一查找工作。
- 晚回應不能發布到新 session／拍攝嘗試，或清除新查找的 loading 狀態。成功快門確認不因縮圖或素材查找失敗而改成失敗。
- 介面明示每次最多檢查相機回傳的 8 筆，以及新出現的素材也可能是以前未顯示的舊檔。「新可見素材」不保證由這次曝光產生；此有限清單也不是全卡完整證據。8 是候選數，不是所有 Canon HTTP 請求的總上限。

## 已執行的因果與回歸檢查

測試直接載入 production `app.js` 函數，復用正式 `media-library.js`／`media-transfer.js`；沒有新增 production 測試開關或替代實作。

1. **20:27:10，原基底：9 例，7 紅／2 綠。** 七個實際斷言失敗為：已載入 A／B 換序誤報、未開相簿但已觀察候選換序誤報、新第二筆被遮住、四輪後缺少 NOT_READY、空卡只查一輪，以及舊第四輪回應清除新 attempt／session loading。Blob 與 direct-writer 的原檔 bytes／短檔拒絕／重下載不重拍兩例為原本就通過的控制。此輪 Node `--test` 使用一個測試檔隔離 child，沒有並行測試檔。
2. **20:36:00，修正後：13／13 通過，無 failure／skip／cancel。** 改為直接單 Node 進程執行同檔，另包含 RAW 順序、still 排除影片、一般最近素材保留影片、清單讀取失敗四例。
3. **20:45:21–20:45:22，完整既有 `npm run test:modules` 通過。** 九個模組腳本按原順序執行，包含新 13 例；不把本次重跑與上一輪 13 加總。變更的三個 JS 入口語法檢查、兩個 Python 檔案 AST 與 `git diff --check` 同輪通過，八個來源檔案執行前後 SHA-256 一致。

本地 Node 為 22.23.3。上述通過時 `app.js` SHA-256 為 `c86d049a6fa4218c22788f634c197195b42a409ffdb85773aebdcfceacbb020c`，focused 測試檔為 `4a3dd408a96feba4a8bd7299749993a6c95c0b1c67f3a72d3269dc9cfa29d330`。

原檔控制使用彼此不同的 synthetic original／preview／thumbnail byte 序列，驗證正式下載函數取原檔路徑、短回應不發布 Blob／不關閉成功目的檔，明確重試仍只有一次快門。direct writer 為受控檔案 handle；這不是 JPEG 解碼、真 OS picker 或真磁碟保存的通過證據。

## 真 HTTP／browser 與未完成 gate

`capture-review.browser.test.js` 已接入正常 `test:browser`；相機測試入口 `capture_review_browser_server.py` 提供獨立 Canon-shaped HTTP，PC 經真 Bridge 執行。它準備七個場景：候選換序、新第二筆、耗盡與重查、清單失敗恢復、Blob 原檔／短檔重試、direct writer 原檔／短檔重試、目的檔建立等待時的普通 UI 斷線重連。相機提供可解碼且尺寸、bytes 均不同的縮圖／預覽／原檔。

本地只嘗試最後一個 picker 場景，兩次都在 Chromium 建立任何 page／UI 前失敗：

- 20:40:05–20:40:11，一般 task sandbox，exit 1。
- 20:43:18–20:43:25，同一命令在受支援的雲端執行模式仍於瀏覽器啟動前失敗，來源不變，exit 1。

兩次均為 `process_singleton_posix` 的 `socket() failed: Operation not permitted`；第一次另有 crashpad 訊息，第二次另有 ptrace 拒絕。Bridge／相機伺服器已啟動，但沒有取得任何 browser 產品斷言。沒有安裝或變更系統／安全參數，也未把此環境失敗寫成產品紅測。

此嘗試用既有系統 Chromium 154.0.8037.57、Playwright 1.62.1，與 CI 鎖定的 Playwright 1.62.0 bundle 不同；測試只有明確可選的 executable path，CI 預設不變。Python 為 3.12.14，uvicorn 0.52.1、FastAPI 0.141.1、Pillow 12.3.0、Pydantic 2.13.4。環境沒有 pytest，六項既有靜態 UI 契約尚未經原 pytest 入口執行，沒有自造替代 runner。

因此七個 browser 場景、既有 browser／Python／lint 與 exact-head CI 都仍需正常環境驗證。靜態觀察到目的地 picker 等待後下載 URL 讀取當前 session，但普通 UI 的實際可達性尚未證明；`downloadMedia` 及其 session owner 尚未更改，不能宣稱已修復或已驗收此保存風險。

## 範圍與發行判定

不包含 REC／Bulb 擴充、自動傳圖、跨程序續傳、手機 Bridge CCAPI 入口或新 Serein 協定。沒有新增相機／手機／Windows 實體裝置證據。

Release Assessment：比較來源為上述 accepted main（宣告版本 0.13.0）；2026-10-07 20:48 UTC 核對 GitHub，最新公開版本仍為 [v0.12.0 Development Preview](https://github.com/js051/open-eos-control/releases/tag/v0.12.0)。v0.13.0 尚未發布，其固定候選 `fb4f555` 不納入本批。此批修復既有 PC 拍後查找與失敗恢復，建議 impact 為 `patch`，不修改版本或發行通道。Browser 完整旅程與 picker 所有權仍未閉合，不能據本地 module 綠燈發布或標成 PR ready。
