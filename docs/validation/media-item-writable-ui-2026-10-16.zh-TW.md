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
