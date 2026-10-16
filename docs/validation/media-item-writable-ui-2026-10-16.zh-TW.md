# G10 單項素材不可寫入狀態對齊（2026-10-16 UTC）

基線共同 dev：`bade2398f100c8898173df8be48d48f5f748fc97`。

## 確證的合約條件缺口

既有 MediaItem 支援四個 optional boolean veto：protectionWritable、ratingWritable、rotationWritable、archiveWritable。Bridge CCAPI media info 轉發嚴格 bool，iOS Bridge/direct CCAPI parser 保留值。這證明 wire 給明確 false 時的路徑；未證實某型號相機會輸出該欄，也未宣稱真機已復現。

PC 原單項控制在 false 時仍可按；實際寫入前的 batch qualify 會拒絕並報 unsupported-item。iOS 原單項控制亦可按，但 CameraAppState 的既有 isWritable guard 靜默返回。Android 原本就以 feature + 每項 writable gate 顯示，單筆與批次 dispatch 另有檢查，故不重寫。

## 修正

- PC 每個單項操作在對應 writable === false 時禁用，顯示英／繁中原因。其他欄位不連帶禁用，true/nil/absent 不授予 capability；原 feature gate 與 mutation dispatch guard 不變。
- iOS 四個單項控制直接使用既有 CameraMediaMutation.isWritable。既有 global writable=false、對應欄 false veto 與 nil 語意保持；顯示本地化不可寫入原因。
- 保留 loading/busy、current value 與 archive known-state gate。沒有擴大相機能力、改協定或啟用 unsupported 操作。

## 實際驗證

PC 擷取 production renderMediaDetails 執行於 inert DOM host fixture：基線 8 案為 4 PASS / 4 FAIL，四種 explicit false 均未禁用；修正後新增 10 案通過，包含 nil/true 恢復、異欄隔離、feature 不授權與真 markup binding。

- PC 新 UI + 既有 media-mutations/media-mutation-owner：211 PASS，exit 0。
- iOS/Android mutation source guards + Bridge media mutation readback corpus：774 PASS，exit 0；其中 iOS source guard 14 案，不是 Swift 執行。
- 六個獨立 source negative controls：分別移除 protection/archive 兩方向、rating、rotation 的 guard，source 檢查各 EXPECTED_FAIL。只在讀取替身中改字串，產品未改。
- iOS native 新 policy matrix 提供 144 次 assertion；NOT_RUN。既有 state guard 未刪。
- node --check 與 git diff --check 通過。新增 PC 測試已接入 test:modules。
- 獨立只讀 review 無阻擋；reviewer 未跑測試。Starlette/httpx deprecation warning 原樣保留。

Windows／macOS／iOS／Android native UI、browser 實看、真相機與 KVM/CI 此批均 NOT_RUN；host test 不等於整個三平台驗收。未發布可下載 App，未合 main 或發版。

## 測試來源 SHA-256

- `bridge/open_eos_bridge/static/app.js`: `c2adb4579670171d1335a39c03ed2a9e7e581ca0dab6709061aee03a2de2c9d9`
- `bridge/open_eos_bridge/static/index.html`: `88f4cc1f1b5277465f5149def93f82cf7dfe5ff65fa8e52c67b110db1f31b490`
- `bridge/package.json`: `020f97fc734d601f87fc5dacd193c887589001806a78a935f724e580a3452d2f`
- `ios/OpenEOSControl/App/MediaView.swift`: `12e22a9fcba5b415cbaf7fd519116d97d55e3bc32e06166c6741e20a88abfbe4`
- `ios/OpenEOSControl/App/Resources/en.lproj/Localizable.strings`: `bdf4bd9089f7fe937fbd3b0f193a231e363b307deb14263a1f3cdec483ba2ddf`
- `ios/OpenEOSControl/App/Resources/zh-Hant.lproj/Localizable.strings`: `47b6ac98de9e534e37217a412102ec809204635c0ba7a3358b35f26e1c92b9f3`
- `ios/OpenEOSControl/Tests/MediaMutationContractTests.swift`: `17c5f10b0a74a2d30c2f7ad68057f463c734eb159efc9693e103f348f38a4f50`
- `scripts/validation/tests/test_ios_media_mutation_source.py`: `297872c2005b50fec6326e125b9e4e18d6cc59700c05ef504b11cc3b3aa17536`
- `bridge/tests/media-item-writable-ui.test.js`: `f20aae43a9f9e9362ed3acdf375ac52b492ec176a885a2a96b2585e4ec56cd0f`

## 2026-10-16 Firefox 實看補驗

受驗產品 HEAD 固定為 `21c1de9f91bf601b3e05750127e4ee28a83a96bb`，未修改產品來源。使用既有雲端桌面的 Mozilla Firefox 157.0.1、全新拋棄式 profile；這不是 Playwright Chromium 或 mobile native 驗收。兩個服務只綁 `127.0.0.1:18765/18766`，gphoto 使用 FakeRunner、EDSDK provider 停用，CCAPI 僅允許無憑證的該 simulator origin，redirect/RTP 停用。

測試專用 simulator 提供五個獨立 item：ALL_FALSE、RATING_FALSE、UNKNOWN、TRUE、BUSY。前四者分別傳四欄 false、只有 rating false、未提供 writable、四欄 true；BUSY 的 metadata info 延遲 8 秒。產品能力與 metadata parser 均走原實作。

實際畫面觀察：

- 不支持 metadata mutation 的 fake gphoto item：四種控制不顯示。
- ALL_FALSE：四組控制均呈禁用，四段英文 read-only 原因可見；切繁體中文後，四段「相機回報此檔案的這項資訊不可寫入。」可見。
- RATING_FALSE：只有評分禁用與原因，其餘保護、旋轉、封存控制仍可用。
- UNKNOWN 和 TRUE：四組控制可用，沒有 read-only 原因。未把 unknown 解釋成 false。
- BUSY：讀取中訊息可見，當時可見的保護、評分、旋轉與下載/刪除控制禁用；封存尚未取得已知值，因此沒有顯示。

本輪未觀察 BUSY 完成後恢復，也未執行 metadata mutation-in-flight 的 busy 狀態；兩者仍不能記為 browser PASS。沒有點擊實際變更／下載／刪除，沒有實體相機。iOS/Android native 仍 NOT_RUN。

連線前置曾在切換 CCAPI 後看到舊 USB 列表；刷新頁面、明確重連後顯示五個正確 fixture item。尚未獨立確認其原因，不以此畫面直接宣告產品缺陷或已修復。

### 終態與證據限制

- harness 開始：2026-10-16 12:11:48.610 UTC；服務就緒：12:11:49.549 UTC。
- 操作者在排程窗口內發出停止；終態：12:19:59.705 UTC。兩個直接 owned group 主程序 exit code 均為 -15（主動 SIGTERM），不是 exit 0。
- 原終態 receipt 瞬間仍列兩個服務子 PID；12:20:06 與 12:21:19 再核全部四個 owned PID 均不存在。保留原 receipt，不抹去短暫 remaining 紀錄。
- 抽樣最低 free 5,772,320,768 bytes；採提前監控停止，非 kernel hard quota。未量到完整程序 RSS 峰值。
- 已停用 profile 清除：41,396,270 logical bytes；df 增加 41,762,816 bytes，清後 free 5,813,252,096 bytes。
- 畫面以 CUA 工具影像回覆實際觀察；其暫存 screenshot 路徑後續複製回報 ENOENT，沒有持久截圖檔，不宣稱已隨 repo 上傳 PNG。
- fixture SHA256：safe_simulator.py `fe0fdfb56cda24079d301192016e74cce376648653d3523940b51dd22d271d0a`；safe_bridge.py `2c4212e273fc52230aa97aa508e9eebbb99364ab9e7fe6ac141d21cfa5cc68d0`。

本補驗只增加上述 PC Firefox 視覺證據，不能提升為三端全驗、真機相容、PR ready 或可發布。Release Assessment：none（驗收文件），無新版本或發布產物。
