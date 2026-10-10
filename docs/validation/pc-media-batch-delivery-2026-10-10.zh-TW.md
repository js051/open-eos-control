# PC 多選與循序原檔交付

本批接續原 P2「找到並挑出要交付的素材」與「原檔確實到達目的地」。基底為挑片草稿 #237 的 `bb95aeb19d7b7eef5d853e9898e471308208e32f`，不是已合併 main。功能與測試來源已備妥，本地單元通過；已發布至同一草稿 PR #237，但首輪正常 CI 的 browser fixture 失敗；完整 App 驗收仍後置。

## 使用者流程與邊界

選取使用原始素材 ID，RAW/JPEG 各自保存身分。日期、評分、資料夾、類型、排序與分頁改變不取消選取。「選取符合項目」只涵蓋目前已載入並符合篩選的素材，不宣稱選取整張卡。切換 Recent 或部分清單刷新時，已選但未出現在新清單的項目保留本次連線的快照與明確提示；缺席不等於已被刪除。成功刪除的項目才移除選取，連線替換清空選取。

批次保存由使用者直接按鈕操作開啟資料夾選擇器，只使用其明確選取的目錄，在其中建立本批唯一子目錄。沒有儲存路徑輸入、持久化目錄 handle、自動授權或相機刪除。瀏覽器未提供 File System Access 目錄 API 時，批次顯示不支援，既有單檔操作保留；不以多個 anchor 下載冒充已保存。

File System Access 的目錄／檔案建立不是原子 exclusive-create。本批拒絕偵測到的既存目錄、檔案或清理後撞名；唯一子目錄降低碰撞，但不能保證外部程式同時建立／替换內容時完全沒有競爭。失敗後不任意刪除目錄或檔案，可能留下空目錄、placeholder 或結果未確認的檔案。使用者應查看該批次目的地；不自動重試可能已完成提交的檔案。

## 完成、失敗與取消

固定本次選取快照與連線物件，循序使用既有原檔 GET，沿用串流與已知長度檢查。每項只有 writer.close 成功後才記完成；這是瀏覽器 API 的寫入完成證據，不是硬體 fsync、來源 hash 或可解碼保證。沒有隱式 metadata/listing fan-out。

第一個失敗、取消或連線退休停止後續項目。摘要區分已完成、當前失敗／結果未確認與未開始；後來的取消不抹去已確認完成的檔案。取消時仍在進行且不合作的 write/close 必須等待其結束，不能提早釋放同一批次狀態。舊連線晚到的進度、結果或 finally 不得污染新連線。

Bridge 現有原檔 GET 先把相機原檔完整暫存再傳回瀏覽器。因此本批取消可停止後續項目、取消瀏覽器請求並處理目前目的檔，但不能宣稱已立即停止進行中的相機 I/O。關閉預覽與取消保存互相獨立。

本批不包含跨程序恢復、HTTP Range 續傳、背景同步、持久化傳輸紀錄、批次評分／刪除或私有消費端整合。

## 驗證狀態

- 純選取／隊列 26 項、目的地 mock 40 項、production callback ownership 17 項皆通過。獨立 reviewer 另複跑上述三組並核對來源。
- 全部 22 個 Node 模組腳本已逐一通過。首次 aggregate 因舊 VM fixture 未載入新增 production helper 失敗，補齊依賴後原 assertions 通過，歷史日誌保留。首輪 head fe8c4b16b13207fb0d49e489f7300c3a531590c0 的 CI38090551549 中，Node 全過；browser 鏈在既有日期旅程先失敗，後續 Bridge pytest／打包與新增 batch browser 尚未執行，不沿用 #237 舊綠色結果。
- 四個新增 browser 來源案例：兩語言窄畫面 200% 字體、隱藏選取、原檔 bytes 與 close 完成；取消第一項寫入後停止後續 GET；不支援目錄 API 時保留單檔操作且不發出批次請求。尚未執行，mock 目錄不等於真實 OS picker／磁碟驗收。
- 完整 App 人工／實機驗收按使用者指示開發後集中。

## Release Assessment

最新已發布基線為 v0.12.0 Development Preview。本批新增 PC 多選與批次保存能力，proposed impact 為 minor。尚未完成本批驗證，不合併 main、不發版；既有 release HOLD 保留。沒有實體相機、真實 OS 目錄選擇器或硬體耐久性證據。

API 參考：[目錄選擇器](https://developer.mozilla.org/en-US/docs/Web/API/Window/showDirectoryPicker)、[可寫入檔案串流](https://developer.mozilla.org/en-US/docs/Web/API/FileSystemFileHandle/createWritable)。目錄 API 需安全來源與使用者互動，且非所有瀏覽器支援。

## 首輪 browser fixture 修復

2026-10-10 22:14:22 UTC，desktop job114325952776 在 ccapi.browser.test.js:623 出現 strict mode violation：原 selector `.media-card [data-media-id="DATE_ONLY.JPG"]` 同時命中新 checkbox 與原 preview button。修復只限定原本要點擊的 `button.media-card-preview`，保留原日期／預覽／取消全部檢查；其他 browser 檔沒有同類寬 selector。語法與 diff 檢查通過，修正後 browser runtime 尚待下一個完整正常 CI。原失敗記錄保留，不稱產品或新 batch DOM 已通過。
