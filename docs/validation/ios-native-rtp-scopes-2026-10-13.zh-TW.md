# iOS 原生 RTP 取樣監看：實作與驗收邊界

日期：2026-10-13。此組是 iOS 原生 RTP 有界像素取樣與五種既有監看工具的本地實作／測試來源；不是原生 LUT 完整對齊，也不是實機驗收紀錄。

## 官方 API 與支援範圍

- Apple 官方 [AVSampleBufferVideoRenderer displayedPixelBuffer()](https://developer.apple.com/documentation/avfoundation/avsamplebuffervideorenderer/displayedpixelbuffer()) 的 DocC metadata 確認 Swift 名稱為 `displayedPixelBuffer()`；Objective-C 名稱為 `copyDisplayedPixelBuffer`，iOS/iPadOS/Mac Catalyst 起始版本為 **17.4**。同頁官方 DocC JSON 可由 `https://developer.apple.com/tutorials/data/documentation/avfoundation/avsamplebuffervideorenderer/displayedpixelbuffer().json` 查核。
- EOS 最低系統仍為 iOS 17.0。17.0–17.3 保留原生視訊／音訊、構圖框與反擠壓，但像素監看顯示需 17.4 的原因。未提高最低系統，也未新增替代 decoder、framework dependency 或 Photos／相機權限。
- Apple 官方 [flush(removingDisplayedImage:completionHandler:)](https://developer.apple.com/documentation/avfoundation/avsamplebuffervideorenderer/flush(removingdisplayedimage:completionhandler:)) 明訂完成回呼。新取樣須等待相同 attachment/reset 的移除完成；舊回呼不能重開新 attachment 的取樣。
- 官方頁沒有替取回的 buffer 提供確切 decoded/displayed-frame timestamp。本組的時戳表示「讀回／處理年齡」，不是實際解碼顯示時刻。enqueue 僅是目前來源持續活動的證據。`nil` 是可接受的無影像結果，會清除監看並顯示暫無影像，不改動播放器速率、不改為整窗截圖。

## 完成的來源接線

- 控制器保留一個 sampler，跨 SwiftUI View／設定更換仍只允許一個實體 buffer／分析工作執行；退休工作必須真正完成才能釋出名額。沒有待處理 frame 陣列或每幀 detached task。
- 每次嘗試至少相隔 250 ms，設定／owner 變更不重置間隔。只在前景、auto-refresh、目前原生來源且至少一個像素工具開啟時讀回。沒有 capture timer；僅新來源活動可提出取樣，另有一個過期清除 task。
- CPU 分析／輸出上限是等比 120×80。API 本身回傳 renderer 的原尺寸 buffer，會保留至該次分析完成；這不是原尺寸記憶體成本或讀回耗時的量測，也不是零拷貝／硬體效能承諾。Core Image 先縮成有界圖，再沿用 histogram、waveform、zebra、false color、peaking 的既有 RGB 演算法，沒有 JPEG 重新編解碼。
- generation、session owner、實際 layer identity、format、設定與 sample sequence 共同排除舊完成結果。新輸入不讓仍有效的同來源分析永遠被淘汰。讀回後 1 秒即失效；有更新排程時，前一個結果也不能因此延壽。
- 暫停、背景、detach、來源／格式切換、關閉工具立即退休結果；無影像／轉換失敗清空結果。停止舊 session 的晚回呼不能清除新 session。
- 同一 layer 的 `updateUIView` 為冪等，不再每次 SwiftUI 更新都 flush／等待 IDR。換 layer 先 detach 舊 layer；舊 dismantle 不能 detach 新 layer。跳過影格的暫停／恢復會重新要求 keyframe。
- `.resize` 配合外層算好的 aspect-fit／desqueeze rectangle；不再被 controller 覆寫為 `.resizeAspect`。presentation dimensions 使用 clean aperture 與 pixel aspect ratio；scope 像素裁成相同 aperture，再映射至相同 UI rectangle。原生 RTP 路徑未實作額外旋轉 transform，取樣也使用 identity orientation；沒有聲稱讀到 Canon 未提供的旋轉 metadata。

## 色彩與 LUT 契約

- 僅接受 8-bit BGRA、ARGB、NV12 video-range/full-range。NV12 必須具備 buffer 的 YCbCr matrix、primaries、transfer 附件，且 matrix 為 BT.709／BT.601、primaries 為 BT.709／SMPTE-C／EBU 3213；其他值停用該次分析；未將 limited-range Y plane 直接當 RGB 亮度。
- 有 transfer metadata 時只接受 BT.709 或 sRGB；PQ、HLG、未知 transfer、高位元格式或不符合來源尺寸的 buffer 不產生結果。Core Image 消費輸入附件並轉為明確 SDR sRGB RGBA8。未標記的 RGB 是預覽近似，並非相機 RAW 曝光、絕對 IRE 或已校準 HDR scope。
- Native 視訊仍由原 renderer 顯示，音訊流程保留。LUT 不套於 native 視訊，不將慢速 LUT 截圖覆蓋原生畫面。已載入 LUT 保留但未啟用；UI 明示「取樣監看／每秒最多 4 次／未套用 LUT」。使用者可移除已載入 LUT。
- 五種 scope 共用有界預覽資料；尤其 peaking 是低解析取樣提示，不是完整解析度對焦驗收。擷取失敗或 decoder 繼續接受輸入但畫面停滯，不能從 enqueue 證明實際顯示 freshness；實機仍須量測與觀察。

## 驗證狀態

- **PASS（靜態）**：`git diff --check`；既有 `python scripts/release/verify-version.py` 回傳 `0.13.0`；標準函式庫檢查英／繁中 9 個新字串鍵一致且沒有重複、Info.plist 無新增 Camera／Photos 權限、17.0 deployment target 與 App／Tests source glob 保留。沒有變更版本、權限、音訊 codec 或依賴。
- **PREPARED / NOT_RUN（Swift tests）**：`RTPPixelMonitoringTests.swift` 的 13 個測試包含持有實體工作跨 invalidate、250 ms 限流、1 秒／晚完成拒收、持續輸入不飢餓、strided BGRA 與五種工具、非對稱四色的通道／上下方向 oracle、成功取樣後沒有新來源活動的真實 expiry task、portrait/landscape 上限、HDR／尺寸拒絕、clean aperture／pixel aspect、工具關閉與 LUT-only 零取樣、舊 view 與 flush 完成 ownership、同 layer 重複 attach／舊 detach／暫停恢復反例。
- **NOT_RUN（compile/runtime）**：此環境沒有 Swift、Xcode、iOS SDK、Simulator；沒有安裝，也沒有執行 app compile、XCTest、真實 Apple readback、效能測量、iPhone／相機連線或 CI。測試來源存在不代表通過。
- 必要後續：在 Xcode 編譯並執行本組與既有 RTP owner／Live View tests；以合成 H.264 decoder 畫面驗證 17.4+ 讀回、nil／重複 attach／flush／背景恢復、直橫向與非正方 pixel aspect、色塊／上下方向、1 秒 stall 清除；在實際 iPhone／Canon 畫面記錄採樣成功率、FPS／latency／memory，以及音訊與原生視訊是否維持。17.0–17.3 需驗證功能 gate。

## Release assessment

目前版本基準 `0.13.0`；本組是新增可見能力，若日後整合發布，impact 建議 **minor**，不在本組改版。尚未取得原生編譯、Simulator 或實體 iPhone／相機證據，不可稱 PR ready、main accepted、已發布、完整 G07／native LUT parity 或跨裝置可用性驗證通過。
