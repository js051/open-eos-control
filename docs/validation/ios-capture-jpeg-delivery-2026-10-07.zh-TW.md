# iOS 單次拍攝至 JPEG 原檔交付：來源候選與待驗契約

日期：2026-10-07 UTC。基底已同步 accepted main `fd3e9837cf1ce2c783acd5a191d252634c1cc6c1`（#218）；本記錄屬 #219 開發分支，不是 PR ready、main accepted 或發版證據。PC 批次已獨立完成；本 iOS 完整批次仍須對最後同一 head 完成全部適用 CI。

## 有限產品範圍

只處理 iOS 一次 still 拍攝後，找出新出現的靜態素材、有限重查、開啟既有全螢幕預覽、下載原檔，再交給既有 ShareLink。延用 #217 的 shutter AF 選擇；重查與重試預覽均只讀，不重拍、不更改 AF。沿用既有 8 候選與 4 輪搜尋（間隔 250、750、1500 ms），不在拍攝前新增全卡掃描。

「新出現」僅表示本次有限讀取看到了先前尚未觀察的項目。已知集合來自當前連線已載入的相簿、目前最近項目及已見候選；時間戳、陌生 ID、快門 ACK 都不證明該檔由這次曝光產生。英文／繁中介面明示僅檢查最近 8 項、新出現也可能是舊檔。RAW／JPEG 與未知日期維持既有格式與排序能力，沒有 RAW 顯影或配對推論。

不包含 auto-import、背景長駐、REC／Bulb 擴充、手機 Bridge CCAPI 入口、新 Serein protocol、真相機操作、全 App 非同步架構重做或 #209 文件。

## 從 source 確認的問題與修正

下列原先由程式可達性分析及新寫反例建立；首輪正常 CI 已通過全部 App／Core 測試，兩個 UI 方法仍有下述待修紀錄。沒有宣稱所有產品問題都曾以未修正 App runtime 重現紅例：

- 原選擇器先比較全清單最大日期，再排除一個先前 ID；未來日期的舊 A 可遮住較舊日期的新 N，已知 B 換序也可能被當成候選。現在先排除所有已知項目及影片，再沿既有日期／相機順序選靜態候選。
- 原最近媒體工作在新快門 ACK 後才退休。現在新快門進入時就失效舊工作；ACK 未回前放行的舊 listing／thumbnail 不能發布。
- 四輪結束後仍保留快門成功；最後一次清單讀取成功但沒有新項目時顯示尚未找到，最後一次讀取失敗則明示無法讀取清單，不把未知清單說成沒有新檔。重試尚未真正讀取便遇到 MEDIA busy 時保留先前讀取失敗；後續成功空清單會清除失敗提示。明確重查復用同一次已知集合，受連線、review 與 busy 保護；MEDIA 忙碌時零請求。內容事件不能接管尚待確認的 review。
- 已找到但尚未開啟的候選保有 review owner，延遲 contents event 不再用一般日期排序把它換回舊 A。使用者開啟候選後恢復一般最近媒體刷新。
- 自動內容事件更新相簿時不再無條件關閉使用者的 viewer，也不清掉該 viewer 尚待處理的讀取錯誤。選中項目不在新部分清單時，仍顯示同一照片與同一原檔入口，位置顯示「目前清單未列出」，停用前後導覽；不把未列出當成刪除證明，也不顯示虛構的 0/n 或 1/0。明確刪除成功、手動關閉與換連線的原清理路徑保留。
- 原預覽回應及 defer 僅比 item ID、無條件結束 MEDIA busy。現在每次開啟有獨立 token，並比對 session；同 ID 關閉重開或換連線的舊成功／失敗不能接管新圖、錯誤、loading 或 busy。關閉退休該次 UI owner；此批沒有增加底層 display HTTP 強制取消，舊讀取仍可能在網路上完成。
- 原兩個分享入口用檔名認領下載檔。現在使用既有 downloadedMediaID；同名不同 ID 不會分享別張原檔。沿用原本下載 UUID、原檔 client 與此次暫存目錄清理，只補排隊工作進入及錯誤回寫的 owner 檢查。
- 縮圖失敗不阻止預覽或原檔下載。預覽失敗可真正重送 display GET；全螢幕 viewer 接手既有 operation-error alert，使其 dismiss／retry 位於目前畫面，Root 不同時呈現相同 alert。沒有更改全 App 的錯誤架構。

## 已準備的因果測試

以下是新增／延伸的測試清單；執行結果另列於下節，不能將方法數與不同 head 的 CI 通過數相加：

- `MediaLibraryTests`：新增 2 個選擇反例，涵蓋 known {A,B}、A 未來日期／N 舊日期、只回 B、未知日期、RAW/JPEG 相機順序及影片排除；既有單 ID 測試更新為集合契約。
- `CaptureMediaJourneyTests`：17 個 App 測試方法，使用真正 `CameraAppState → CameraSession → DesktopBridgeClient` 與 synthetic transport。包括四輪舊檔／503 後 NOT_READY、四輪全失敗的 readFailed、失敗後成功空清單清除提示、GET-only 重查且 shutter/AF 計數不增、已載入相簿集合、快門 ACK 前舊 listing／thumbnail、MEDIA busy 零請求、縮圖／display 失敗與原檔 bytes、同 ID 關閉重開／新 session 的成功與失敗、同名不同 ID 分享、原檔失敗／取消／重試、內容事件保護 review／選中但未列出的項目、舊原檔工作釋放時新 session 的 busy/share/error，以及明確刪除對照。
- 非同步反例以 continuation gate 暫停指定請求；對已退休的最近媒體／原檔工作保留精確 task handle 並 await 完成，不以若干次 yield 推測舊 completion 已結束。read-only task handle 不新增外部 App API 或相機命令。
- `CCAPIClientTests.testDistinctThumbnailDisplayAndOriginalBytesReachTheOriginalDestination`：新增 1 個真 Canon client 路徑測試。thumbnail、display、original 使用不同 bytes，精確驗證 destination 檔是 original，路徑均為 GET。此單元 fixture 只提供 JPEG framing，解碼證據另由有效 JPEG fixture 的 UI 路徑承擔。
- 既有 `testCanonicalShutterAFChoiceReachesCameraAndNewJpegPreviewInBothLanguages` 延伸至縮圖故障後的有效 JPEG 預覽與原檔 ShareLink；保留既有單次 tap、5 秒互動等待、44 pt 及快門命令計數約束。
- 新增 `testCanonicalCaptureReviewRetriesReadsAndFailedPreviewOriginalWithoutReshooting`：英文／繁中各自走正式 HTTP preset，先四輪至 NOT_READY，再使用既有 opt-in fixture 的 listing 503 預算，實際點同一只讀重查按鈕；等待 readFailed 文案與可再次操作的 44 pt 重試入口，保留舊圖標示並截圖。失敗階段也確認快門恰一次、AF false 及實際清單 GET 失敗；不要求平台不存在短暫中間狀態，也不把含 query fallback 的 HTTP GET 次數當作輪數。清除故障設定後，再從同一按鈕接回成功候選、display 失敗重試、3 個既有 original URL variant 均失敗、第 4 次取消、第 5 次成功；快門仍只有一次且 AF 仍 false。再透過真正 contents event 讓部分清單漏掉當前項目，驗證 viewer／share 保留、位置與導覽正確。async UI 保持 `continueAfterFailure = true`、正常 Swift throwing `XCTUnwrap` 及 `defer app.terminate()`，避免 Objective-C XCTest 例外跨 async 汙染後續案例。
- `simulator/test_capture_delivery.py`：8 個新 fixture 測試。新的 `/ccapi/test/capture-delivery` 明確 opt-in 才啟用拍後 listing 503／延遲可見、representation 故障／延遲及 GET 計數。thumbnail／display／original 為獨立 deterministic 有效 JPEG；狀態提供大小、尺寸與 SHA-256。預設 simulator 契約不變，部分設定更新保留同一 capture 與計數，讓 UI 可以只 GET 重查。

ShareLink 可見只證明 App 有原檔可交給使用者，不能宣稱外部 Files、Photos 或接收 App 已保存，也不構成真實相機／實體 iPhone 驗證。

## 本次已執行與尚未執行

已執行的輕量檢查：

- 完整兩份 `Localizable.strings` 各 480 個 key，無重複；兩語 key 集合完全一致，所有格式參數一致。
- 5 個新增／具名導航 accessibility identifier 的 App 定義唯一，與 UI 測試綁定一致；既有 latest、close、image、download、cancel、share 動態 ID 定義與測試亦核對。
- `simulator/main.py` 與新增 fixture 測試的 Python AST 可解析。
- `git diff --check` 通過；獨立 source review 未找出已確認的 Swift 語法／owner 路徑 blocker，指出的反例缺口已補入。這不是 compiler 結果。

此環境沒有 Swift/Xcode；未安裝新工具。本地正常 CC／安全 hooks 已執行，但不構成 App runtime 證據；draft PR 及同源 CI 的實際狀態以該 PR 記錄為準。正常 macOS CI 必須證明真正編譯、影像解碼、alert 呈現、雙語幾何與完整互動；修正版不可沿用首輪綠燈。

### 首輪 CI 與窄修

原 head `09544229ced3c1e45ef23c6ce447a30de38989e5` 的 [CI 37699137960](https://github.com/js051/open-eos-control/actions/runs/37699137960) 已證明 App build、App 125/125（含新增 17 方法）、Swift Core 245/245、simulator 56 例與 Ruff、Desktop Bridge、Windows、Android JVM／APK 成功。iOS UI 20 個方法中 18 個通過、2 個失敗（3 個 assertion failures）；Android API34／36 裝置矩陣其後亦成功；原 run 的聚合結果仍因兩個 UI 方法失敗而不通過。這不是全批通過，也不拿部分結果替代最終 head 驗收。

- 新 recovery journey 的英文流程已到原檔分享、部分清單未列出提示、同照片與分享保留，失敗在用 `isHittable == false` 驗停用導航。Apple 的 [isHittable](https://developer.apple.com/documentation/xcuiautomation/xcuielement/ishittable) 描述命中點，而 [isEnabled](https://developer.apple.com/documentation/xcuiautomation/xcuielementattributes/isenabled) 才是互動啟用狀態。修正版以最多 5 秒確認兩個導航均未提供或 disabled，保留照片／分享／位置、一次快門、AF 與原檔計數保障。沒有改 App 產品 source 或宣稱 UIKit 內部根因已定位。
- 隨後原事件 UI 測試觀測到 poll count=1、delivery count=1、active requests=0，等候 active=1 超時。原 simulator 的待決 poll 在 `/ccapi/test/reset` 後仍直接使用全域 cursor 與 counter。新增原標準 `unittest.IsolatedAsyncioTestCase` 三案：舊 handler 消耗新事件、舊 finally 把新 active 1 減成 0，兩案在原 source 真紅；當前 owner 的事件交付正向對照通過。
- 只在 simulator 加 reset generation 的 request 歸屬檢查，舊 poll 不讀取新事件，也不在 finally 扣新 counter。相同三案於 23:28:01 UTC 全過；兩輪來源 hash 無漂移，各約 0.1 秒測試時間。保留原 long-poll 時限與當前 owner 清理。此修復符合 UI 原觀測，但完整 macOS UI 仍須由新 head 正常 CI 驗證。

沒有刪除失敗案例、放寬一次拍攝／原檔 ownership 契約、改必要 checks 或重啟先前停止的安裝。UI 修正版及 simulator 完整 pytest／其餘受影響矩陣尚待新 head 執行。

## Release Assessment

- 最新真正公開 release：`v0.12.0 Development Preview`。repo README／App metadata 已是 `0.13.0`，固定的 0.13.0 候選尚未發布；檔案中的版本不等於已公開發版基準。
- 建議 impact：`patch`，修復既有一次拍照至預覽／原檔交付及恢復流程；沒有新協定或新的平台能力聲明。
- 本批沒有改版號、合併或發版。
- 未解 blocker：兩個 UI 紅燈的修正版尚待最終 exact-head CI；已同步接受的 PC #218 main。部分 runtime 通過不構成 PR ready。
- 物理裝置：沒有新增相機、實體 iPhone、Wi-Fi 弱網或外部儲存接收驗證。
