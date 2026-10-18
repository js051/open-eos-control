# Android 原 data 完整來源 host 閉包

2026-10-18，接續 UI host 閉包。原 data 85 類中，37 類已有本輪執行收據；其餘 48 類靜態 517 案使用原完整 production/test 檔、原 fixtures 與已驗官方材料，不抽取函式。

## 首輪與修正

- UsbPtpCameraBackendTest 唯一 ScriptedTransport() 漏必填 advertiseCapture，整類 97 案編譯失敗。明設 false 後完整來源編譯、97/97 通過。該案例於 transport acquisition 取消，仍斷言 handle 關閉且零 PTP 命令；旗標只影響未到達的 DeviceInfo capture 廣告。
- 其餘 47 類 420 案，首輪 418 通過、2 失敗，原始結果保留。
- CcapiRtpTest 的 Media3 LATM parser 因 Android Build native 初始化在 plain JVM 失敗；原類加官方 Robolectric SDK35 runner 後 16/16 通過，實際執行原 LATM parser。

## RTP runtime 語義界線

AndroidCcapiRtpSessionTest plain JVM 9 案有 1 個 audio availability 斷言失敗，另 codec 分支背景 JNI 錯誤。以原類臨時 Robolectric runner 核對後 9 案有 3 個 decoder/readiness failure，不掩蓋任一結果。

已下載官方 Robolectric 4.14.1 的 ShadowMediaCodec 靜態檢查顯示：未註冊 decoder 使用 DEFAULT_CODEC，該實作直接 output.put(input)，未驗 H264；再以輸出 byte count 發布 BufferInfo。因此截斷 synthetic H264 在預設 shadow 會變成「decoded」輸出。此 runner 無法驗原三案所要求的真 codec 拒絕語義，整類 annotation 候選已撤回。不能把 native linkage 錯誤或 shadow passthrough 當真影像解碼驗收。

## 音訊狀態原契約修正

初始化失敗與 LATM parse／reset 失敗各新增一個 listener 事件歷史測試。兩個原產品路徑都先同步發布 owner error，下一行才撤銷 availability，因此錯誤事件仍可能宣稱音訊可用。兩個新增測試分別確定紅；不送 video codec 輸入、沒有 JNI 噪音。

最小修正將原 available=false 的非通知寫入移到同步 owner failure callback 之前，後續 owner callback 同時發布原 error／enabled=false／available=false。沒有更改解析、播放、恢復次數或 deadline。parse reset 失敗測試亦要求 factory 精確兩次，不自動重試。初始化修後先有 7 個原音訊方法與完整 26 個 playback owner 案共 33 項通過。整合 parse 修正首次 34 案有 1 案舊 fixture 同步點失敗：只等 error 就要求已恢復 availability。原測試改等待 factory 精確兩次且 availability 回復，保留原斷言與 timeout，並新增 factory==2 斷言；最終完整來源 8 個 RTP 無 codec 方法＋26 個 playback owner 案全部 34/34 通過，來源 SHA 前後一致，沒有背景例外。

[本批證據](evidence/android-data-host-closure-2026-10-18/) 保留原編譯失敗、47 類首次執行、USB 97、Robo runtime 對照、官方 shadow 實作以及兩組事件契約紅綠。所有輪次按原案例辨識，不重複加總成假覆蓋率。

## Release Assessment

`none`：沒有版本、tag 或 release。本批不宣稱完整 App、真相機或三平台對齊完成。
