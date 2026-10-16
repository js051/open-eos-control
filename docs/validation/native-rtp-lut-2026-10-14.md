# 原生 RTP LUT 與監看一致性候選（2026-10-14）

> 以下為 10/14 原候選紀錄。10/15 exact-head 原生執行已失敗結束；最新結果、修正及待驗界線見 [原生補驗紀錄](native-rtp-followup-2026-10-15.md)。原始 NOT_RUN 紀錄不能被當作最新狀態。

## 狀態與範圍

最新 Android 狀態見下方[首次原生 CI 與未解失敗](#首次-android-原生-ci-與未解失敗2026-10-15)：RTP 候選已推送，exact head 的 API 34／36 原生測試均失敗，不能標為 PR ready。後續本地診斷增量尚未經 Android SDK 編譯或 emulator 執行。本文保留首次發布前的本地檢查紀錄，但該歷史段落的 NOT_RUN／未推送描述不代表目前整批狀態。

本文件最初涵蓋兩個原開發分支的本地候選。上一批三端 exact-head CI 全綠仍是上一批證據；不得套用到後續增量。PC 不重做既有 LUT／監看工具。兩個手機候選尚未合在同一個提交樹；Git 分支同步也不等於產品功能對齊。

本群補原生 RTP 連續影片的 2–64 邊長 3D `.cube` 顯示，並讓既有直方圖、波形、斑馬紋、假色、峰值分析套 LUT 後的畫面。LUT 僅在記憶體，沒有新增相機命令、檔案上傳、持久設定或音訊轉碼。清除 LUT 恢復未套用原生顯示。

顏色契約是 8-bit SDR 的顯示預覽數值，紅座標最快、domain 正規化、八角點三線性插值、插值後裁切輸出。不是 RAW／scene-linear／校準曝光。未標註顏色的來源只提供明示的預覽近似；已標註 HDR、廣色域、高位深或未知格式不猜測轉換，保留未套 LUT 的原生影片並顯示原因。不同硬體解碼器的色彩轉換及端到端校準仍待實機比對。

## 原生管線與界限

- Android：MediaCodec → SurfaceTexture/OES → GLES 3 浮點 3D LUT → 原本可見 Surface。每幀讀 decoder transform，不用 CPU Bitmap 冒充影片。PixelCopy 從同一個最終 Surface 取樣，不再套第二次 LUT。單一待繪 callback、16 筆 frame metadata、原有 250 ms 取樣間隔與 1 秒有效期限保留。新舊 decoder 之間等候真正 codec／EGL 釋放，快速連續取消不能穿越前一個實體釋放屏障。
- iOS：VideoToolbox → Core Image／Metal → 最多 3 個自有 BGRA output buffer → 原本 AVSampleBufferVideoRenderer。一次只有一個實體處理工作，MainActor 完成前不釋放工作槽；世代、來源、顯示層及設定不符的晚結果不能發布。最多 4 個待處理主執行緒投遞。最大輸出 8,847,360 pixels、各邊 8,192。
- iOS 17.4+ 監看讀回實際 renderer buffer；17.0–17.3 使用同一個已提交 renderer 的處理後 buffer，不宣稱有較新系統的顯示讀回 API。採樣仍是最多 120×80、每秒最多 4 次，不是逐幀示波器。
- 兩端不因顯示 FPS 上限丟掉解碼所需參考圖片。丟輸入、佇列溢位、取消或格式切換後等候 IDR 恢復；iOS 隱藏幀照常解碼但不顯示，亂序／舊投遞不能倒退串流。
- 不支援 GPU／色彩或處理失敗時，只在同一會話回退一次至未套 LUT 原生畫面，不重連相機、不重播控制命令。保留所選 LUT 和可見狀態，不把回退畫面標為已套用。

## 發布前新增驗收來源（歷史本地狀態）

合成 fixture 為自行產生的 64×48 非對稱四色 8-bit BT.709 H.264；沒有使用者影像。去除 encoder SEI 後 167 bytes，SHA256：`245005cbafd7fc1f3a493eb48542e777ed707bde39d63c5da6a26db8104ac6ac`。

Android instrumentation 使用真正 MediaCodec、GLES、SurfaceView、PixelCopy，驗四角方向、identity／紅藍交換、非單位 domain／非線性八角插值、既有 sampler 的同 Surface 五種監看分析與暫停清除。只控制來源通知 owner，不 mock 解碼或 GPU。

iOS XCTest 使用真正 H.264 sample builder、VideoToolbox、Core Image／Metal、native layer。驗方向／通道、非線性 domain 對 CPU oracle、套 LUT 一次的五種既有分析入口、decode-only 不配置輸出、3-buffer 上限與恢復、17.4+ 真顯示讀回、取消晚完成及高位深拒絕；另驗有界投遞／亂序恢復。17.4 前只跳過該系統不存在的顯示讀回測試，不能據此當成新 API 通過。

待平台編譯及真終態測試後才能標為原生通過。仍需實機：支援相機 RTP 長時間拍攝／錄影、LUT 開關與換檔、旋轉／clean aperture／pixel aspect、暫停與背景恢復、熱量／幀率／記憶體、斷線、挑片／交付取消後恢復；OS provider 實際落盤及雲端同步也不能由 simulator 推論。不同 transport 的能力缺口與直接 USB／廠商 SDK 限制不在本群假裝填平。

## 官方 API 依據

- [Android SurfaceTexture](https://developer.android.com/reference/android/graphics/SurfaceTexture)：GL 執行緒、每幀 transform 與 timestamp。
- [Android GLES30](https://developer.android.com/reference/android/opengl/GLES30)：RGBA32F 3D texture；手動 texelFetch 插值不依賴浮點 linear filtering extension。
- [Apple VideoToolbox decode](https://developer.apple.com/documentation/videotoolbox/vtdecompressionsessiondecodeframe(_:samplebuffer:flags:infoflagsout:outputhandler:))：不啟用非同步旗標的同步回呼契約。
- [Apple CIColorCubeWithColorSpace](https://developer.apple.com/documentation/coreimage/cicolorcubewithcolorspace)：明確的 LUT 數值色域。
- [Apple output-pool threshold](https://developer.apple.com/documentation/corevideo/kcvpixelbufferpoolallocationthresholdkey)：實體 output 配置上限。

## 此分支本地驗證

- 平台候選：iOS。原生 SDK／GPU／renderer 測試 **NOT_RUN**；環境沒有 Android SDK／Gradle 或 Xcode／Swift，不使用假 API stub 冒充編譯。
- Bridge pytest：412 項通過；ruff 通過。使用此工作樹的 PYTHONPATH，未拿另一分支模組代跑。
- Python 驗收／來源守門：23 項通過；來源字串守門不等於原生執行。

## 此分支首次發布前本地驗證（歷史紀錄）

- 平台候選：Android。原生 SDK／GPU／renderer 測試 **NOT_RUN**；環境沒有 Android SDK／Gradle 或 Xcode／Swift，不使用假 API stub 冒充編譯。
- Bridge pytest：412 項通過；ruff 通過。使用此工作樹的 PYTHONPATH，未拿另一分支模組代跑。
- Python 驗收／來源守門：30 項通過；來源字串守門不等於原生執行。
- 固定官方 Kotlin 2.2.21／coroutines 1.9.0／JUnit 4.13.2 實際編譯及執行：18 項通過（10 項新 LUT／handoff／prediction policy，8 項既有取樣 owner／時限 policy）。這不是 Android SDK 編譯。
- 未啟動新 Actions／KVM；未推送本候選。原 draft PR 狀態、main 及發版保持原狀。

## Release Assessment（候選增量）

本群加入可見的原生 RTP LUT 能力，連同已委託交付增量的累積影響為 **minor**；沒有更動產品版本。2026-10-15 03:54 UTC 經 GitHub 核實的最新 baseline 為 **v0.12.0 Development Preview（prerelease）**；正式發版前仍須重新核實最新 release、合併樹及發版 gates。目前 blocker 是下述 exact-head 原生測試失敗、診斷增量未經平台驗證，以及實機顏色、持續效能與完整 transport 工作流待驗。此文件不是合併或發版授權，也不宣稱已發行。

## 首次 Android 原生 CI 與未解失敗（2026-10-15）

上述「尚未執行」是發布前的本地狀態。Android head `685bc3b4970dea22c62fc4e1eaee93c777af40fa` 的 [run 38675803571](https://github.com/js051/open-eos-control/actions/runs/38675803571) 已執行真實 emulator：API 34 為 372 tests／1 failure，API 36 為 372 tests／50 failures。兩者的新 RTP 案例都在 `Decoder SDR conversion or quadrant orientation differs` 失敗；原日誌未包含實際 RGB、座標、通道或 decoder 色彩欄位，無法區分色彩矩陣、方向或 Surface 讀回問題。依測試順序，三次 decoder/GPU 呈現及 swap 的五種 post-LUT sampler 比對已返回，之後才進入失敗的逐像素斷言；這不等於整條原生驗收通過。

API 36 的其餘 49 failures 包括 43 個 Compose 10 秒 timeout 和 6 個 SAF picker timeout。六個 picker 的原始錯誤都直接觀察到系統 `Quickstep isn't responding` 對話框。這是實際系統遮擋證據，不足以證明每一個 timeout 的根因、判定與 RTP 無關，或把紅燈豁免；API 34 的其餘 371 tests 通過也不能代替 API 36 驗收。不增加 timeout、不自動關閉系統錯誤對話框、不重跑或略過測試。

既有本機 FFmpeg 離線解碼同一份嵌入 fixture，四個採樣點 RGB 依序為 `(223,39,54)`、`(46,199,71)`、`(39,79,216)`、`(175,142,102)`，均符合原 expected±18。這只驗證 fixture／參考值，不能代替 Android MediaCodec、GLES 或 PixelCopy。

後續本地修改僅補測試診斷：保留原 18／3／6 數值誤差、原時間上限及產品 shader；失敗行包含原 assertion/cause、座標／通道／actual／expected，及最多三個合成 frame 的固定四點 RGB、decoder 名稱上限 96 字元、白名單數值 format 欄位、輸出／呈現 timestamp、失敗 phase 和 cleanup 結果。不輸出使用者影像、裝置識別或完整 vendor metadata。另將三張 capture 的回收移入包覆全部 render 的 finally，避免後段 render 失敗時先前 Bitmap 未回收，並保留 renderer 釋放錯誤而不遮掉主要失敗。

2026-10-15 04:12 UTC 本地檢查：兩個相關來源守門檔共 12/12 通過，`git diff --check` 通過，既有時間上限逐項與修改前一致。固定 Kotlin 2.2.21 的 PSI parser 僅確認修改檔沒有語法錯誤，沒有 Android API stub，也沒有執行 Android type checking；不得稱為 Android 編譯成功。

這是診斷候選，尚未修證 Android 像素差異或 API 36 系統遮擋。新的 instrumentation 來源仍待 Android SDK 編譯及 exact-head API 34／36 runtime；本地來源守門與離線 fixture 檢查不能提升為原生通過或 PR ready。未啟動新 Actions／KVM／rerun，未安裝工具。
