# PC 前景 JPEG 自動交付開發批次

## 已規劃範圍

接續產品計畫 P2「拍後自動交付」，沿同一 PC 開發草稿的挑片、多選、目的地及傳輸紀錄。僅限 CCAPI、已確認素材瀏覽／原檔下載能力、前景分頁、非錄影／Bulb。使用者必須明確啟用並選擇支援 File System Access 的瀏覽器資料夾。沒有背景喚醒、持久化 folder handle、自動重連／續傳、相機寫入或私有消費端規格。API 不支援的瀏覽器保留原手動單檔流程。

## 來源完整性與相容契約

舊 `/media` 的短清單、`sizeBytes` cache、最近照片入口都不是完整或穩定證據。本批在既有 Bridge GET 上增加可選 query，預設舊客戶端行為保留：

- `/media?inventory=strict&limit=1000`：只對 CCAPI 驗證相機回報的所有可達 contents 容器／頁面；成功才回 `X-Open-EOS-Inventory: complete-v1`。最多 1000 identities、512 requests／containers、每 JSON 2 MiB；第 1001 個項目即失敗，不截短冒充成功。頁數型別／path／同源 canonical identity／重複 JSON keys／partial framing／redirect／busy／auth／預算耗盡均不得產生 positive evidence。循環與跨容器重複去重但仍窮盡其他可達節點。只有不支援 count/order 的限定 HTTP 狀態可採有預算的替代讀法。
- `/media/{id}/info?fresh=true`：成功回 `X-Open-EOS-Fresh-Metadata: v1`，但這個 header 不代表有正確檔案長度。先清除該項 cache 的 size/type，只接受本次 JSON 正整數 filesize；缺值、0、bool、浮點或其他無效值回 unknown（wire sizeBytes=0），不沿用舊正值。只讀該 canonical ID，不隱式走 legacy listing。
- `/media/{id}?attempt=single`：只 GET 一次 canonical original，不試 kind/type variant，不跟 redirect，拒绝 HEAD／Range／partial response；成功完整 staging 後回 `X-Open-EOS-Original-Attempt: single-v1`。預設原檔 fallback／Range 與其他舊客戶端能力保留。
- 新前端仍獨立要求 HTTP 200、無 Content-Range、positive header，原檔另驗 fresh stable byte count 後才 close；忽略新 query 的舊伺服器缺 header 時即停止。strict ID 要求 decode→canonical encode round-trip，不能以多餘 base64 字元製造同檔別名。

完整 traversal 只代表窮盡相機本次回報的可達拓撲，不是原子實體記憶卡 snapshot；相機未回報的容器／拍攝中變動仍是外部限制。兩次相同 baseline 也不能消除這個限制。

inventory/fresh metadata 的 30 秒是 cooperative observation budget；lock wait、body read1 chunks 與 parsing 之間檢查時間，單次 socket inactivity 上限 2 秒，blocked read 可有超時偏差。urllib response-header parsing 不能提供嚴格 whole-header deadline，不能宣稱 hard wall-clock bound。沒有用不受控 worker thread 假裝中止。原檔沿 Bridge 既有 staging／600 秒 stream inactivity 行為；瀏覽器 Stop／abort 不保證已在 Bridge 內的相機讀取立即停止。

## 前景工作流與界線

1. 目的地明選後，在相同連線物件內建立兩次完整且相同 ID 集合的 baseline。baseline 改變或證據不足即停止，明確重新啟用才能再做；舊檔不回灌，baseline 不建立目的檔案。
2. 空閒時最多每 5 秒讀一次完整 inventory；所有新 IDs（包括 RAW／影片）都記入有界集合，避免消失後重現／完成後重傳。最多 4096 known IDs、20 個等待 JPEG；溢出在 metadata fan-out 前停止。
3. 只對 baseline 後新增 JPEG 讀 fresh info，取得至少相隔 1 秒的兩次相同正整數 size 才交付。缺值或變動重等，候選觀察後最多 30 秒；listing 舊 size 不參與。待處理 ID 消失即停止，不猜測另一檔案取代。
4. 一次一檔沿既有隔離 batch directory/writer，actual byte count 必須等於 fresh stable size；header 的 Content-Length 不能覆盖這個條件。超量不再寫入，短檔／不同長度不得 close 成功。close 成功才產生已儲存 receipt，第一個失敗／未知結果即停止後項，不自動重試。
5. 監看空閒不占用拍攝 controls；實際 inventory／metadata／save 由 exact owner 保留 busy，與手動拍攝、載入、預覽、單檔／批次下載及上傳互斥。每個 adapter 呼叫前再次檢查 owner／manual busy；observer 同步改狀態不得穿透。
6. Stop、分頁隱藏／離開、disconnect、session 替換或失去能力都停止後項，取消不合作操作尚未落定則維持 stopping。舊 owner 的 late close receipt 仍是真實結果，但不得更新新連線畫面／清除新操作。重新可見或連線不自動恢復。

結果只顯示最近 100 筆及累計已保存數，沿同一 bounded IndexedDB 紀錄已開始的嘗試；不是跨程序 pending queue 或 exactly-once delivery 保證。資料夾／檔案 API 沒有 atomic exclusive create，既有碰撞拒絕與外部競爭／partial 殘留限制保留。close 不是 fsync、來源 hash、光學清晰或外部檔案永久存在證據。

## 目前驗證

同 ID 若被相機覆寫成新照片，本輪去重仍視為已知，不自動重新下載；無可證版本欄位時不能猜測新內容。JPEG 依相機 listing 路徑／副檔名分類，長度吻合不等於完成影像解碼或驗證來源 hash。

- Backend strict controls 與 production urllib synthetic HTTP 測試涵蓋 malformed count/page/path、cache 保留與 fresh 清除、budget、slow drip body、redirect、truncated original、positive headers 與 legacy defaults。獨立 review 發現 canonical ID alias／single Range 邊界，新增路由反例先得 5 個失敗，再修正為 41 方法全過。
- 純 controller 初版 19 控制通過；獨立 review 發現 observer-reentrant busy 後仍能 I/O，補三個 operation 邊界控制及檢查，20 方法通過。來源 review 關閉 backend/controller 範圍；UI adapter 與完整正常 CI 仍另驗。
- Backend strict 41＋既有 CCAPI recovery 29 個方法組合通過（70/70，單 CPU，17.69 秒）；第一次 runner invocation 缺 package context 的 ImportError 保留，修正呼叫方式後通過，未改 production 以處理工具錯誤。Ruff 全 bridge 通過。UI 來源初次完整 25 個 Node 腳本通過（3.97 秒）；之後 reviewer 指出 visibility return、舊 reservation 落定 rerender、thumbnail demand 的可用性問題，正在补齊，不能沿用此結果作最終新 source 通過。
- 最終來源補齊上述三個 UI 問題：回到前景／BFCache 只恢復明確 Start，不自動重啟；舊 reservation 落定只釋放自己的 busy 並刷新目前 controls，不覆寫新結果；被暫緩的縮圖從目前有界頁面重新觀察，latest thumbnail 等 actual reservation 落定後重查 session／generation。新增 owner 控制共 25 項，完整 25 個 Node 腳本再次全過（4.30 秒、單 CPU）。
- 第二次 UI review 發現同一 Start 可用性問題也出現在 manual latest refresh／thumbnail／details／preview 的結束或關閉：補既有 leaf render 路徑刷新及 3 個控制，owner 共 28 項；最終 25 個 Node 腳本全過（4.55 秒、單 CPU），來源 hash `67196231df58dffc3a75323b135393bfccefc1a07e4fb3aef289d625a9cc055e`。独立來源 review 確認沒有 renderer recursion、沒有 auto-restart，UI 範圍已無剩餘阻擋。
- 另以真 HTTP peer 證實重複 Content-Length 被舊 dictionary 合併會丟失中間矛盾值，以及原 permissive 數字 parser 可接受 malformed length；strict-only 路徑改驗 raw duplicate headers、ASCII decimal／HTTP OWS、TE+CL conflict，並保留相同 combined length 正例。修正反例後最終 backend strict 44＋既有 recovery 29 = 73/73 通過（17.19 秒、單 CPU）。舊客戶端解析未改；此 strict framing 來源經獨立 review 無剩餘 findings。
- 新 browser 來源沿既有真 Bridge HTTP＋Canon-shaped synthetic peer，未改 simulator 或必要 CI 分類：英繁中 390px／200% baseline 排除舊檔、一次人工拍攝後一次新 JPEG 原檔、精確 closed bytes、fresh evidence、重載不自動啟用；另覆蓋缺 inventory header 舊伺服器與 writer 暫停後 Stop。檔案平台 handle 是 synthetic；不是 OS picker/provider 或實機驗收。
- 首次前景功能 head `d92c27d` 正常 CI `38110691618` 失敗：英繁中 baseline→新 JPEG、缺 inventory header 拒絕均通過，但取消旅程在 line 892 等待 Start 後 `watching` 的原 12 秒期限到達，尚未執行 capture／writer／Stop 斷言。artifact `11691243240`（5,405,233 bytes，SHA-256 `873b94a2486ee6df6016204a2c05cae800a5e660fbed7e2490d5eeb18afea3f3`）真實畫面仍 Off、0 known，只有兩個一般 listing，沒有 strict inventory／fresh metadata／original request 或 page error。這證明該次未進入前景讀取，不證明精確的點擊／忙碌狀態交錯。
- 同次 Windows bundle、JVM／APK、secret gates 通過；Desktop browser gate 失敗後，後續 backend contract／package 步驟未跑，不能報整批通過。正在收斂 gallery 初始縮圖與 Start 前置的 fixture；保留 production 互斥 guard、所有業務斷言與原 timeout，修正後 runtime 仍待新 head 驗證。
- 後續候選只調整前景四旅程的前置：先將兩個既有 JPEG 的真 gallery 縮圖分別捲入並確認解碼完成，再確認 latest 縮圖與 Start 可用，單次點擊並驗 pickerCalls=1。新受控 Node 測試證明初始縮圖 I/O 期間 Start 必須拒絕，落定後只恢復按鈕，不自動啟動；下一個明確 gesture 可建立 baseline。完整 25 個 Node 模組通過，owner 29/29；browser syntax 與 diff 檢查通過。這不是已重現原次點擊交錯或新 browser runtime 通過，失敗畫面另增加 importer／picker／縮圖狀態以便後續判讀。
- 完整 App／實機驗收依使用者指示開發後集中。PR237 前版 `644ced49` CI `38093214237` 全適用成功，只驗舊挑片／批次／紀錄，不是本次自動交付成功證據。原必要 gates、失敗紀錄與 release HOLD 保留。
