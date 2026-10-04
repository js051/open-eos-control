# iOS 快門 idle timeout 與 close 次序的 wire characterization

基底：PR #201 head `f1183d156030d18b93f089f3386316beb59efa6b`。本分支新增一份共享 wire test source、本文件，以及 `project.yml` 既有 App unit-test target 的 source 引用。不改 production、不縮短曝光命令預算、不新增 timeout API，也不改媒體與合法長命令的設定。

## 目的與界線

`URLSessionCameraHTTPTransport` 的 request idle interval 為 10 秒，whole-resource interval 為 120 秒。前者收到新資料會重新計時；因此 120 秒是有限上限，不是無限 hang。此實驗只隔離 Foundation 的真網路行為及目前 Start/close ownership，不把現行延遲寫成理想產品規範，也不表示已修復。

新增 `URLSessionShutterDeadlineWireTests` 五個方法：
- 無滴流正向 control 在同一方法並行比較兩個 phase：完整讀到 request 後不送任何 response headers，以及完整 headers + 一 byte body 後靜默。兩者都必須透過 production transport 得到 `URLError.timedOut`，等待上限維持原 3 秒。test-only decorator 此次只將 requested idle 改成整數 1.0 秒，不偽造 response。
- direct CCAPI PUT / ver110 與 POST / ver130：peer 已完整讀到 `full_press`，HTTP 200 body 每 50ms 滴送一 byte，超過三倍設定的 1.0 秒 idle interval 時兩個 close 仍不能提前送 release。外部 barrier 完成合法 body 後，要求一次原 method/path、`af=false` release，兩個 close 都回 idle。
- 相同 direct PUT/POST 情境，也先等到超過三倍設定的 1.0 秒 idle interval，確認 Start 與兩個 close 仍 pending、transport 尚無完成結果、wire 尚無 release；保持 completion barrier 關閉並取消 Start task，要求 release 到達，不等待 body 完成。
- Desktop Bridge 的相同慢滴流／取消兩例；不廣告 event polling 能力，隔離 Start 後唯一的原 session DELETE，沒有 stop replay 或重複 DELETE。

兩分支都使用相同 pending checkpoint，取消不能只是對早已失敗的 Start 做 no-op。透明 decorator 只觀察指定合成 Start path 的真 `underlying.send` 結果，記錄 HTTP status/body byte count 或固定錯誤分類，不保存 headers、authorization、body 內容或原始 error 字串，也不改寫 response/error。正常開 barrier 的分支必須觀察到 production transport 成功回傳 HTTP 200 與完整 padded body 長度，之後才由 client closing guard 拒絕 Start（direct 為 `CancellationError`、Bridge 為 `sessionChanged`）；單純斷 socket 或截斷 body 不符合成功 oracle。取消分支則要求 transport 本身確實回報 `CancellationError` 或 `URLError.cancelled`，不能把任意 timeout／網路失敗當成取消成功。

固定 `deadline-wire` metrics 只輸出設定值、單調時鐘量到的秒數、status/count 與合成 outcome category：`no-drip-before-headers` 與 `no-drip-body` 各自的真 `underlying.send` elapsed、每個 case 的滴流 checkpoint，以及 Start 的完成／取消結果。1.0 秒仍只是 requested idle 設定，不能當成已測得的精確有效 deadline；兩個 OS 的 phase elapsed 用來檢查設定是否實際生效，不先假定 fractional rounding/clamping 或 body 階段的根因。不為此加入過窄的 wall-clock 成功斷言。

滴流 checkpoint 目標依公式 `1.0 × 3.25 = 3.25 秒`；只把等待該 checkpoint 的測試上限改成 `3.25 + 1.25 = 4.5 秒`，其中 1.25 秒是固定排程餘裕。這不是放寬 no-drip 的 3 秒正向 control；peer 最大 hold 8 秒、socket timeout 與 cleanup 上限都不變。

raw TCP listener 與每個 connection 使用不同 worker，另有 held-body 期間的 `/fixture-probe` 往返，使用與 client 同一個 production URLSession transport，驗證 peer 與該 transport 連線池仍能服務其他連線。測試使用精確 wire request 序列、actor 內 close-entry 訊號、completion barrier 與同步結果盒；XCTest 有限等待之後不再無限 await Task.value。fixture 有 8 秒 hold 上限、socket timeout、所有 active socket shutdown 與最多 3 秒 teardown join。所有值為合成 fixture，沒有真相機或 credentials。

同一個檔案只 `import OpenEOSCore`、使用 public API，自帶所需最小 endpoint/request fixture，不依賴其他 Core test helpers。Swift Package macOS tests 與 `OpenEOSControlTests` iOS Simulator target 都編譯這份 source，沒有複製測試或新增 CI job。兩個平台的 CFNetwork 執行證據必須分開紀錄。

## 不混淆的兩個問題

失敗 Stop 後的 Bridge fresh-status GET 也可能滴流占用到現行 resource 上限，App `.capture` 在整個 retry 中保持 busy。但它位於 `stopBulbExposure` 的 defer 釋放 `bulbOperationInFlight` 之後，core close 不等待該 GET；generation/revision guard 阻止舊回覆解除新 session 的責任。不能把 Android 的「fallback GET 挡住 DELETE」直接套到 iOS。

Start 自身 deadline 則影響正常曝光命令，必須另作產品決策。此分支不修改全域 10/120 秒 timeout，不測量完整 120 秒等待，也不聲稱已證明新的 deadline 或拍攝語意。

## 執行狀態與 Release Assessment

### 第一輪 0.4 秒設定的實際結果

[PR #202 CI 37214338750](https://github.com/js051/open-eos-control/actions/runs/37214338750)，head `05685ae81aa610f21c02d36efa8d5496956ea80a`：
- macOS Swift Package 成功編譯；原 228 方法通過，新 5 方法中 4 過、1 紅。no-drip 在 3 秒內未完成，清理取消時才回報 `URLError.cancelled`，elapsed 約 3.435 秒。
- iOS Simulator 的同份 App unit-test source 成功編譯；新 5 方法也為 4 過、1 紅。no-drip 同樣在 3 秒內未完成，清理取消時才回報 `URLError.cancelled`，elapsed 約 3.991 秒。
- 兩平台的 4 個 ownership/cancellation 方法都觀察到約 1.3–1.4 秒的 pending checkpoint，完成分支是真 HTTP 200、direct 258 bytes／Bridge 317 bytes；取消分支是真 `URLError.cancelled`。兩平台在這個範圍未見行為差異，但不能把 macOS 結果當成 iOS 的替代證據。

因此目前只建立了 body barrier／取消／exact-once close 的 wire 證據。正向 timeout control 失敗，**尚未證明 0.4 秒設定形成有效 idle deadline，也尚未由實驗證明滴流重置該 deadline**。不能把 4 過或清理取消包裝成整體綠燈／timeout 成功。

### 僅一次整數校準，待執行

本次 requested 1.0 秒、before-headers/body 並行 control 尚未編譯或執行；本機仍沒有 Swift/Xcode。若只有 before-headers control 通過而 body control 不通過，則結果支持進一步區分回覆階段，但仍不直接證明根因；若兩者都通過，才可結合同設定下 3.25 秒仍 pending 的滴流結果判讀 idle 行為。若任一 control 再失敗，先保留證據限制並停止猜測常數，不持續擴大 iOS 故障矩陣。

校準必須由精確 head 的 CI 分別驗證兩個 OS。預期每個成功慢滴流 case 約 3.25 秒加初始化/清理；安全上限與 CI 排程不是正常完成時間保證。此文件只記錄 wire 實驗，不宣稱整體 App/UI CI 通過。

本機只完成本次校準的 diff/whitespace、五個 public-API-only 方法與有界等待的靜態範圍檢查；這些不是 Swift 編譯或網路執行證據。本次校準未提交或推送，尚需照原規則執行最終機密掃描。

最新版本基準為 v0.10.0 Development Preview。Release impact：`none`，純工程 characterization，沒有 distributable product 變更。不合併、不發布；沒有實體 iPhone/EOS 證據。

參考：[Apple request timeout](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/timeoutintervalforrequest)、[Apple resource timeout](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/timeoutintervalforresource)。
