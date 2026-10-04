# iOS Bulb 停止責任與恢復流程

## 基底與因果證據

基底為 PR #199 head 180dbfab9ee30a78796bb32f4072d724c0a693e9。先單獨提交只使用既有 public API 的兩個 baseline tests，沒有把待測實作混入。

[Baseline CI 37202721755](https://github.com/js051/open-eos-control/actions/runs/37202721755) 的 head 32c4cbb3ce854b6164fd8d9ec96d3b106c4d1d98：
- Core 成功編譯，173 個測試中只有新增 2 例失敗，8 個斷言失敗、0 unexpected；其他 171 例通過。
- peer 已收到 full_press，再讓其回覆與第一個補償 release 失敗。
- 使用者再呼叫 Stop 或 close，實際命令仍只有 full_press、release，少了要求的第三個 release。
- PUT /ccapi/ver110/shooting/control/shutterbutton/manual 與 POST /ccapi/ver130/shooting/control/shutterbutton/manual 皆如此；原 method/path 與 af=false 斷言保留。
- 這是注入 transport 的 production Core 契約證據，不是實際 URLSession wire 或實體相機。

## direct CCAPI

在開始前保存 session 宣告的精確 release。start／stop／close 使用連線擁有的 operation 責任，處理取消、晚 ACK、重複操作與 actor reentrancy；一般寫入不能穿過尚未確認的 release。

未知不等於 active，也不能假裝 idle。停止失敗保留責任，retry 只送原 release，不重新 start、不重新推測 endpoint。close 等待已擁有的開始 settle，再嘗試原 release；退休連線保留風險，不向新 session 偷送舊命令。

新增 CameraShutterReleaseState 區分目前可重試責任、release unconfirmed 與 nullable active。既有 close() 保留，相容 wrapper 搭配可回傳清理狀態的新介面。成功 release 與後續 status 刷新分開；未 ACK 的開始經補償，不算完成曝光。

## Bridge adapter

保留同 session 的 start/stop 責任、generation 與 revision。只接受 literal Boolean 證據，不把 NSNumber、字串、缺欄位或普通舊狀態當成未知 release 已解決。

正常 legacy 已確認 start/stop 保持可用；模糊 legacy 回覆 fail closed。Stop 不會 replay start。失敗後僅在對應 Stop 已完成、session 未換、未取消／closing 時讀取新 status 來驗證 false/false；一般舊回應不能清除新責任。

DELETE /v1/session/{id} 由連線清理擁有，等待結果後才解除 session 與 close waiters。已取消的呼叫端不讓最後 DELETE 在發送前失效；不擴充權限或跨 session 重試。

## App 使用流程

- 已知 active 或未知 release 都有模式／capability 無關的 Stop／Retry Stop。
- 顯示持續當前警告與獨立上一連線警告，不因普通錯誤清除或 reconnect 混掉。
- operation token／session generation 防止舊 start、reply 或 defer 清掉新操作狀態。
- 恢復未知 start 不閃拍攝成功；沒有隱式重新曝光。
- 背景曝光維持既有政策，沒有新增自動 stop；阻止錯誤 Live View resume，明確 Stop／disconnect 做已擁有的清理。
- 英文／繁中、不同字體、直橫向與新舊警告並存納入測試；實際畫面須 CI 執行後檢查。

## 測試分層與歷史 gate

保留 baseline 2 例不改。新增 Core ownership 16、Bridge recovery 29、App 17；URLSession/Darwin TCP 原故障矩陣保留，另外有 transport 長度檢查與正常回應對照。UI 原新增 2 個方法之外，本輪再加 1 個 sheet hit-target 回歸。wire tests 要直接量到 full_press 次數，不以 mock 預期代替真傳輸。

DEBUG UI fixture 僅 DEBUG build 且 OEC_SHUTTER_RECOVERY_FIXTURE 為明確白名單情境才啟用；普通啟動／Release 使用原 client。fixture 標記 simulated-shutter-recovery，新增回歸要求實體驗證匯出被拒。它是注入 transport 的 UI 證據，與 Darwin socket tests、實體相機三者嚴格分開。

### 第二輪 macOS CI 的實際結果

[CI 37205228838](https://github.com/js051/open-eos-control/actions/runs/37205228838)，PR head f4dc8b17e96009cde64599160af2d4d6c4b11c93：
- Core 成功編譯；221 個測試方法中 219 個通過、2 個 wire 方法失敗，合計 11 個失敗斷言。
- App 成功編譯；90 個 unit tests 全過，含本分支新增 17 個。
- 13 個 UI tests 中 12 個通過；上一連線警告／新 Stop 流程在單次 Disconnect 後無法回到連線畫面。不能把這輪說成整體通過。

### 已重現的 HTTP 截斷因果與修正邊界

Darwin 實際 TCP fixture 已送出完整 HTTP 200 headers、Content-Length 64，但 body 只送 1 byte 後關閉連線。PUT 在 fresh 與 pooled 連線中都被 URLSession 當成功回傳；同矩陣的 POST 回傳 NSURLErrorDomain -1005。Core 因 PUT 的假成功跳過原本應做的補償 release，所以這是 transport 完整性問題，不是等待太短，也不是應把失敗測試改成成功。

最小修正在 send 回傳前比對仍可比較的 identity body byte count 與 Content-Length，拒絕長度不符、無效／衝突／溢位的長度。依 [RFC 9112 §6.3](https://www.rfc-editor.org/rfc/rfc9112.html#section-6.3) 的 body／framing 規則，排除 HEAD、1xx、204、304、成功 CONNECT，以及有 Transfer-Encoding 的回應；非 identity Content-Encoding 也不直接用 decoded byte count 比較。依 [RFC 9110 §8.6](https://www.rfc-editor.org/rfc/rfc9110.html#section-8.6)，相同的合併 Content-Length 值可正規化。這不新增 Canon-specific 回覆契約、不 replay full_press，也不增加自動重試。

新增 6 個 helper 測試方法、1 個實際 wire 正向方法（9 組完整／合法回應對照，含 gzip、chunked）；既有 lost／truncated、fresh／pooled 故障矩陣與 exact-once 斷言不改。後續 e076956 與 5594055 的正常 macOS CI 已實際編譯並通過全部 228 個 Core 方法，包含這些正／負向對照。encoded／chunked 的完整性仍依賴 URLSession，不能宣稱這個 identity Content-Length guard 已覆蓋其所有截斷情況。

### 已重現的 sheet hit overlap 與最小修正

同一 CI 的 pre-tap 幾何為 Disconnect `(20, 784, 362, 48)`、可操作 Stop `(12, 766, 378, 62)`；兩者重疊 44pt 高，Disconnect 中心位於 Stop 內。兩者都被 accessibility 回報 hittable。單次 Disconnect element tap 後，More actions sheet 與原 camera model 保留，Stop／當前警告消失。由於 requestDisconnect 會同步清掉 snapshot 與 activeSheet，這組幾何與狀態轉移證明問題是按鈕 hit area 重疊，不能用 cleanup timeout 或第二次點擊掩蓋。

修正僅把 sheet host 與 recovery 改成同一 VStack 中明確分配空間的 siblings，保留 recovery 的 layout priority，取代 NavigationStack 外側的 safeAreaInset；相機命令及警告語意不變。原跨連線測試仍只點一次 Disconnect、等待 8 秒，並保留所有既有斷言。

新增回歸直接要求唯一可操作 sheet Stop、Disconnect／Stop 無交集、各自完整位於畫面內且至少 44pt 高。4 組情境涵蓋英文／繁中、XS／accessibility XXXL、直向／左右橫向、active／unknown；每組要求單次 Disconnect 退休連線，以及重連後單次 Stop 釋放快門但保留目前連線與 actions sheet。原 root recovery 的完整語言／字體／方向矩陣保留。後續 CI 已驗證 layout 的分離，仍有下面獨立的測試操作缺陷待閉合；沒有實體相機證據。

### 後續實測：產品修正通過的部分與 Connect 測試操作缺陷

[CI 37207124228](https://github.com/js051/open-eos-control/actions/runs/37207124228)，head e076956eece92014c6e529bb31686c37c1c4e121，以及加上有界回連診斷的 [CI 37208791469](https://github.com/js051/open-eos-control/actions/runs/37208791469)，head 5594055324aed78f2ff2752b4719d8955173d924：
- Core 228／228、App unit 90／90 通過；UI 14 個方法中 13 個通過、1 個失敗，不能宣稱整體已綠。
- 原上一連線警告／新 Stop 測試及完整 root 語言／字體／方向矩陣通過。新 sheet 回歸 4 組都確認 Disconnect／Stop 無交集並成功單次斷線，前 3 組也確認重連後 Stop 的獨立效果。
- 第 4 組繁中 accessibility XXXL、landscapeRight 在斷線後無法完成測試所要求的回連，所以尚未執行該組後半段 Stop。

5594055 的 before／after／failed-reconnect 幾何及 PNG 顯示：window 與 connection scroll viewport 都是 `(0, 0, 874, 402)`，Connect frame 卻是 `(157, 596.67, 560, 77.33)`，完全不在畫面內；AX 仍回報 enabled／hittable 為 true。舊通用 helper 只看 exists／enabled／isHittable，直接返回而沒有滑動。名義上的單次 Connect tap 前 PNG 為 HTTP preset 選中，tap 後及原 8 秒 budget 失敗後 PNG 都變成 Simulator preset 選中；Connect frame 仍在畫面外，model／Stop 都不存在。這證明本次紅燈是測試操作點錯目標，不能据此修改 fixture 的第二連線狀態或宣稱 production connect 壞掉。

本輪只修測試 harness：對這 3 個 recovery 測試的所有 Connect setup／reconnect，採專用 connection-scroll-view helper；最多 8 次、8 秒內的有界上下滑動，要求按鈕完整 frame 與中心都在 scroll／window 交集內、寬高至少 44pt，再做原單次 tap。各 tap 前另有直接幾何斷言。一般 media helper、production、字體、方向、Stop／Disconnect 次數及產品結果斷言不變；回連的 8 秒期限仍從 tap 返回立即開始，tap 後診斷算入該期限，沒有額外等待寬限。

新增 1 個純 CGRect 判準測試方法，14 組手工案例含上述真實 offscreen frame、中心可見但邊界裁切、scroll 與 window 交集、44pt 門檻、空／無限／非有限 frame。在 f1183d1 提交時，下一輪 UI 預期 15 個方法，當時尚未 macOS 編譯或執行；其後的精確 head 結果見下節。保留同名 before／after／failed 診斷附件，繼續用實際可見 Connect tap、第二 session、sheet Stop 與無舊責任干擾的最終結果閉合，不能把 helper 寫好當成回歸已通過。

本機沒有 Swift／Xcode。本輪 diff check 已通過；先前版本已完成語系 key parity、原規則機密掃描與 source manifest／patch 完整性檢查，但仍需對最終整合 head 重跑適用檢查。前述 Core、App unit 或 13 個 UI 成功都不能當成修改後全綠；修正必須由精確 head 正常 macOS CI 閉合，不能跳過失敗或放寬 exact-once。

## f1183d1 的已完成驗證與整合界線

[CI 37211034672](https://github.com/js051/open-eos-control/actions/runs/37211034672) 對應精確 head `f1183d156030d18b93f089f3386316beb59efa6b`、tree `3e59501126c9dd57d5185ad19500cfecb9177013`，`ci-complete` 成功。Core 228／228、App unit 90／90、UI 15／15 方法通過；UI 為 14 個流程方法，加 1 個包含 14 組邊界案例的 CGRect 方法，不是 15 個完整 UI 流程。

該 run 的有界 UI artifact 包含 37 個附件，合計 4,138,736 bytes；ZIP 為 3,423,556 bytes，SHA-256 `a54bcd113f821b320497290fafeed2969f55f8752806ae969ad1a1d230ccd240`。三個恢復測試都回報 Passed。先前失敗的繁中 accessibility XXXL、landscapeRight 情境，在 Connect tap 前的 frame 已為 `(157, 117, 560, 77.33)`，完整位於 `(0, 0, 874, 402)` viewport；回連後 fixture session 2、目前 Stop／警告均存在。既有附件保留為該歷史 run 的 synthetic iOS Simulator 證據，不是實體 iPhone／EOS 驗證。

上述成功不替代本次收斂後新 head 的驗收。iOS product／App／Core／UI source 保留 f1183d1 的已驗證內容；合併已接受 main 與下列文件／工具用途修正後，仍須對新的精確 head 重新取得 `ci-complete`，squash 進 main 後另須 `main-accepted`。此處不預先宣稱整合 head 已綠或已發布。

## 保留的手動證據工具

本次收斂將 `.github/workflows/android.yml` 還原為接受當時 main 的完整 blob，移除本 PR 額外加入的 exporter paths-filter 與 export/upload steps，不移除或放寬 main 原有測試、checks、gates、XCResult／failure-log 保存行為。保留 `scripts/ci/export_ios_ui_evidence.py` 及其 synthetic helper tests 作為手動離線分析工具；目前 workflow 沒有自動執行它或上傳新的小型 PNG artifact。既有 CI helper unit-test discovery 仍會測試這個 Python helper，不能將這點說成 UI 附件自動匯出已啟用。

在已有 Xcode 16+ 的 macOS host，從 repository root 對已存在的 `.xcresult` 手動執行：

```sh
python3 scripts/ci/export_ios_ui_evidence.py \
  --result-bundle ios/OpenEOSControl/TestResults.xcresult \
  --output-dir .codex/verification/ios-ui-evidence-manual
```

output directory 必須是尚不存在的新目錄。工具只讀取結果並匯出精確白名單的合成附件，維持 24 MiB 總量與單檔／schema 限制；不安裝 Xcode、不執行測試、不改變測試結果、不安排 upload。查看產出的 summary 與原始 XCResult，export 成功不等於測試成功。欲恢復自動 artifact 工作流程，須另案完成 workflow 與 live settings 的驗收，不能以本工具仍在 repository 中推論已持續生效。

## 相容性與發布評估

v0.10.0 Development Preview 之上的既有流程 patch；本產品修正 PR 不改版本。是否進入 main 或發布 Preview 依各自 exact-head／provenance gate 判定，沒有新的實體驗證宣稱。Android Bridge PR #200 的結果不冒充本分支驗證。沒有實體 EOS／iPhone、其他機型或光學對焦新證據；永久斷線／process death 仍不能保證機身實際停止，使用者須檢查機身。
