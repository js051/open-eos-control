# iOS 前景 JPEG 原檔準備：開發批次

後續的[逐次授權 Files 交付批次](ios-foreground-files-delivery-2026-10-13.zh-TW.md)在此私有準備基礎上新增外部複製。本文件保留原批次歷史與原驗證結果，不代表後續變更已通過 runtime。

## 原 P2 邊界

本批接續產品矩陣的「前景、明確啟用、bounded auto-import」，目的地是 App 內可在「最近準備的原檔」再次分享的副本。使用者仍須自行操作系統分享介面選擇外部目的地；不自動寫入 Photos／Files、不宣稱外部保存完成、不觸發快門或修改相機設定。

僅直接 CCAPI camera 模式與明確支援 strict transport 的連線可用。Bridge、USB、離線預覽及舊 transport 不因原有手動下載能力而自動獲得此能力。原 listMedia／mediaInfo／downloadMedia 相容行為保留。這些都是公開 EOS 契約，不含其他私有產品內容。

## 可判定的來源與限制

- 使用者明確按 Start 後，至少相隔 1 秒取得兩份相同的完整 ID baseline；baseline 內所有格式只記住、不回灌。30 秒仍不穩定則停止。
- 完整 inventory 是遍歷所有已宣告頁面與容器後的結果，最多 1,000 個 ID、512 次請求／容器、每個 JSON 2 MiB、整次 30 秒。超限、格式不明、部分失敗均不回傳「完整」前綴。這不等於相機記憶卡的原子快照。
- baseline 後約每 5 秒讀取完整 inventory；只有第一次出現的 JPEG ID 可入列。ID 以原始 UTF-8 身分去重；記住 RAW／影片，避免日後 metadata 變動把舊 ID 當新 JPEG。
- 每次 run 最多記住 4,096 個 ID、等待 20 個 JPEG、保留 100 個結果。新 ID 只表示在 baseline 後首次觀察，不證明由某次快門造成。
- 同一項須在 30 秒內取得兩次相隔至少 1 秒、相同且為正整數的新鮮 filesize；變動會重置觀察間隔。缺少 size 不回退到 listing 舊值。
- 下載只有一個 canonical GET，不跟隨 redirect、不嘗試原檔變體；HTTP 200、無 Content-Range、串流上限與最終 exact bytes 都需成立。長度吻合不等於來源 hash 或可解碼保證。
- 先以預期大小檢查 App 副本容量，再發原檔請求；保留時仍檢查實際容量。這不是跨程序容量預留。既有 100 筆／512 MiB 歷史上限仍有效。

## 操作、取消與所有權

前景讀取在既有手動操作、素材載入／縮圖、錄影、Bulb 或需釋放快門時等待；讀取持有 media busy 狀態，避免新的普通相機操作同時進入。必要的快門安全釋放路徑不被替換。這不是宣稱所有 LiveView/event 背景讀取都停止。

Stop 與進入背景會取消 run，等目前不合作的 I/O 真正返回才允許重新開始；不自動恢復。Disconnect 同步退休 owner，舊回覆與 defer 不得更新或解除新連線的狀態。每次 await 之後再次核對 owner／session。

beginItem 已建立但原檔 GET 尚未開始時取消，留下取消或冷啟動 UNKNOWN 的真實紀錄，不多發下載。已確實保留的副本即使 Stop 正好在回覆返回時到達，仍計入準備成功。舊 run 可清理自己擁有的檔案，不能清掉新 run。

保留副本失敗時，已取得的有效暫存原檔仍可明確分享，run 停止並顯示儲存問題。暫存最多一項；重新開始或斷線會退休該 run 的暫存，因此需先分享。歷史記錄的 prepared 事實不因後續分享、缺檔或容量問題被改成「相機下載失敗」。

## 驗證狀態

新增來源涵蓋 plan 邊界、受控 controller 取消／replacement ownership、真 CameraAppState／CameraSession 接線、store 容量 admission、strict CCAPI inventory／fresh-size／single-original 及真 loopback URLSession transport。

本地沒有 Swift／Xcode，本地 Swift 編譯／runtime 均為 **NOT_RUN**。遠端首輪 `80eb0b3` 已編譯，App 312/312 通過（含新增前景 ownership 接線），Core 300/301、UI 28/29；整個 run 為 FAILURE，詳見下方。來源審查、語系鍵對齊與 whitespace 通過不能替代 runtime。原正常必要 CI 保留，完整 App／真機／系統分享 provider 驗收依既定方向在功能開發後集中。沒有跨程序自動續傳、背景傳輸或 exactly-once 保證。

## 同批原 UI 失敗收斂

### 首次前景批次 Core runtime

Head `80eb0b3`／CI `38110150085` 已實際編譯並執行 Core 301 tests。新增 chunked overflow 清理測試的一個方法有 3 個連鎖 assertion failure：2 秒未收到 55-byte prefix 的 progress，接著無法找到 marker temp、unwrap 失敗；其餘方法通過。原 fixture 只送未終止的短 chunked body，再等 URLSession 必須立即發布該整段 prefix 才送尾段，形成未受 API 保證的循環門控。此處沒有以增加 timeout 重跑。

修正候選改成完整 chunked response（無 Content-Length），實際 decoded body 比 cap 多一 byte，要求 production byte-limit 錯誤；使用預設不變的 internal temporaryDirectory injection，先證明同一 production task 的 exact-size chunked 成功會在 private 目錄產生檔案，再檢查 overflow 後僅自有目錄清空。URLSession 可能合併整段 callback，故這個負例不保證 overflow 前已寫入 partial body；原 controlled transport publication／既存 destination 不變負例及 final-byte cancellation cleanup 仍保留。新候選 runtime 尚未執行，首次失敗保留。

### 同次 App／UI 終態

`80eb0b3` 的 App 312/312 通過，UI 28/29；唯一失敗仍是長挑片旅程，這次在 known folder Apply 後 line 619 搜尋 `media-actions-preview-001`。artifact `11692015945`，194,423,209 bytes，SHA-256 `aa14f07d40f87179480c24ff78f1e4cc72649ef633a0e06d13934cd57f0342f3` 已下載核對並查看真 PNG。

trace 的 ScrollView／viewport 為 `(0,180.667,402,693.333)`，三次有效 clipped drag 後 target 才 materialize，final frame `(140,904,48,48)`，仍在底874之外。原 timeout 8 秒，第三 gesture 返回後已約9.79秒；三段容器查詢共0.79秒，gesture/event共3.78秒、post-event idle共5.22秒，不能將查詢 cache 當成充分解法。真圖顯示 Recent originals／日期與第一張 RAW 卡片，action還在下方，不是此前 rating Form 被header蓋住。

新搜尋策略只作待 runtime 驗證的候選：generic target尚無有效frame時，沿原clipped座標以fast drag release、不加terminal hold，允許discovery慣性；已有frame則沿原方向/距離精確停住，cameraActions速度與hold不變。絕對8秒、業務斷言與原座標安全檢查保留，不在deadline後額外補拖。不能預先宣稱已證實更快或整個UI已通過。

PR236 `39d2de7` 的 CI `38106141493` 保留 FAILURE：App 281/281，UI 27/28；唯一失敗為 rating Form 的五星選項不可互動。artifact `11690083759`，167,194,881 bytes，SHA-256 `dfff2b4c7cd767fa039e35eb12789170b554c22a544daa1e0da8d947955aa56f`。

真實失敗 screenshot 顯示 Form 捲至 Unknown rating／說明；五星 row 的 AX frame y=25.33～110.67，在 sheet 導覽列後方。舊 helper 僅找 ScrollView，fallback 採整個 App frame，錯把被固定 chrome 蓋住的 row 判為無須反向捲動。此次候選同時辨認 collection/table/scroll，優先選擇 target 的祖先，扣除可見 navigation/tab/toolbar；不再拖曳整個 App。新增真實 frame 的純幾何反例，保留原 8 秒、業務斷言與 28 個既有 test bodies。不存在的 lazy row 先確認 exists，避免讀 identifier 造成額外查詢失敗。

此為來源與圖像支持的候選，尚未證明 runtime 已修復，也不倒推更早失敗的根因。release HOLD 不變，不以本批開發宣稱 P2 或完整產品已驗收。
