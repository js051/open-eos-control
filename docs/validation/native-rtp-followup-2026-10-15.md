# 原生 RTP 補驗與本地修正（2026-10-15）

## Exact-head 終態

- iOS `6807796ae2e962a2f7382c2c60627e6e37283ede`，[run 38675767030](https://github.com/js051/open-eos-control/actions/runs/38675767030)：04:01:53 UTC `failure`。原生編譯成功；538 個 App test methods 中一個非線性 LUT 方法有 10 個色差斷言失敗。39 個 UI methods 中一個雙語錄影流程有 2 個失敗斷言。該分支 Android API 34／36 皆通過。
- Android `685bc3b4970dea22c62fc4e1eaee93c777af40fa`，[run 38675803571](https://github.com/js051/open-eos-control/actions/runs/38675803571)：04:01:04 UTC `failure`。API 34 的 372 個 methods 中只有新 RTP 四角像素方法失敗。API 36 的 372 個 methods 中 50 個失敗：同一 RTP 方法、43 個 Compose 等待、6 個 SAF picker 失敗；後 6 個日誌記錄系統 Quickstep ANR 遮擋。這不是已證明的共同根因，也不豁免失敗。
- 這兩個 run 所有 job 都已結束；沒有因這次紀錄觸發重跑。成功的編譯／其他方法不能替失敗方法或較新的本地提交背書。

## iOS LUT 修正

原設定用有界 sRGB 同時作工作空間及 cube 空間。非線性 fixture 有負數及大於 1 的節點；將節點提前裁切的數學結果吻合已觀察色差，支持色域裁切方向，但日誌沒有完整像素，不能稱已直接觀察 Core Image 內部實作。

本地改用 extended sRGB 工作／cube 空間，保持與 CPU preview 相同的編碼座標和原始浮點節點。只在 cube 插值後才明確裁切至顯示範圍，最終仍輸出 sRGB BGRA8。沒有把工作空間改成 scene-linear，沒有允許 HDR／高位深來源，也沒有改 buffer 上限、取消規則或監看取樣。

原 H.264 非線性測試的 6 級誤差上限保留，失敗時增加合成 quadrant/channel/input/actual/expected 訊息。新增直接 Metal／Core Image 測試使用同一個 production context factory，驗負值及超範圍節點的插值後裁切，容差 3；這可獨立於 decoder 定位色域問題。

後續核對發現 decoded JPEG／Bridge 的舊 `CIContext` 沒指定工作空間，會用預設線性光；原測試只驗輸出尺寸。現在兩條路徑共用 `CubeLut.swift` 的 context／domain／cube／output-clamp 實作。相同 12 個跨端固定向量（其中 6 個 byte 向量）已接至 iOS CPU、PNG/Metal 和 JPEG 原生測試來源，詳見 [LUT 數值契約](../lut-preview.md#shared-numeric-contract-2026-10-15)。仍須實際平台執行，不能用共用程式碼當作像素一致性證明。

## 錄影模式仍未解

繁中 Video segment 點擊後，fixture 20 秒內沒有收到成功的 movie-mode POST。現有日誌不能判斷 SwiftUI binding、admission 或傳輸哪一段未完成。原生 result-bundle 下載經官方既有路徑回覆 `Forbidden`，已停止，沒有改路線；沒有可核實數字 HTTP status。

只在既有 failure catch 補固定白名單狀態／alert 分類的 terminal 輸出，保留原 timeout、一次點擊、錄影／新媒體因果斷言、附件、截圖及 rethrow。未增加重試、放寬等待或猜測產品修復。任意錯誤文字、URL 或相機識別不新增輸出至 terminal。

## 驗證界線及 Release Assessment

本地 unittest 的 CI helper 46 項與驗收守門 28 項通過，變更 Python ruff、版本一致性與 whitespace 檢查通過。先前以 pytest 收集 71 項的紀錄包含一個被額外當作測試的既有 `test_tree` fixture helper，以及 return-value warning，未把它當成新增驗證。這些是來源／隱私守門，並非 Swift 編譯、GPU 或 UIKit 執行。本地無 Swift/Xcode，新增測試及修正仍為 native **NOT_RUN**。原生 RTP、錄影及較新的交付／取消恢復群需要各自 exact-head 驗收；實機顏色、效能與 provider 落盤另外待驗。

最新已核實發布基線為 [v0.12.0 Development Preview](https://github.com/js051/open-eos-control/releases/tag/v0.12.0)，查核於 2026-10-15 03:54 UTC。累積新 RTP 能力及交付觀察群的影響為 **minor**，本次修正是該群內的正確性補正。未改版本、未授予 PR ready；失敗驗收與未驗實機限制保留，不合併或發版。

## 官方 API 依據

- [Apple extendedSRGB](https://developer.apple.com/documentation/coregraphics/cgcolorspace/extendedsrgb)：與 sRGB 相同色度／編碼，能保存低於 0 及高於 1 的分量。
- [Apple CIContext workingColorSpace](https://developer.apple.com/documentation/coreimage/cicontextoption/workingcolorspace)：輸入與輸出會匹配工作及目的色域，因此工作空間不是無關設定。
- [Apple CIColorCube cubeData](https://developer.apple.com/documentation/coreimage/cicolorcube/cubedata)：32-bit 浮點 premultiplied RGBA 表格，紅座標最快。以上 API 定義支援修正設計；實際 GPU 結果仍須原生驗證。
