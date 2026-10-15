# 原生 RTP LUT 與監看一致性候選（2026-10-14）

> 以下為 10/14 原候選紀錄。10/15 exact-head 原生執行已失敗結束；最新結果、修正及待驗界線見 [原生補驗紀錄](native-rtp-followup-2026-10-15.md)。原始 NOT_RUN 紀錄不能被當作最新狀態。

## 狀態與範圍

這是兩個原開發分支的本地候選。上一批三端 exact-head CI 全綠仍是上一批證據；不得用它宣稱本文件的新原生程式已編譯。PC 保持原 head，不重做既有 LUT／監看工具。兩個手機候選尚未合在同一個提交樹；Git 分支同步也不等於產品功能對齊。

本群補原生 RTP 連續影片的 2–64 邊長 3D `.cube` 顯示，並讓既有直方圖、波形、斑馬紋、假色、峰值分析套 LUT 後的畫面。LUT 僅在記憶體，沒有新增相機命令、檔案上傳、持久設定或音訊轉碼。清除 LUT 恢復未套用原生顯示。

顏色契約是 8-bit SDR 的顯示預覽數值，紅座標最快、domain 正規化、八角點三線性插值、插值後裁切輸出。不是 RAW／scene-linear／校準曝光。未標註顏色的來源只提供明示的預覽近似；已標註 HDR、廣色域、高位深或未知格式不猜測轉換，保留未套 LUT 的原生影片並顯示原因。不同硬體解碼器的色彩轉換及端到端校準仍待實機比對。

## 原生管線與界限

- Android：MediaCodec → SurfaceTexture/OES → GLES 3 浮點 3D LUT → 原本可見 Surface。每幀讀 decoder transform，不用 CPU Bitmap 冒充影片。PixelCopy 從同一個最終 Surface 取樣，不再套第二次 LUT。單一待繪 callback、16 筆 frame metadata、原有 250 ms 取樣間隔與 1 秒有效期限保留。新舊 decoder 之間等候真正 codec／EGL 釋放，快速連續取消不能穿越前一個實體釋放屏障。
- iOS：VideoToolbox → Core Image／Metal → 最多 3 個自有 BGRA output buffer → 原本 AVSampleBufferVideoRenderer。一次只有一個實體處理工作，MainActor 完成前不釋放工作槽；世代、來源、顯示層及設定不符的晚結果不能發布。最多 4 個待處理主執行緒投遞。最大輸出 8,847,360 pixels、各邊 8,192。
- iOS 17.4+ 監看讀回實際 renderer buffer；17.0–17.3 使用同一個已提交 renderer 的處理後 buffer，不宣稱有較新系統的顯示讀回 API。採樣仍是最多 120×80、每秒最多 4 次，不是逐幀示波器。
- 兩端不因顯示 FPS 上限丟掉解碼所需參考圖片。丟輸入、佇列溢位、取消或格式切換後等候 IDR 恢復；iOS 隱藏幀照常解碼但不顯示，亂序／舊投遞不能倒退串流。
- 不支援 GPU／色彩或處理失敗時，只在同一會話回退一次至未套 LUT 原生畫面，不重連相機、不重播控制命令。保留所選 LUT 和可見狀態，不把回退畫面標為已套用。

## 新驗收來源（原生尚未執行）

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
- 未啟動新 Actions／KVM；未推送本候選。原 draft PR 狀態、main 及發版保持原狀。

## Release Assessment（候選增量）

本群加入可見的原生 RTP LUT 能力，建議影響為 **minor**；沒有更動產品版本。沿用原 PR 的 release baseline，正式發版前仍須重新核實當時最新 release、合併樹及發版 gates。現有 blocker 是本候選原生編譯／測試尚未執行，以及實機顏色、持續效能與完整 transport 工作流待驗。此文件不是合併或發版授權，也不宣稱已發行。
