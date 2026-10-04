# iOS 快門 idle timeout 與 close 次序的 wire characterization

基底：PR #201 head `f1183d156030d18b93f089f3386316beb59efa6b`。本分支新增一份共享 wire test source、本文件，以及 `project.yml` 既有 App unit-test target 的 source 引用。不改 production、不縮短曝光命令預算、不新增 timeout API，也不改媒體與合法長命令的設定。

## 目的與界線

`URLSessionCameraHTTPTransport` 的 request idle interval 為 10 秒，whole-resource interval 為 120 秒。前者收到新資料會重新計時；因此 120 秒是有限上限，不是無限 hang。此實驗只隔離 Foundation 的真網路行為及目前 Start/close ownership，不把現行延遲寫成理想產品規範，也不表示已修復。

新增 `URLSessionShutterDeadlineWireTests` 五個方法：
- 無滴流回覆必須透過 production transport 得到 `URLError.timedOut`；test-only decorator 只把 request idle interval 改成 0.4 秒，不偽造 response。
- direct CCAPI PUT / ver110 與 POST / ver130：peer 已完整讀到 `full_press`，HTTP 200 body 每 50ms 滴送一 byte，超過三倍設定的 0.4 秒 idle interval 時兩個 close 仍不能提前送 release。外部 barrier 完成合法 body 後，要求一次原 method/path、`af=false` release，兩個 close 都回 idle。
- 相同 direct PUT/POST 情境，也先等到超過三倍設定的 0.4 秒 idle interval，確認 Start 與兩個 close 仍 pending、transport 尚無完成結果、wire 尚無 release；保持 completion barrier 關閉並取消 Start task，要求 release 到達，不等待 body 完成。
- Desktop Bridge 的相同慢滴流／取消兩例；不廣告 event polling 能力，隔離 Start 後唯一的原 session DELETE，沒有 stop replay 或重複 DELETE。

兩分支都使用相同 pending checkpoint，取消不能只是對早已失敗的 Start 做 no-op。透明 decorator 只觀察指定合成 Start path 的真 `underlying.send` 結果，記錄 HTTP status/body byte count 或固定錯誤分類，不保存 headers、authorization、body 內容或原始 error 字串，也不改寫 response/error。正常開 barrier 的分支必須觀察到 production transport 成功回傳 HTTP 200 與完整 padded body 長度，之後才由 client closing guard 拒絕 Start（direct 為 `CancellationError`、Bridge 為 `sessionChanged`）；單純斷 socket 或截斷 body 不符合成功 oracle。取消分支則要求 transport 本身確實回報 `CancellationError` 或 `URLError.cancelled`，不能把任意 timeout／網路失敗當成取消成功。

固定 `deadline-wire` metrics 只輸出設定值、單調時鐘量到的秒數、status/count 與合成 outcome category：no-drip 的真 `underlying.send` elapsed、每個 case 的滴流 checkpoint，以及 Start 的完成／取消結果。0.4 秒是 requested idle 設定，不能當成已測得的精確有效 deadline；兩個 OS 的 no-drip elapsed 可用來核對 CFNetwork rounding/clamping 或 CI scheduling 影響。不為此加入過窄的 wall-clock 成功斷言。

raw TCP listener 與每個 connection 使用不同 worker，另有 held-body 期間的 `/fixture-probe` 往返，使用與 client 同一個 production URLSession transport，驗證 peer 與該 transport 連線池仍能服務其他連線。測試使用精確 wire request 序列、actor 內 close-entry 訊號、completion barrier 與同步結果盒；XCTest 有限等待之後不再無限 await Task.value。fixture 有 8 秒 hold 上限、socket timeout、所有 active socket shutdown 與最多 3 秒 teardown join。所有值為合成 fixture，沒有真相機或 credentials。

同一個檔案只 `import OpenEOSCore`、使用 public API，自帶所需最小 endpoint/request fixture，不依賴其他 Core test helpers。Swift Package macOS tests 與 `OpenEOSControlTests` iOS Simulator target 都編譯這份 source，沒有複製測試或新增 CI job。兩個平台的 CFNetwork 執行證據必須分開紀錄。

## 不混淆的兩個問題

失敗 Stop 後的 Bridge fresh-status GET 也可能滴流占用到現行 resource 上限，App `.capture` 在整個 retry 中保持 busy。但它位於 `stopBulbExposure` 的 defer 釋放 `bulbOperationInFlight` 之後，core close 不等待該 GET；generation/revision guard 阻止舊回覆解除新 session 的責任。不能把 Android 的「fallback GET 挡住 DELETE」直接套到 iOS。

Start 自身 deadline 則影響正常曝光命令，必須另作產品決策。此分支不修改全域 10/120 秒 timeout，不測量完整 120 秒等待，也不聲稱已證明新的 deadline 或拍攝語意。

## 執行狀態與 Release Assessment

本機沒有 Swift/Xcode。macOS Swift Package：尚未編譯或執行；iOS Simulator App unit tests：尚未編譯或執行。必須由精確 head 的 CI 分別驗證，不能以 source inspection 或 macOS 結果代替 iOS Simulator wire 通過。預期每個成功慢滴流 case 約 1.3 秒加初始化/清理；安全上限與 CI 排程不是正常完成時間保證。

本機只完成 diff/whitespace 檢查、YAML 解析、唯一共享 source 路徑核對，以及五個 public-API-only 方法的靜態範圍检查；這些不是 Swift 編譯或網路執行證據。未提交或推送，尚需照原規則執行最終機密掃描。

最新版本基準為 v0.10.0 Development Preview。Release impact：`none`，純工程 characterization，沒有 distributable product 變更。不合併、不發布；沒有實體 iPhone/EOS 證據。

參考：[Apple request timeout](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/timeoutintervalforrequest)、[Apple resource timeout](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/timeoutintervalforresource)。
