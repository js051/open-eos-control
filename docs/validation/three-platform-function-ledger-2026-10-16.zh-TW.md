# 原功能與三端交付對帳（2026-10-16 UTC）

> 最新平台批次終態見文末「2026-10-20 平台證據對帳」；下列各舊日期／SHA 表格保留歷史，不能當成目前仍全部 NOT_RUN。Latest completed platform runs are reconciled at the end; dated snapshots remain historical.

## 固定對帳基準

產品 source 固定 `dev` 的 `41bf6ef0684f982937f5d5fdf428feca3b683f04`，tree `3d67cdb74b6c95010ba82f0035421679af548e16`。本文件在另一個從此 dev 出發的 docs 分支製作，不改 Firefox 受驗產品 HEAD。原三來源 archive refs、舊 receipt、六個 KVM 待批快照均保留。

範圍是原 [product workflow acceptance](../product-workflow-acceptance.zh-TW.md) 的 P0/P1/P2/P3/Research，以及原 feature-status 的 89 個能力列。功能名稱不是 runtime 證據；這不是 89/89 PASS 或功能完成率。本輪核對現行入口/後端檔、已修歷史缺口及明確 unsupported，沒有逐項重新操作真相機。

五個判斷不可合併：A＝產品入口與後端路徑已接通；B＝平台/整合驗證仍缺；C＝OS/transport 本來不同；D＝後端明確未實作；E＝未知/Research。同一項可同時為 A+B 或 A+C，不能把 D/E 改寫成「只待測試」。

## 平台執行與可下載狀態（適用下列全部列）

| 證據 | 此產品 HEAD 的事實 |
|---|---|
| 已實際執行的本地檢查 | source guards 148/148、整合 gphoto2 真合成子程序/HTTP fixture 44/44、PC production legacy parser 9/9。gphoto2 原修正來源另有163/163、PC原listing回歸74/74；來源不同明確分列。 |
| Android | 此共同 HEAD SDK build/unit/instrumentation **NOT_RUN**。既有原生/KVM失敗或舊SHA綠燈都不提升成此HEAD通過。 |
| iOS | 此共同 HEAD Swift/Xcode/XCTest/UI/GPU **NOT_RUN**。 |
| PC/browser | Node/Python host 已執行以上窄範圍；此HEAD Firefox GUI **NOT_RUN（等待受控同desktop流程）**；Playwright Chromium未完成。Windows/WSL實際相機未跑。 |
| 真相機／外部儲存provider | 此HEAD未有逐項實機驗收；命令接受/合成fixture不能證明曝光、對焦、停止或外部同步。 |
| 可下載App／installer | **否：未製作/發佈對應此HEAD的新APK、iOS安裝包或Windows installer。** GitHub dev可取得原始碼，不等於可下載已驗App；既有release產物不可冒稱此候選。 |
| CI／PR／發布 | dev-only非force push已確認；查該HEAD Actions runs為0。未建PR、未合main、未發版；六KVM待批不會因dev文件改變。 |

## 原完整流程對帳

| 原需求 | 三端目前已接通的caller/backend | 未完成與合理差異 |
|---|---|---|
| P0 安全停止曝光/AF | G04/G05：UI→owner狀態→directCCAPI或Bridge release/start/stop；未知ACK保留責任與明確stop-only retry | A+B；物理停止、斷網/強殺後相機狀態未證，wired依能力。 |
| P1 長連線、構圖、拍攝/REC、看新素材 | G01/G04/G05/G06/G07/G08；取消/舊session清理、倍率geometry退休、Near/Far晚回覆隔離、只讀查圖均有本地修正 | A+B；native RTP GPU/UI有歷史失敗，後續共同HEAD未驗；optical清晰非軟體ACK。 |
| P2 找片/篩選/取消 | G08；PC UI→NDJSON stream→CCAPI/gphoto2 progressive；mobile consumer同owner/terminal契約 | A+B；gphoto2 gap已補，實際CLI buffering/大卡/Windows與WSL未驗；不等同strict atomic inventory。 |
| P2 原檔到目的地/接續/前景自動交付 | G09；Android MediaStore/SAF、iOS Files協調寫入、PC明選資料夾；durable receipt與明確接續 | A+B+C；prepared、provider callback、寫入完成、cloud同步不可混稱；不含背景常駐/任意跨程序Range續傳。 |
| P3 初次設定/遇錯恢復 | G01/G13；typed reason、empty scan、取消後改設定再明確重試已有入口 | A+B+C；真OS picker/權限/觸控與語言布局仍待平台驗收，不要求關TLS或sandbox。 |
| Research/transport拓展 | G11/G12 | D/E仍存在：iOS direct USB、真EDSDK、未廣告USB座標操作、gphoto2 metadata mutation。不能為表面一致開假控制。 |

## 三端實際caller/backend索引

每列路徑均指向固定共同 HEAD；同一群的多個UI入口由app state/view model調用client/session，再到backend。此表是來源可追蹤性，不代替下面各功能的具體能力限制。

| 群組 | Android入口→資料/後端 | iOS入口→session/client | PC入口→HTTP/backend | 目前實作/差異 |
|---|---|---|---|---|
| G01 連線／發現／取消重試 | [ConnectionScreen.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/ConnectionScreen.kt)<br>[CameraViewModel.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/CameraViewModel.kt)<br>[ConnectionRetirement.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/ConnectionRetirement.kt)<br>[CcapiClient.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/CcapiClient.kt) | [ConnectionView.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/ConnectionView.swift)<br>[CameraAppState.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/CameraAppState.swift)<br>[CCAPIClient.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSCore/Sources/OpenEOSCore/CCAPIClient.swift)<br>[DesktopBridgeClient.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSCore/Sources/OpenEOSCore/DesktopBridgeClient.swift) | [connection-guidance.js](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/static/connection-guidance.js)<br>[app.js](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/static/app.js)<br>[app.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/app.py)<br>[ccapi.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/ccapi.py) | CCAPI/Bridge 已接通；USB/network routing 另依平台。 |
| G02 狀態／能力／診斷 | [Diagnostics.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/Diagnostics.kt)<br>[CameraModels.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/CameraModels.kt)<br>[CcapiClient.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/CcapiClient.kt) | [CameraAppState.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/CameraAppState.swift)<br>[CCAPIDiagnostics.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSCore/Sources/OpenEOSCore/CCAPIDiagnostics.swift)<br>[CCAPIClient.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSCore/Sources/OpenEOSCore/CCAPIClient.swift) | [app.js](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/static/app.js)<br>[models.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/models.py)<br>[ccapi.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/ccapi.py) | 有資料才呈現；supported/observed/operator-confirmed 分開。 |
| G03 設定／維護／拍攝配置 | [ShootingSettingWorkflow.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/ShootingSettingWorkflow.kt)<br>[MaintenanceWorkflow.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/MaintenanceWorkflow.kt)<br>[CcapiClient.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/CcapiClient.kt) | [CameraControlView.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/CameraControlView.swift)<br>[CameraAppState.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/CameraAppState.swift)<br>[CCAPIClient.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSCore/Sources/OpenEOSCore/CCAPIClient.swift) | [app.js](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/static/app.js)<br>[ccapi.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/ccapi.py)<br>[gphoto2.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/gphoto2.py) | CCAPI 有 gated 路徑；wired 只承諾可廣告與 readback 的子集。 |
| G04 Photo／Bulb／REC／停止責任 | [CameraViewModel.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/CameraViewModel.kt)<br>[RecordingLifecycle.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/RecordingLifecycle.kt)<br>[BridgeShutterReleaseSession.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/BridgeShutterReleaseSession.kt) | [CameraControlView.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/CameraControlView.swift)<br>[CameraAppState.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/CameraAppState.swift)<br>[CameraSession.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/CameraSession.swift)<br>[CCAPIClient.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSCore/Sources/OpenEOSCore/CCAPIClient.swift) | [app.js](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/static/app.js)<br>[app.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/app.py)<br>[ccapi.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/ccapi.py) | 命令 ACK、停止責任、只讀查圖與實際光學結果分開。 |
| G05 AF／Near-Far／倍率／telemetry | [CameraFocusReadGate.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/CameraFocusReadGate.kt)<br>[CameraViewModel.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/CameraViewModel.kt)<br>[CameraRepository.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/CameraRepository.kt)<br>[DesktopBridgeClient.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/DesktopBridgeClient.kt) | [CameraControlView.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/CameraControlView.swift)<br>[CameraAppState.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/CameraAppState.swift)<br>[CameraSession.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/CameraSession.swift)<br>[CCAPIClient.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSCore/Sources/OpenEOSCore/CCAPIClient.swift) | [app.js](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/static/app.js)<br>[app.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/app.py)<br>[ccapi.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/ccapi.py)<br>[gphoto2.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/gphoto2.py) | held AF/telemetry/倍率退休已有路徑；gphoto2 座標 AF/Click WB 明確未實作。 |
| G06 Live View／RTP／音訊／事件 | [CameraComponents.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/CameraComponents.kt)<br>[AndroidCcapiRtpSession.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/AndroidCcapiRtpSession.kt)<br>[AndroidRtpAudioPlayer.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/AndroidRtpAudioPlayer.kt)<br>[CcapiClient.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/CcapiClient.kt) | [LiveViewSurface.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/LiveViewSurface.swift)<br>[IOSCcapiRTP.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/IOSCcapiRTP.swift)<br>[IOSCcapiRTPAudio.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/IOSCcapiRTPAudio.swift)<br>[CCAPIClient.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSCore/Sources/OpenEOSCore/CCAPIClient.swift) | [app.js](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/static/app.js)<br>[ccapi.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/ccapi.py)<br>[rtp.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/rtp.py) | 三端 transport/owner 路徑存在；真 decoder、UDP、長時效能與弱網驗收分開。 |
| G07 LUT／scopes／幾何／local video | [CameraComponents.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/CameraComponents.kt)<br>[NativeRtpMonitorSampler.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/NativeRtpMonitorSampler.kt)<br>[NativeRtpLutRenderer.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/NativeRtpLutRenderer.kt) | [LiveViewSurface.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/LiveViewSurface.swift)<br>[IOSRTPVideoProcessing.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/IOSRTPVideoProcessing.swift)<br>[IOSRTPPixelMonitoring.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/IOSRTPPixelMonitoring.swift) | [app.js](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/static/app.js)<br>[monitoring.js](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/static/monitoring.js)<br>[local-video.js](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/static/local-video.js) | mobile native LUT/分析已實作，原生有歷史失敗及後續未驗；PC UVC/HDMI 為平台專屬。 |
| G08 找片／漸進清單／篩選／預覽 | [MediaScreen.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/MediaScreen.kt)<br>[MediaLibrary.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/MediaLibrary.kt)<br>[DesktopBridgeClient.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/DesktopBridgeClient.kt) | [MediaView.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/MediaView.swift)<br>[MediaLibrary.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/MediaLibrary.swift)<br>[DesktopBridgeClient.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSCore/Sources/OpenEOSCore/DesktopBridgeClient.swift) | [app.js](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/static/app.js)<br>[media-listing.js](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/static/media-listing.js)<br>[media_listing.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/media_listing.py)<br>[gphoto_listing.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/gphoto_listing.py)<br>[gphoto2.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/gphoto2.py)<br>[ccapi.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/ccapi.py) | CCAPI及gphoto2逐步/取消已有後端與caller；mobile legacy全陣列fail-closed已補。 |
| G09 原檔目的地／工作清單／前景交付 | [DownloadHistoryTransfer.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/DownloadHistoryTransfer.kt)<br>[ForegroundJpegImportRunner.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/ForegroundJpegImportRunner.kt)<br>[DownloadHistoryStore.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/DownloadHistoryStore.kt) | [ForegroundMediaView.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/ForegroundMediaView.swift)<br>[CameraAppState.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/CameraAppState.swift)<br>[ForegroundFilesDelivery.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/ForegroundFilesDelivery.swift)<br>[MediaBatchDelivery.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/MediaBatchDelivery.swift) | [app.js](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/static/app.js)<br>[media-auto-import.js](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/static/media-auto-import.js)<br>[media-transfer-history.js](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/static/media-transfer-history.js) | iOS Files已實際協調寫入；各OS授權/完成證據不同，不承諾任意Range續傳或背景常駐。 |
| G10 媒體metadata／刪除／upload | [MediaMetadataBatch.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/MediaMetadataBatch.kt)<br>[MediaDeleteBatch.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/MediaDeleteBatch.kt)<br>[CcapiClient.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/CcapiClient.kt)<br>[UsbPtpCameraBackend.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/UsbPtpCameraBackend.kt) | [CameraAppState.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/CameraAppState.swift)<br>[CCAPIClient.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSCore/Sources/OpenEOSCore/CCAPIClient.swift)<br>[DesktopBridgeClient.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSCore/Sources/OpenEOSCore/DesktopBridgeClient.swift) | [app.js](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/static/app.js)<br>[app.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/app.py)<br>[ccapi.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/ccapi.py)<br>[gphoto2.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/gphoto2.py) | direct CCAPI mutation已有；gphoto2 protection/rating/rotation/archive未實作；upload依wired，CCAPI不推定。 |
| G11 有線/PTP/Canon USB | [AndroidUsbPtpTransport.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/AndroidUsbPtpTransport.kt)<br>[UsbPtpCameraBackend.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/UsbPtpCameraBackend.kt)<br>[PtpProtocol.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/PtpProtocol.kt) | [ConnectionView.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/ConnectionView.swift)<br>[DesktopBridgeClient.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSCore/Sources/OpenEOSCore/DesktopBridgeClient.swift) | [app.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/app.py)<br>[gphoto2.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/gphoto2.py) | Android direct USB／PC gphoto2／iOS經Bridge不等價；iOS direct USB未實作。 |
| G12 分發／provider／Research | [Diagnostics.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/Diagnostics.kt)<br>[UsbPtpCameraBackend.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/data/UsbPtpCameraBackend.kt) | [ConnectionView.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/ConnectionView.swift)<br>[DesktopBridgeClient.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSCore/Sources/OpenEOSCore/DesktopBridgeClient.swift) | [app.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/app.py)<br>[edsdk.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/edsdk.py)<br>[edsdk_loader.py](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/edsdk_loader.py) | 真EDSDK只有provider邊界；unknown vendor/iOS direct USB仍Research。 |
| G13 產品殼／英繁中／可達性 | [CameraControlScreen.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/CameraControlScreen.kt)<br>[ConnectionScreen.kt](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/android/app/src/main/java/dev/openeos/control/ui/ConnectionScreen.kt) | [CameraControlView.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/CameraControlView.swift)<br>[ConnectionView.swift](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/ios/OpenEOSControl/App/ConnectionView.swift) | [index.html](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/static/index.html)<br>[app.js](https://github.com/js051/open-eos-control/blob/41bf6ef0684f982937f5d5fdf428feca3b683f04/bridge/open_eos_bridge/static/app.js) | 入口/資源存在；大字、方向、觸控、鍵盤與OS picker需各平台操作驗收。 |

## 已修歷史缺口與確證未實作

- Held AF：PC app.js `heldAutofocusCommand/startHeldAutofocus/stopHeldAutofocus` → app.py → ccapi.py `start_autofocus/stop_autofocus`；iOS CameraControlView → CameraAppState → CameraSession → CCAPIClient/DesktopBridgeClient。Android Bridge也有對應；不推定gphoto2/USB held AF。
- AF telemetry：PC focus-info輪詢與bridge endpoint；iOS CameraAppState focusInfo owner loop；Android DesktopBridgeClient→CameraBackend→CameraRepository→UI gate。不是命令marker冒充相機回報。
- Native RTP LUT/分析：Android CameraComponents→NativeRtpLutRenderer→同幀320px GPU readback→NativeRtpMonitorSampler；iOS IOSCcapiRTP→IOSRTPVideoProcessing→processed pixelBuffer→IOSRTPPixelMonitoring。Android已不是舊PixelCopy架構；原生顏色/呈現驗收仍欠。iOS renderer/presentation API仍有OS版本gate。
- PC漸進清單：app.js→app.py media route→media_listing.py→ccapi.py或gphoto2.py `list_media_progressive`。gphoto2真CLI bounded pump、取消/timeout/背壓、session lock、部分cache不提交及WSL異常fence已合入。legacy JSON三端拒絕invalid/duplicate/overflow，不能截斷成COMPLETE。
- iOS外部Files：ForegroundMediaView→CameraAppState `chooseForegroundFolder/acceptForegroundFolder/startForegroundMedia`→ForegroundFilesDelivery實際security-scope/coordinated寫入；手動匯出另有MediaFilesExport。不是僅保留私有副本或改saved字串。
- **D：gphoto2.py `tap_focus/click_white_balance` 明確unsupported；`set_media_protection/rating/rotation/archive`亦unsupported。** iOS經此Bridge仍承襲缺口；Android USB僅部分操作，R6III座標ops尚未證。不是「只待驗收」。
- **E：iOS direct USB尚未實作；edsdk.py/edsdk_loader.py是外部provider邊界，不是working Canon SDK binding；未知vendor設定仍Research。**需有效公開契約、合法SDK/API與裝置證據，不擴成這批新feature。
- **C：PC local UVC/HDMI專屬；OS目的地/grant不同；directCCAPI upload三端均無可證承諾。**手機有同名入口不代表具有PC輸入或另一transport操作。

## 原89列逐項索引（全量範圍，不是全量平台通過）

「平台/可下載」每列均適用上方共同HEAD表：原生N、browser待驗、此HEAD成品未發佈。Gxx是上方三端caller/backend的可點選索引；列出來源存在不宣稱本輪對每列執行測試。

| 原行 | 原能力名稱 | 三端caller/backend群 | 2026-10-16實作/缺口增補 | 此HEAD平台/成品 |
|---|---|---|---|---|
| 36 | Discovery and API versions | G01 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 37 | Capability evidence diagnostics | G02 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 38 | Identity, battery, storage | G02 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 39 | CCAPI recordable information | G02 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 40 | CCAPI lens and temperature status | G02 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 41 | CCAPI event polling | G06 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 42 | ISO, Tv, Av, WB | G03 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 43 | Dynamic camera settings | G03 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 44 | CCAPI camera beep and display-off timeout | G03 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 45 | CCAPI optical zoom | G03 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 46 | CCAPI sound recording level | G03 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 47 | CCAPI sound recording mode, wind filter and attenuator | G03 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 48 | CCAPI focus bracketing | G03 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 49 | CCAPI movie quality, high frame rate, cropping and format | G03 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 50 | CCAPI dual-card selection | G03 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 51 | CCAPI capture directory | G03 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 52 | CCAPI file naming | G03 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 53 | CCAPI Movie Mode | G04 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 54 | CCAPI Auto Power Off and camera sleep | G03 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 55 | CCAPI sensor cleaning | G03 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 56 | Media details, protection, archive, rating and display rotation | G10 | A+B+D：CCAPI mutation已有；gphoto2四種metadata mutation未實作。 | 原生N／browser待驗／成品無 |
| 57 | JPEG Live View | G06 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 58 | CCAPI multipart JPEG Live View | G06 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 59 | Android Live View monitoring assists | G07 | A+B：decoded與mobile native LUT/分析已有；native舊失敗與新NOT_RUN保留。 | 原生N／browser待驗／成品無 |
| 60 | Android capture-mode and exposure interaction | G04 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 61 | iOS Live View monitoring assists | G07 | A+B：decoded與mobile native LUT/分析已有；native舊失敗與新NOT_RUN保留。 | 原生N／browser待驗／成品無 |
| 62 | PC Live View monitoring assists | G07 | A+B：PC decoded Bridge/local video監看已有；此HEAD browser尚未驗。mobile native另見59/61列。 | 原生N／browser待驗／成品無 |
| 63 | Still capture | G04 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 64 | Android shutter autofocus selection | G04 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 65 | Bulb long exposure | G04 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 66 | Independent AF command | G05 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 67 | Android held AF-ON | G05 | A+B：held AF已補PC/iOS與Android Bridge；USB/gphoto2不推定。 | 原生N／browser待驗／成品無 |
| 68 | Android bounded focus release recovery | G04 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 69 | Shutter half-press | G05 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 70 | Movie recording start/stop | G04 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 71 | Tap focus | G05 | A+B+D：CCAPI已有；gphoto2座標操作未實作；Android USB僅廣告gate，R6III未證。 | 原生N／browser待驗／成品無 |
| 72 | Click White Balance | G05 | A+B+D：CCAPI已有；gphoto2座標操作未實作；Android USB僅廣告gate，R6III未證。 | 原生N／browser待驗／成品無 |
| 73 | Media browser | G08 | A+B：CCAPI與gphoto2 progressive/cancel已接通；mobile legacy fail-closed已補。 | 原生N／browser待驗／成品無 |
| 74 | Media thumbnails | G08 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 75 | Media display preview | G08 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 76 | Media download | G09 | A+B+C：iOS Files已有真正寫入；各OS目的地結果/授權不同。 | 原生N／browser待驗／成品無 |
| 77 | Media video playback | G08 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 78 | Media upload | G10 | A+B+C：wired upload依能力；directCCAPI三端無可證upload。 | 原生N／browser待驗／成品無 |
| 79 | Media deletion | G10 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 80 | CCAPI RTP H.264/AAC LATM Live View on Android | G06 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 81 | CCAPI RTP H.264/AAC LATM Live View on iOS | G06 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 87 | Android camera Wi-Fi routing | G01/G11 | A+B+C：Android相機Wi-Fi network routing為平台專屬；cellular coexistence/真網路待驗，iOS/PC使用各自網路機制。 | 原生N／browser待驗／成品無 |
| 88 | Android USB device and PTP-interface diagnostics | G11 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 89 | Android PTP container transport, session and DeviceInfo | G11 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 90 | Android PTP storage and media | G11/G08/G10 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 91 | Android PTP standard still capture | G11/G04 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 92 | Android PTP battery and standard property control | G11/G03 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 93 | Android Canon EOS USB event synchronization | G11/G06 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 94 | Android Canon EOS USB lens status | G11/G02 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 95 | Android Canon EOS camera clock synchronization | G11/G03 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 96 | Android Canon EOS remote release, half-press and Bulb | G11/G04/G05 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 97 | Android Canon EOS capture destination | G11 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 98 | Android Canon EOS active recording card | G11 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 99 | Android Canon EOS native autofocus | G11/G05 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 100 | Android Canon EOS manual focus drive | G11/G05 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 101 | Android Canon EOS Live View focus magnification | G11/G05 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 102 | Android Canon EOS JPEG Live View | G11/G06 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 103 | Android Canon EOS ISO/Tv/Av/WB | G11/G03 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 104 | Android Canon EOS movie start/stop | G11/G04 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 105 | Canon EOS wired text metadata | G11/G10 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 106 | Android Canon EOS shooting mode | G11/G03 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 107 | Android Canon EOS dedicated Photo/Video switch | G11/G04 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 108 | Android Canon EOS AF/drive/metering/picture-style vendor settings | G11/G03 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 109 | Android Canon EOS exposure/color/bracketing vendor settings | G11/G03 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 110 | Android Canon EOS aspect ratio, power-zoom speed and auto power-off | G11/G03 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 111 | Android Canon EOS Auto Lighting Optimizer | G11/G03 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 112 | Android Canon EOS image quality | G11/G03 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 113 | Android Canon EOS remaining vendor settings | G12 | E：未知vendor只有結構診斷，未實作可任意控制。 | 原生N／browser待驗／成品無 |
| 114 | Android Canon EOS USB Touch AF | G11/G05 | A+B+D：CCAPI已有；gphoto2座標操作未實作；Android USB僅廣告gate，R6III未證。 | 原生N／browser待驗／成品無 |
| 115 | Android Canon EOS USB Click White Balance | G11/G05 | A+B+D：CCAPI已有；gphoto2座標操作未實作；Android USB僅廣告gate，R6III未證。 | 原生N／browser待驗／成品無 |
| 116 | Desktop Bridge HTTP service | G12 | A+B+C：Bridge/service/分發來源存在；此共同HEAD未製作Windows成品。 | 原生N／browser待驗／成品無 |
| 117 | Windows standalone Desktop Bridge | G12 | A+B+C：Bridge/service/分發來源存在；此共同HEAD未製作Windows成品。 | 原生N／browser待驗／成品無 |
| 118 | libgphoto2 desktop adapter | G11 | A+B+C：Bridge/service/分發來源存在；此共同HEAD未製作Windows成品。 | 原生N／browser待驗／成品無 |
| 119 | libgphoto2 camera clock synchronization | G11/G03 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 120 | libgphoto2 active recording card | G11 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 121 | PC direct CCAPI engine | G01/G03/G06 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 122 | PC control UI | G04/G08/G13 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 123 | PC local UVC/HDMI preview input | G07 | A+B+C：local輸入不提供可信Canon影像座標/相機AF telemetry；既有相機控制session仍可保留，座標/倍率依gate。 | 原生N／browser待驗／成品無 |
| 124 | Android Desktop Bridge client | G01/G11 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 125 | iOS Desktop Bridge client | G01/G11 | A+B+C：Android direct USB與PC gphoto2不同；iOS有線控制僅Bridge，逐operation依能力。 | 原生N／browser待驗／成品無 |
| 126 | Optional Canon EDSDK provider | G12 | D/E：僅provider boundary，真Canon EDSDK binding未實作。 | 原生N／browser待驗／成品無 |
| 127 | iOS CCAPI core | G01/G03/G04/G08 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 128 | iOS camera-control app | G04/G06/G08/G13 | A+B：對應產品入口存在；能力/readback gate與平台驗證分開。 | 原生N／browser待驗／成品無 |
| 129 | iOS USB/PTP | G12 | D/E：已有隔離 port／production coordinator 及未經 Apple 編譯的首次 session adapter 原碼；native wire／產品接線未完，Bridge不等同直連。 | 原生N／browser待驗／成品無 |

## 08:05 UTC 後新增的窄平台證據

本表仍保留初始盤點時的逐列未驗狀態，不追溯改寫為89列全部已驗。以下是後續已實際執行的有界增量：

- [原生 Firefox 合成 CCAPI 連線／Live View／倍率／Near-Far accepted／斷重連](native-firefox-synthetic-2026-10-16.zh-TW.md)：固定產品41bf6ef，已完成所列流程。
- [gphoto 漸進清單／取消／1101筆重試](gphoto-progressive-firefox-2026-10-16.zh-TW.md)：固定2fbeeeb8（相對前者僅docs），真受控CLI與Firefox UI；取消child已reap、新attempt完整成功。
- 兩批未提供mobile native、真相機、Windows/WSL或新發布包；每一項的範圍與未涵蓋部分以各自文件為準。

## 13:13 UTC 交付增量與目前真正待辦

本節更新進展索引，不把前面固定 `41bf6ef` 的歷史89列改寫成新HEAD全部PASS。最後產品修正為 `6362b87edbf7b369d39b4427b1a62b9211cfc773`；其Firefox實看到13:09:35.732已停止，文件封存`99db285d`。本次對帳文件更新不算新產品開發或新測試進展。

| 原群 | 後續產品來源與實際證據 | 仍未證明 |
|---|---|---|
| P2/G09 gphoto下載準備取消 | `c5749763`，[ASGI disconnect/task cancellation→controlled child drain/reap/tmp/lock/newattempt](bridge-gphoto-download-cancellation-2026-10-16.zh-TW.md)，並補同ticket並行、executor飽和、cache put與close exception反例 | 真Windows/WSL remote child、實體相機；人工fence不能代表真remote reap |
| P2/G09 CCAPI下載準備取消 | `bade2398`，[production urllib真HTTP headers/body stall取消](bridge-ccapi-download-cancellation-2026-10-16.zh-TW.md)，12個精準場景及契約回歸 | DNS/connect/TLS初建仍timeout-bound；沒有新增真TLS/proxy/cross-origin網路驗證 |
| G10 單項可寫性 | `21c1de9f`，[PC/iOS逐欄false veto與原因](media-item-writable-ui-2026-10-16.zh-TW.md)；PC JS真執行及Firefox有限矩陣，unknown保留原語意 | iOS native未跑；前一輪loading完成未觀察，不能宣稱mutation-inflight |
| G08 Media重連owner | `42e20f8f`，[舊DOM/handler/observer隔離與gallery-first](media-reconnect-owner-2026-10-16.zh-TW.md)，805 host案；同head Firefox A/B同ID新清單與info loading恢復，6圖持久保存 | queued race為host證據，未全部在人為UI觸發；非真兩相機、非mobile native |
| G03 設定UI owner | `6362b87e`，[advanced/exposure/filename舊callback與late-close隔離](settings-ui-owner-2026-10-16.zh-TW.md)，813 host案；同head Firefox typing/Refresh/原生Escape/合法Apply/斷重連清草稿，6圖持久保存 | 未強制所有browser queued race，未量測caret連續性；mobile native與真機未跑 |

以上測試是不同來源／不同範圍，不將805＋813相加成不重複全案覆蓋率。gphoto progressive 1101筆與最初連線/LiveView/倍率Firefox已完成，不再把重跑這些綠流程列成下一步。

### Android：目前不是「EOS需要4GiB依賴」

4GiB是此共享overlay工作空間採用的保守保留底線，不是Gradle/kernel硬限制，也不是EOS本身安裝需求；監測是提前停止而非hard quota。約365MB是為隔離活躍共用第三方cache而複製的公開dependency snapshot，並非EOS已證缺額。不能把整個Serein工具cache算EOS需求，不能直接RO借用仍被其他工作寫入的cache。

- 03:44:40–03:44:59，舊候選`6b1df91a`的overlay離線解析被監測提前stop，exit -9，tests/build NOT_RUN。
- 09:54:37–09:55:16，候選`c0af91f5`在已協調靜止窗口、來源hash一致的tmpfs snapshot／獨立寫cache下完成offline `debugUnitTestRuntimeClasspath`圖解析（exit0）。snapshot365,449,216 bytes；tmp配置峰844,681,216 bytes；最小MemAvailable3,828,719,616 bytes；overlay最小free4,395,286,528 bytes。所有owned退出並清可重建tmp。
- **圖仍不完整**：8個failed coordinates為Media3 exoplayer/extractor/ui1.8.1、mockwebserver4.12.0、coil-compose2.7.0、coroutines-test1.9.0、serialization-core-jvm1.7.3、org.json20240303。exit0不代表依賴已解齊、artifact已存在或compile/unit已過。完整variant/BOM/transitive closure仍未知。
- 後續官方metadata/HEAD查核09:58留下部分結果、09:59兩次自動審查取消後停止。11:33使用者明確續行後的最小probe，官方media3-ui module回 `Tunnel connection failed: 403 Forbidden`、POM回 `Connection refused`，未取得新metadata（0 bytes）；沒有換CDN／關TLS／重刷下載。artifact補件、compile、unit仍NOT_RUN。
- 後續清理已讓free高於先前門檻（13:10讀回約5.69GB）；因此舊「容量不足」不是目前唯一或已證根因。真正下一步是讓既定官方metadata正常可達並完成最小閉包預算；成功才補件、精準compile/unit。現在不重試已停probe，不宣稱缺額固定為365MB或2–4GiB。

### 其餘平台與能力界線

1. iOS Xcode/GPU/UI、Android instrumentation仍需適當平台與具體驗收權限；原六KVM固定待批，不改SHA。這不是全案禁止開發的理由。
2. Windows/WSL實際打包與相機、外部儲存provider驗收需相應OS/裝置。現有雲端Firefox可驗特定PC合成旅程，但不替代這些平台。
3. iOS directUSB、真Canon EDSDK binding、gphoto座標AF/ClickWB及metadata mutation仍D/E未支持或Research；缺可證API／真機能力資料，不能把缺實作假稱只待驗收，也不能憑空新增命令。
4. 目前沒有另一個已確認、可直接實作的原caller缺口。若從原需求實際發現反例，再從共同dev建立短期分支收斂；不為維持活動重刷測試、添加相似合成輪次或重試被擋下載。這不等於原89列全部完成。
5. main／版本／可下載發布包仍未完成必要整體驗收，dev source交付不能代稱可下載App或release ready。

## 2026-10-17 再盤點的產品反例

先前「沒有另一個已確認 caller 缺口」只代表當時觀察，現由兩個真反例更新：[G07 local preview／G09 download lifecycle](local-preview-transfer-lifecycle-2026-10-17.zh-TW.md)。預覽不再由裝置列舉鎖住 Stop，舊列舉不能覆蓋新狀態；目的地處理失敗會取消來源 reader。已本地實作與 host 回歸；初次缺 pwsh 的掃描阻擋已依既有授權恢復官方工具，交付沿正常掃描及非 force dev 流程。本批不是原生／瀏覽器／真機驗收，不改 D/E 能力界線。

## 2026-10-17 iOS G03/G06/G08 續查反例

基底 `20251fcd` 的既有 iOS caller 續查找到兩個可實作缺口：[event admission／maintenance RTP watcher](ios-lifecycle-continuation-2026-10-17.zh-TW.md)。原列 #41/#73 的 content event 在 Live View retirement 擋住 admission 時同步空轉；#55/#81 的 sensor-cleaning Restore 遺漏重新註冊 RTP render watcher。兩者均已有窄修正與已撰寫的原生 regression；本地只執行 source／contract guards，Swift/Xcode/XCTest/GPU 仍 NOT_RUN。新文件列出 G01–G10 查核及各群 residual blockers，不把原89列改寫為全通過，也不把未實作 transport 改稱僅待測試。

## 2026-10-17 Android G08/G09/G10 原畫面 owner

[媒體 callback 原連線 gate](android-media-callback-owner-2026-10-17.zh-TW.md) 修正原列 #73/#75/#76/#77/#78 相關 gallery、preview、transfer、upload/cancel caller：舊畫面不再於重連後替新相機取得操作 owner，已儲存 Gallery JPEG 的獨立交付不受影響。既有離線 Kotlin 工具直接編譯 production 純 gate／metadata helper，以及 byte-exact callback binding＋shape adapters，22/22；完整 Compose/Gradle/native/真機仍 NOT_RUN。與 iOS lifecycle 兩項修正整合後，134 source guards 通過；source guards 不代表原生 runtime 通過。

## 2026-10-17 G07 browser 延遲列舉增量

[原列 #123 local preview Firefox 操作](local-preview-firefox-2026-10-17.zh-TW.md)：source `4efb9a33`，合成 canvas stream 已播放、enumerateDevices unresolved 時 Stop 可用；停止後晚回覆不覆寫 stopped。三張截圖與狀態 receipt 已保存。這是 cloud Firefox production UI 的窄旅程，不是實體 UVC／HDMI 或三端完整驗收。

## 2026-10-17 Android G08/G11 USB 清單完成

[原列 #73/#90 USB 媒體列舉](android-usb-listing-completion-2026-10-17.zh-TW.md)：修復 directory handle 吃掉 Recent 的媒體預算，以及部分必要 ObjectInfo 失敗卻將剩餘清單或 host item 宣告成功。基準 byte-exact production declaration 的6案有3個預期失敗；修正後13/13 host JUnit通過，包含取消優先、host預填、全卡、optional rating未知。4 source guards／2 Kotlin PSI語法通過；6完整backend protocol新增測試尚未執行，完整Android compile/native/真USB仍NOT_RUN。已審查窄production差異，不更改D/E能力或宣稱原89列完成。

## 2026-10-17 G12/G13 shutdown 與初始化恢復

- [原列 #116 Bridge shutdown](bridge-shutdown-drain-2026-10-17.zh-TW.md)：普通 OS/provider/子程序 timeout 不再跳過後續 session close，最後保留第一個原始錯誤；BaseException 中斷不改。基準7 fail/4 pass，修正與相鄰host測試共39 pass；包含production SubprocessByteStream、後續CcapiSession合成transport與ASGI lifespan，非物理相機release。
- [原列 #122 health recovery](pc-bridge-health-recovery-2026-10-17.zh-TW.md)：health fetch/body卡住時15秒deadline退回可明確重試；舊回覆不能覆寫新attempt。104個connection guidance host cases通過，包括7新反例與97既有case；全域API不改。Windows standalone、真正EDSDK、mobile native、OS picker與真機驗收界線保留。

## 2026-10-17 G13 初始化可選視訊列舉

[原列 #122/#123 初始化 readiness](pc-initialization-readiness-2026-10-17.zh-TW.md)：可選 enumerateDevices 不再擋住必要 Bridge health／既有 initial scan。仍先等 health 並遵守能力／token gate，保留 local-video 原有晚回覆 owner 與錯誤處理。新production initialize harness在基準7 fail/1 pass，修正後含既有preview案例16/16；這不把前一筆3626完整模組結果追溯成此新source全量通過。Browser合成恢復旅程另外記錄，native／真輸入未驗。

## 2026-10-17 G13 iOS 選定語言與錯誤恢復

[原列 #128 選定語言恢復文案](ios-recovery-language-2026-10-17.zh-TW.md)：修正 app 選 English／繁中但 state app 自有錯誤與 REC evidence 仍走系統 bundle 的 source mismatch。五個 App entrypoints 共用既有 live language store；新發出的 app 訊息與 REC 模板／嵌套值依所選語言，原始 device/server 診斷、既有錯誤 owner、connection/history 不改。143 source guards、4 source-path checks 及兩個基準 static negative controls通過；五個已撰寫 XCTest 尚未編譯／執行。#36/#127 的 connection 初始化／取消／重試本輪未確認新缺口；混合語言原生 UI、VoiceOver／大字／鍵盤／OS picker 與真機仍未驗，不把 G13 或原89列改成全部完成。

同日續查原列 #128／#67 的既有 iOS accessibility caller：AF-ON label/value/hint、倍率按鈕與 camera-reported focus 狀態改用同一 selected-language store；touch/cancel/release、coordinator 與幾何不變。後續 source guards147/147、source paths4/4、兩個前一提交 static negative controls通過；兩個新增 hosted UIKit/resource XCTest 均 NOT_RUN。細節與原生 scene／VoiceOver 邊界見同文件後續節。

## 2026-10-17 G13 browser 恢復旅程

[PC初始化／health Firefox證據](health-recovery-firefox-2026-10-17.zh-TW.md)：固定source `90aa16db`。可選裝置列舉永不完成仍開始health；15秒timeout後實際Check again成功；舊health晚回不覆蓋新結果。三圖、fixture及telemetry封存；不代替真網路、相機、mobile native或Windows成品驗收。

## 2026-10-17 Android G03/G10/G13 app 錯誤語言

- [退役警告與上傳驗證](android-app-error-language-2026-10-17.zh-TW.md)：原列 #55/#78 與 G13 英繁中恢復細節修復 disconnect 既有缺失 import／越域 appContext 編譯缺陷；app 自有維護與上傳錯誤改用精確型別 token，於畫面依選定語言解析。原始 provider／相機診斷保留。production helper／抽出 declaration host 10/10、實際上傳驗證 6/6、source 14/14、語法 6/6；原生 Compose／完整編譯仍 NOT_RUN。
- [文件與資料夾儲存失敗](android-download-error-language-2026-10-17.zh-TW.md)：原列 #76 的 null output stream 錯誤在總覽與每檔失敗提示均遵守選定語言；cleanup、收據、owner 與明確重試不改。基準 4 fail／6 pass，修正 host 10/10、source 7/7、語法 4/4；新增 instrumentation 尚未執行，實際 SAF/provider 仍未驗。

## 2026-10-17 官方公開依賴隔離快取核對

在已授權且停止 writer 的共用工具快取／公開材料目錄，精確核對原八項座標，找到並以官方 Maven Central 來源收據／hash 核實 mockwebserver 4.12.0 的 jar、pom、module，已複製到 EOS 隔離快取，未重新下載。其餘七項 Media3 exoplayer／extractor／ui 1.8.1、coil-compose 2.7.0、kotlinx-coroutines-test 1.9.0、kotlinx-serialization-core-jvm 1.7.3、org.json 20240303 在該限定範圍仍缺；不能據此執行或宣稱完整 Android 依賴閉合。工具二進位與其他專案資料未上傳。這是 blocker 的縮小，非產品功能完成或原生驗收。

## 2026-10-17 G09/G13 資料夾與圖庫失敗續查

[原列 #76 的相鄰儲存 caller](android-folder-gallery-error-language-2026-10-17.zh-TW.md)：補齊 folder create-null、Gallery insert/open/byte-count/length/publication 五類原因，以及自動 JPEG 未發布 fallback 的選定語言。保留 exact item 檔名、原始 provider 診斷、cleanup-unconfirmed 與 receipt 優先順序。整個 production Gallery 類別搭配明示的合成 Android adapters，加上原樣 SAF／auto caller，基準 9 fail／9 pass，修正 18/18；source 10/10、PSI 5/5。原生/provider 未驗，沒有改檔案交付成功條件。此有界儲存路徑續查未再確認可見 App 英文缺口，不代表全平台語言驗收完成。

## 2026-10-17 G06/G13 JPEG decode 診斷與語言

[原列 #57/#58 的 Android null-decode 錯誤](android-liveview-decode-language-2026-10-17.zh-TW.md)：以單一 canonical typed payload 保留 byte count 與 nullable content type，並於畫面依目前語言格式化；診斷內容含 SocketTimeoutException 也不會誤取代成 timeout 原因。原始 provider/cause、owner/attempt、取消、retirement/Stop/顯式重啟不變。基準 4 fail／6 controls pass；實際 helper 與抽出 production caller／banner 加合成 adapters 共16/16。新增4 source guards、相鄰7 guards與6 syntax checks通過；3 instrumentation案例NOT_RUN。整合後 decode/format/retire/banner 與 Gallery/error helper 對照受驗來源逐字一致，兩批合計14個 source guards通過。沒有因此聲稱 native decoder、實體Stop或原89列完成。


## 2026-10-17 G05/G07/G13 PC 鍵盤操作歸屬

[既有 viewfinder 內 zoom/audio 鍵盤路由](pc-viewfinder-keyboard-routing-2026-10-17.zh-TW.md)：修正 Enter／Space 從子控制冒泡時誤送中心 focus/WB。僅 viewfinder 本身為事件 target 才進入其鍵盤動作；原 pointer/owner/能力 gate 不變。基準18案9fail，修正及相鄰focus/magnification共64/64。Firefox實際 zoom Enter／Space各只送magnification，viewfinder Enter仍送focus，四圖與telemetry保存。合成API/JPEG，非實體相機、RTP音訊、screen reader或Windows交付。

## 2026-10-17 G13 PC 對話框名稱與設定焦點

- [八 dialog 有界命名／鍵盤檢查](pc-dialog-name-audit-2026-10-17.zh-TW.md)：唯一未命名的 setting dialog 關聯既有動態 heading；HTML基準1fail/7pass，修正static15/15、setting caller47/47。標題在showModal前依目前setting／語言填入；實際screen reader仍未驗。
- [設定Apply後焦點恢復](pc-setting-focus-return-2026-10-17.zh-TW.md)：Firefox基準source90174ba0真操作確認Apply後原按鈕已detach，dialog關閉時activeElement為BODY。修正只在同session/editor且沒有新焦點目的地時，優先原有效按鈕或同設定的目前可用按鈕；不從queuedclose／late response搶焦點。新增有界host反例與owner/visibility/cancel控制後69/69，另124相鄰設定測試通過；pendingApply→Escape及media dialog候選未據此宣稱修好。
- 整合source `0b8dfaf4` 的PC全modules一次完整執行：52組Node runner summaries共3675 tests/pass，fail/cancel/skip均0，另既有direct assert scripts通過；15 static UI tests通過。這是整合回歸，不代替native、Windows包或真相機驗收。

同source Firefox fixed對照已確認Apply後回到目前ISO200按鈕，直接Enter能重開；baseline/fixed各3圖、telemetry與hash已保存。pendingApply Escape與兩個media候選仍獨立待驗，沒有整群升級為PASS。

## 2026-10-17 19:18 UTC 媒體鍵盤與遲來回覆增量

- G08/G09/G10/G13，原#56/#73/#75/#76/#122：[媒體dialog焦點](pc-media-dialog-focus-2026-10-17.zh-TW.md)。真Firefox基準Details metadata、Preview Download重繪後關窗焦點掉BODY；第一輪修正實測仍失敗而未交付，補native close保留內部焦點時序後，最終`abbcde3b`五畫面證實Escape回同ID同角色卡片且Enter重開，下載503仍留Close。
- G09/G10，原#76/#78/#79：[新媒體操作隔離舊upload completion](pc-single-download-owner-2026-10-17.zh-TW.md)。單檔下載、metadata mutation、delete、foreground import成功admission才退休舊publication；保留原receipt，拒絕admission不改原語意。可選upload completion refresh保留使用者已開preview，不新增delete全域鎖。
- 最終產品source `abbcde3bd247db8ee277fafe8f34edc2564168b6`：完整PC modules3729/3729（53組node:test報告＋其他direct scripts）、staticUI15/15；54新增回歸。Firefox19:18:45 terminal。其餘native／Windows／真相機／外部儲存／全部89列仍依原界線，沒有新APK、iOS包或Windows installer，沒有三端全通過或release宣稱。

## 19:49 UTC Bulb 停止責任的增量

- G04／#65：libgphoto2 已確證 Press Full lost ACK＋cleanup失敗後Stop不重送release，改為保存原immutable release、unknown停止責任、明確Stop／close重試；新增27案基準24 fail／3 pass→27 pass。詳見 [gphoto停止責任](gphoto-bulb-stop-ownership-2026-10-17.zh-TW.md)。
- G04／#65、G13／#122：PC malformed502未知結果保留stop-only；只讀恢復15秒界限不再永久卡busy。新增9案，真Firefox前三個有界旅程通過，第四張是pending Start仍無可按Stop的失敗基準，詳見 [PC回應恢復](pc-bulb-response-recovery-2026-10-17.zh-TW.md)。
- Pending Start的HTTP順序仍需owner cancellation契約；另已發現gphoto半按／獨立AF cleanup缺少saved retry責任，尚未由本批修正。這些是具體仍可開發的原功能缺口，不能寫成只待native驗收。
- G13完整四區metadata在Firefox200%／CSS594×381實看：dialog bottom361、Download/Delete bottom354，末端按鈕完整；未確認裁切，未為候選修改CSS。證據保留本地，不重複計為功能交付。
- 仍無mobile native、真相機或新installer證據；原89列平台缺口不因host增量改為全過。

## 2026-10-17 owned Bulb 與 G03 Escape 整合增量

- G04/#65、G05/#66/#69、G13/#122：[owned Bulb整合](pc-owned-bulb-integrated-2026-10-17.zh-TW.md) 修復pending Start無Stop、HTTP Stop超車late Start、短控制release責任，以及整合時發現的跨caller新AF被舊owner Stop誤停。cached receipt不得抹掉較新AF未知警告。PC/Bridge與gphoto為本批範圍；mobile Bridge caller尚在獨立核對，不能升級成三端完成。
- G03/#42/#43、G13/#122：[Escape同步退休](pc-setting-escape-retirement-2026-10-17.zh-TW.md) 補queued native close之前的late receipt／舊Apply callback空窗；取消保留原write receipt，不消耗draft、不自動重送。Firefox baseline已重現matched stream在Escape後更新ISO200/Ready，與之前普通成功Apply不同路徑。
- 產品source1128b4b2整合PC modules3791/3791、Python608＋77通過；後續僅短控制test格式整理，34案再過、相關Python Ruff全過。真正PC bounded Firefox、host合成provider/transport與native/真機證據仍分開記錄。
- Android/iOS完整原生驗證、Windows成品、實體相機/外部儲存與原D/E列限制仍存在；沒有把原89列改成全過。

20:21 UTC Firefox補驗：cached A receipt保留新AF B警告、僅明確Recovery解除，三圖通過；G03晚receipt資料退休通過，但pendingEscape仍回BODY、Enter不能重開，該鍵盤殘缺明列下一段，未被successfulApply或host綠燈掩蓋。

## 20:32 UTC pending 設定取消鍵盤補齊

G03/#42/#43、G13/#122：[pending Apply 焦點](pc-setting-pending-focus-2026-10-17.zh-TW.md) 補上前段實看失敗項。只在原 record settlement、精確 owner 未變且焦點仍 BODY 時，回到重繪後目前同 key 按鈕；不搶新焦點，不把取消等待說成相機取消。3799 modules、15 static UI；真 Firefox pending Escape→late matched stream→Enter 重開草稿三圖通過。Android Bridge owned Bulb caller 正獨立修正，原生與全89列未完成。

## 20:53 UTC metadata 鍵盤與 Android owned Bulb 增量

- G10/#56、G13/#122：[PC metadata pending 關窗](pc-media-pending-mutation-focus-2026-10-17.zh-TW.md) 已以真正 Firefox 重現 BODY／Enter 失效並驗證修正。只在同 owner／session／generation 的晚收據 settlement 回目前同 ID/manage，保留新焦點、新dialog與收據語意。3812 modules、15 static UI；合成相機 transport，非真機。
- G04/#65、G05/#66/#69、G13/#122：[Android Bridge owned Bulb](../android-bridge-owned-bulb.md) 補同步 owner admission、pending Stop、coalescing、cached receipt／較新狀態保護，以及15秒 caller deadline、retirement與 bounded ordinary DELETE。59項 production-host runtime、40項 source/resource checks；[host receipt](evidence/android-bridge-owned-bulb-2026-10-17/receipt.json)。完整 Android／Compose／實際 OkHttp／MockWebServer與裝置未驗；舊 Bridge 404/405 提示升級，沒有危險 legacy Start fallback。
- 原 G11/G13 maintenance-owned retirement 的舊 blocking transport 尚待下一段反例與修正，普通 owned Stop＋DELETE 的 deadline 不擴張宣稱涵蓋它。iOS caller 尚未同等修正，全部89列未完成。
- 整合時 bridge tree 與已驗 PC source、android及host fixture tree 與已驗 Android source逐一比對相同；未以重跑未變綠燈充當新進度。

## 21:05 UTC Android maintenance cleanup deadline

G11/G13：[三條 strict cleanup](../android-maintenance-cleanup-deadline.md) 的 caller 已改用有界 callback 等待，保留精確 saved session、strict Stop／events DELETE／session DELETE 契約及原 error priority。基準4/4確實紅，包含未主動cancel、真15秒已觸發而body仍卡住；固定16/16、前批owned Bulb59/59、40 source/resource checks通過。真15秒證據不等於transport producer實際停止。

仍未宣稱整個maintenance Disconnect有界：ViewModel先join一般maintenance dispatch/read/restore的caller鏈，以及physical AF cleanup另有邊界；下一段先驗證精確入口反例。完整Android／Compose／實際OkHttp／裝置與iOS同等修正仍未完成。另校正前段metadata #56分類為原G10。

21:08 UTC G10/#78＋G08/#75：[上傳晚收據／預覽接續](pc-upload-preview-continuation-2026-10-17.zh-TW.md) 真Firefox與原生picker補驗：cancel unknown→明確Preview→late201 confirmed＋空listing不關預覽，僅一個write。合成network邊界清楚分列，非新增功能或真機上傳證據。

## 21:25 UTC G07/G13 LUT 與 G11/G13 maintenance dispatch

- G07/#62、G13/#122：[LUT 鍵盤接續](pc-monitor-lut-keyboard-2026-10-17.zh-TW.md) 真 Firefox baseline 重現 Remove 隱藏後 BODY 焦點；固定版保留 Load 焦點，下一次 Enter 原生 picker 實際開啟。完整 modules 3823、static 15、focused 58 通過；取消與 settlement 分支僅 host 覆蓋，未宣稱 GPU 色彩或三端驗收。
- G11/G13：[maintenance dispatch deadline](../android-maintenance-dispatch-deadline.md) 精確 submitMaintenance→operation job→dispatch→Disconnect join 基準兩案真紅，包括真 15 秒 body 仍阻塞。固定 62＋5＋前 cleanup 16＋前 Bulb 59＝142 host runtime、40 source checks 通過。保留原 join、receipt/history、較新 recovery 與 replacement session；晚 callback 只更新捕捉的原 evidence。
- bridge 與已驗 PC source、android 與已驗 Android source 整合後 tree 相同，沒有以未變更回歸重跑充數。一般 maintenance read／preview restore、其他 operation join、physical AF 與 iOS caller 仍需獨立精確審核；完整 Android／Compose／實際 OkHttp socket／裝置未驗。原 89 列的大目標仍未完成。

## 21:38 UTC local preview timestamps／maintenance reads

- G07/#123、G02：[本地預覽最後影格時間](pc-local-frame-timestamp-2026-10-17.zh-TW.md) 的 timer fallback 曾把無新 frame counter／0／unknown 的輪詢當新影格。Production handler 基準2 pass／5 fail，固定3835 modules與15 static通過；只改可觀察影格timestamp，保留render cadence。沒有UVC／HDMI或GPU實機證據。
- G11/G13：[Android三條精確maintenance GET](../android-maintenance-read-deadline.md) 已將初始read、recovery與restore前fresh read改為有界caller；四個已修真紅與第五個仍未修restore Start真紅明確區分。Read67 distinct，dispatch67／cleanup16／Bulb59回歸與40 source checks另列。普通transport／join／strict Start未變。
- 新確認的server維護restore排序反例：Stop可超越已admitted但尚未進provider的Start；local-only DELETE204不代表物理Stop。正在另批處理owned restore permit與caller，未先將Start改有界。iOS未同等遷移，原生三端驗收與原89列大目標仍未完成。

## 22:01 UTC 維護恢復的一次性許可與停止責任

- G11/G13：[Bridge restore ownership](../maintenance-restore-ownership.md) 修正已 admitted 的 Start 被 Stop 超越、普通 Stop 後晚 owned Start 再啟動，以及 explicit null token 降成全域 cleanup 的真 API 反例。Session-bound 一次性 permit 凍結原來源，取消先留下不可重送紀錄，Stop 共用 execution lane；cached receipt 不等於目前相機狀態。293 項 production route/manager/provider host 測試（含43所有權/排序/schema）通過。相機 HTTP 為合成，local DELETE 仍不代表物理 Stop。
- [PC caller](pc-maintenance-restore-permit-2026-10-17.zh-TW.md) 綁同一 permit、明確 token/outcome ACK、較新 warning 與後續 fresh Stop；第二次 cleaning 不繼承舊 token。85 專項、3860 modules、15 static UI；真正雲端 Firefox 已跑正常恢復及第二次取消→明確 Stop→晚 Start 拒絕，三畫面與完整事件保存。沒有真相機／USB接受宣稱。
- Android 與 iOS 同契約 caller 分別整合驗證；手機完整原生及實機仍是獨立未過 gates。不能因本批一次性許可修正就將原89列標為完成。維護側欄實看另發現文字被按鈕擠壓，後續最小版面修正與可讀性補驗另列。

22:06 UTC 同批 caller 與版面補齊：
- [Android owned restore](../android-maintenance-restore-token.md)：原請求 token 與 strict ACK、whole-callback deadline、較新狀態發布前／中／後的明確 Stop 重試。新組68（24新控＋44既有workflow），read67／dispatch67／cleanup16／Bulb59，合計277 executions而非277個新案；42 source/resource checks。真15秒Start期限15022ms，實際registered job→Disconnect可收尾。完整Android／Compose／OkHttp／裝置未驗。
- [iOS caller source migration](ios-maintenance-restore-token-20261017.md)：同一attempt的一次性permit、strict ACK、terminal receipt不清較新warning、下一明確globalStop，以及完成後新warning仍用maintenance strict transport。Missing optional permit保留已確認Stop與清潔，僅Restore封鎖。82 Python source/wiring檢查通過，Swift/Core及App simulator未執行；不能把源碼檢查等同原生通過。
- [維護側欄與Cancel翻譯](pc-maintenance-layout-2026-10-17.zh-TW.md)：3862 modules；Firefox一般側欄與200%大字可讀性、pending取消標籤已實看。
- 三端仍未完成：iOS Bridge Bulb還使用legacy無owner Start/Stop，等待Start後才Stop且cached收據／較新警告界線尚需遷移，已確認為下一個可獨立開發缺口；既有原生／真相機／官方依賴限制保留。


## 22:20 UTC iOS owned Bulb 與 Android 配置恢復

- G04/#65、G05/#66/#69、G13/#122：[iOS owned Bulb caller](../ios-bridge-owned-bulb.md) 已遷移同一不可變 owner admission、獨立且合併重複 Stop、嚴格 receipt 與較新觀察保護。取消前置 Start 不再阻擋 owned Stop；背景只請求原 owner Stop，沒有自動恢復。Receipt-only 退休不偽造 active=false，原 session DELETE 不當成物理釋放。每個新 owned/maintenance exchange 有15秒期限，但不擴大宣稱所有舊 focus／Live View close 等待已解決。88 supplementary source checks、獨立靜態審查及正常安全 hook；Swift/Core/App 原生測試已編寫但未執行，無原生通過宣稱。
- G03/G13：[Android capture-configuration recovery](android-capture-recovery-bound-20261017.md) 修正 read-only GET 的 response/body 卡住 registered DIRECTORY job、阻止 Disconnect join 的缺口。原版3項真正 runtime 紅，固定13項通過；四組既有維護回歸16+67+67+68，合計231 executions，非231新案。[精確收據與輸出](evidence/android-capture-recovery-bound-20261017/README.md) 包含真15秒期限、Cancel、同名換session與late callback。保留 mutation／physical release join；僅明確 read-only recovery 使用有界 callback。
- 整合 iOS／Android 來源與各自驗證 worktree 逐檔相同。沒有重跑未改綠燈充作新進度。完整 Android／Compose／真 OkHttp、Swift／Xcode、實體相機及原89列的平台驗收仍未完成。
- 下一個已確認原功能缺口：Android Direct CCAPI 一般 still capture 的取消 watcher／whole-call timeout／bounded body 缺失；mandatory manual release 仍須保留原順序。另精確核對 iOS focus 退休等待，不把 owned Bulb 修正擴張成所有 cleanup 完成。


## 22:30 UTC still capture 與 held-AF 退休

- G04/G13：[Android Direct CCAPI still capture](android-still-transport-bound-2026-10-17.zh-TW.md) 只將真相機 direct/manual 三個既有呼叫（含原 release closure）接到既有 single-attempt、有全程期限的 client，加取消 watcher 與64KiB body上限。保留 synchronous producer join 與 NonCancellable mandatory release；沒有把 mutation 改成脫離 caller 的 callback。精確抽取production基準3案真紅→固定3案綠；4項 MockWebServer socket tests 已寫但未執行，15秒 production期限未實測。Simulator unchanged，完整 Android／真相機仍未驗。
- G04/G13：[iOS Bridge held-AF](ios-held-af-retirement-2026-10-17.zh-TW.md) 原 App/Core雙重等待 cancellation-ignoring Start，連 Stop與Disconnect都無法送出。本批獨立且合併原owner Stop、精確task identity、bounded原session retirement；匹配Stop ACK後仍保留pending Start reservation，防止live session循環新owner跨過server有限取消紀錄。409／timeout保留未知責任，DELETE不是物理釋放。
- iOS App新增同generation／observer ID／release observation保護的晚settlement observer，只在原Start實際結束且Core明確idle後清同一舊警告；不清較新warning、不自動恢復。獨立審查抓到並修正重複native test宣告及App殘留警告。31 supplementary source checks；Core/App native fixtures仍未編譯／執行，不作原生驗收宣稱。Direct CCAPI流程保留。
- 上述仍是原功能恢復與三端對齊增量，不是原89列全過。另在核對Android held-AF mutex／registered job／Disconnect的精確同類等待，不能將本批still capture修正擴張成所有focus cleanup已完成。

22:31 UTC Android held-AF精確複核：Start與NonCancellable Stop原本皆經recoveryRead=true，設有min(existing,5秒)完整Call timeout，涵蓋response與body；三層join/mutex是現有安全順序。尚無足以支持ownership重寫的實際production反例，未因iOS差異就強行改Android。既有native取消/Stop測試仍未在本環境執行，保留平台驗證缺口。


## 22:53 UTC G08/G10 已確認刪除與晚清單

- [PC listing receipt fence](pc-delete-listing-receipt-2026-10-17.md)：Refresh 在 DELETE pending 期間取得的舊清單，曾在 204 後還原已刪項目。精確 production 基準 3 案紅，固定 3867 node:test 與15 static 通過；只在同 session 的當前 listing owner 記錄已確認 ID，保留 raw overflow 與其他項目。
- 真 Firefox 基準最終 DOM 收據重現還原，固定版畫面／收據只留 KEEP；後續新 Refresh 同 ID 正常返回。基準最後畫面因 owned-process deadline 未捕捉，未拿較早 screenshot 冒充最終畫面。
- Android／iOS 來源核對已有 operation/listing admission 與退休規則排除此重疊，沒有無證據重寫；兩端原生、真卡片／相機仍未驗。此為既有媒體功能修正，原89列平台驗收未完成。


## 23:03 UTC G10/#78 上傳退休 admission

- [Bridge upload retirement](bridge-upload-retirement-2026-10-17.md)：真 ASGI endpoint／SessionManager／GPhoto2Session 加合成 FakeRunner 重現 body staging 尚未完成時 Disconnect 已退休 registry、仍在 drain 先前 control，body 接續後卻 dispatch 一次 upload 並回201。並非已完成close後寫入新session；原provider closed檢查原本已能拒絕該控制案。
- 最小修正讓同步 upload worker 於正式dispatch前進入原 camera_mutation guard，持有至provider終態；guard內核對原物件與取消。排隊後退休、重用文字ID、HTTP取消、已commit upload的readback/drain/staging壽命皆保留。原碼真紅；217 distinct pytest通過（含8新案），額外8案重跑不重複加計，Ruff與正常secret hook通過。
- 整合app/test tree與受驗085be203相同並經獨立窄審；沒有新upload能力，實際相機／USB／三端原生仍未驗。


## 23:08 UTC G06/G13 Android Direct Live View 控制

- [Live View control transport](android-liveview-transport-bound-2026-10-17.zh-TW.md)：JPEG／multipart／RTP 原有Start/Stop（含400降級與closeStream）明確選用最長15秒single-attempt client、取消watcher及64KiB完整body讀取。保留同步producer join、NonCancellable Stop、unknown Stop阻擋新Start/fallback與RTP finally close。純文字400仍可執行原尺寸降級。
- 精確production入口萃取同一fixture基線12案在指定assertion真紅，固定12/12；共用requestOk既有still回歸3/3另列。multipart stream只做wiring source guard；8個MockWebServer案已編寫未編譯/執行，真socket15秒未證。PSI語法不是Android type-check。整合Android與受驗89ae6aad逐檔相同，獨立窄審無新增blocker。前置temperature/capability read、RTP SDP與整條Connect/Disconnect總期限不在此claim。
- G07歷史native失敗已對照較晚診斷：Android fixture VUI的兩個SPS metadata bytes早已修正（17da77a1），swap誤差0、nonlinear max0.416支持fixture診斷；修正後API34/36仍NOT_RUN。iOS extended-sRGB與插值後clamp早已實作，Metal/H264原容差保留，仍需native。沒有新production數學反例就不重跑host綠燈當進度。
- iOS普通Bridge Live View仍用URLSession每request120秒resource上限，不能將自訂忽略取消transport無界等待當成真URLSession已證缺陷；無owned token的普通Start/Stop維持physical排序。下一最小驗證為真URLSession loopback取消/滴流，現無Swift/Xcode。上述兩個新產品缺口已修，原89列的native/真相機/OS目的地與unsupported項仍未完成。


## 23:18 UTC G04/G05/G06 原控制 producer 與釋放責任

- [Android Direct AF／Bulb](android-direct-release-transport-bound-2026-10-17.zh-TW.md)：10個原有held AF、pulse AF／manual fallback、half-press與Bulb press/release呼叫使用bounded single-attempt transport。不是先前已具5秒界線的Android Bridge AF；兩條transport分開。基準12指定真紅＋2相容性綠，固定14/14；LV12與still3既有回歸另列。兩個release owner class逐byte未變，原mutex、NonCancellable cleanup、原operation closure與Simulator保留。9個socket案已寫未跑，PSI僅語法，15秒真socket未驗。
- [Bridge cooperative control body](bridge-control-body-budget-2026-10-17.zh-TW.md)：真urllib loopback證明socket timeout不能阻止持續滴流累積body。只將8個method/suffix加入既有合作式read1 budget；Start producer完結後才補償Stop，晚Stop保留原owner／未知責任。相同34案基準14紅／20綠→固定34綠，445 distinct窄回歸已含34案；重跑不重複計數。快速錯誤診斷、已收到拒絕status、configuration與redirect規則不變。
- 整合Android tree與受驗38f96f52、Bridge tree與受驗63c199b5逐檔相同並經獨立窄審。Bridge只證body讀取合作式期限；DNS/open/header、單次blocking read、其他GET/recording/drivefocus及整條Disconnect不在完成claim。原89列平台／真相機／OS驗收仍未完成。


## 23:33 UTC G04/G05/G06 partial control ACK 與原 Stop 責任

- [Bridge／Android partial ACK](control-partial-ack-2026-10-17.zh-TW.md)：原 AF／Bulb Stop 503 後，206 或 200+Content-Range（含空值）曾清掉原停止責任，讓新 Start 通過。只對原有 physical-control routes／flags 拒絕部分成功收據；原 owner、join、mutex、deadline、非2xx錯誤與media range保留。完整空body／純文字／JSON及既有成功status相容性不變。
- Bridge同一86案基準30紅／56綠→固定86綠；466 distinct回歸已包含新案。Android精確production入口host同一13案基準9紅／4綠→固定13綠，既有release14、LV12、still3回歸另列。Android四個新MockWebServer測試已寫未跑，host不等於真OkHttp或完整type-check。
- [iOS同一收據gate](ios-partial-control-receipt-20261017.md) 以相同8組method／suffix保護原AF、Bulb、JPEG／RTP Stop。保留原捕捉的method／path／payload與未知責任；完整原Stop成功才釋放。獨立來源複審無blocker；96 supplementary source checks已含5新項，12個native XCTest methods已寫但編譯／執行NOT_RUN，不宣稱iOS runtime紅綠。
- 三端本批仍是原功能恢復契約增量；原89列的原生、實體相機、OS目的地、安裝／分發與unsupported provider缺口未因host或來源檢查變為通過。


## 23:47 UTC G05 drive focus／tap AF／click WB 成功提示

- [Bridge／Android focus ACK](focus-ack-receipts-2026-10-17.zh-TW.md)：三個原有method/path收到206或Content-Range曾回報accepted／新增observed feature，實際PC函式因而顯示成功提示。相同API fixture的9個不完整回覆在修正後拒絕，原PC錯誤流程不再畫accepted／success reticle；9個完整成功與3個503控制保留，單次mutation不重送。未修改PC來源。
- Bridge新38案基準9紅／29綠→固定38綠；相關353回歸包含新案，PC113回歸另列。Android精確public-entry host同13案基準9紅→固定13綠，既有13／14／12／3回歸另列。21個parameterized MockWebServer案例已寫未跑；host假transport／geometry與PC函式抽取不等於native／Firefox／實體光學驗收。
- [iOS focus receipt](ios-focus-control-receipt-20261017.md) 同樣分離三組收據判定；100 supplementary source checks已含4新項，原生XCTest已寫但編譯與執行NOT_RUN。三端均保留原座標／snapshot owner、完整ACK相容性、非2xx分類及原body deadline／retry政策；沒有把focus路徑加入八條transport期限路由。
- 整合Android／Bridge tree與受驗efa19a3d、iOS tree與受驗e35501f逐檔相同，獨立窄審無blocker。原89列原生／相機／OS驗收繼續保留未完成狀態。


## 23:49 UTC G03/G13 拍攝資料夾與檔名收據

- [Capture configuration receipts](capture-config-receipts-20261017.zh-TW.md)：真API證實206／Content-Range曾將未建立的五字元suffix回報為成功；檔名讀回不符原本會拒絕完成，但誤標stage=acknowledged。Bridge／Android只在原capture ACK前加入完整性gate，保留原未知寫入恢復、非2xx與嚴格JSON、suffix／讀回／選取語義，不重送mutation。iOS原有完整性判定已涵蓋，未改。
- Bridge同一新15案基準6紅／9綠；與既有capture案35綠，較廣545回歸已含新案。Android同12案基準6個runtime紅→固定12綠；原owner／取消fence與readback mismatch保持。兩個完整Kotlin檔PSI零語法錯誤，三個MockWebServer測試已寫未跑；不是完整type-check或原生通過。
- 九行產品diff經兩方窄審。整合focus與capture兩批不同hunk後，Bridge focused73案通過；另用最終合併來源核對Android capture12／focus13入口host皆通過，[合併收據](evidence/focus-capture-integration-20261017/)另存。這是合併驗證，不重複計為新功能。原89列平台驗收仍未完成。


## 23:56 UTC G08/G10 媒體刪除不完整204

- [Media DELETE receipts](media-delete-partial-receipts-2026-10-17.zh-TW.md)：帶Content-Range的空204曾被確認刪除，真API與原PC函式因此移除項目／消耗選取並抑制晚清單，即使合成peer仍保留原檔。Bridge／Android兩行修正只收窄204成功界線，包含空header；403與帶range的403維持原拒絕分類。iOS原本已拒絕，PC錯誤處理原樣保留。
- 新focused基準6紅／37綠；Bridge相關188通過，PC ownership48通過。相同7案API→原PC函式中，3個partial204改為uncertain並保留項目／選取；完整204兩控制仍成功，206／403原分類保留，每案只一次DELETE。Android exact transport/helper＋擷取caller host基準3紅→10綠；native MockWebServer已寫未跑。
- 合併capture／focus來源後Bridge focused96、API7→PC7、Android10再次通過，[精確收據與基準／固定回覆](evidence/media-delete-integration-20261017/)保留。這是同檔不同hunk整合驗證，不重複計為新功能；原生／真相機／OS目的地與原89列其他驗收未因此完成。


## 2026-10-18 00:02 UTC G04 相容REC API收據

- [Legacy recording ACK](legacy-recording-ack-receipts-2026-10-17.zh-TW.md)：公開且需身分驗證的舊Start／Stop API曾將部分相機回覆轉成HTTP200與新recording布林值；Stop可誤報false而合成peer仍錄影。現行三端UI走較新的嚴格outcome，不宣稱同一UI缺陷。
- 只新增POST／PUT recbutton的ACK-only判定，部分成功沿既有RECORDING_COMMAND_UNCONFIRMED拒絕，先於cache／observed更新；保留原完整ACK、非2xx、status-readback-failed、temperature preflight與單次dispatch。八條body期限／modern schema／mobile UI不變。保留的status仍是先前ACK cache，不能當成物理停止證明。
- 同一70新案基準36紅／34綠→固定70綠，既有Python508與Node154回歸另列；獨立275覆核包含新70與modern205，不重複累計。完整legacy及media兩批合併後，五個相關suite共252通過，[整合來源收據](media-legacy-integration-20261018.json)保留；無native或真相機驗收提升。

## 2026-10-18 01:49 UTC：#129 隔離 PTP 邊界

- [iOS PTP 介面／純狀態機切片](ios-ptp-boundary-2026-10-18.zh-TW.md)：新增精確 handle/session/request、read-only DeviceInfo semantic port、退場清理及最多四個未解決 session 的 admission；尚無 native adapter 或產品入口。
- 106 個 iOS 來源守門檢查與 4 個 project-path 檢查通過；新增 29 個 fake XCTest **NOT_RUN**，Swift/Xcode、USB、權限 prompt、真機均未驗。來源檢查不證明 Swift runtime 行為。
- [原未完列公開契約核對](public-provider-contracts-2026-10-18.zh-TW.md)：確認 Apple 原生 iOS PTP API 與公開 EDSDK API 文件；校正過時的文件／平台限制。其他 gphoto maintenance/metadata/coordinates 與 CCAPI upload 仍依各自缺少的確切契約保持 fail-closed。
- 原始 89 列狀態不改成全部完成；#129 仍缺可用 native provider 與驗收。Release Assessment：none。

## 2026-10-18 03:45 UTC：Bridge 實作缺口續修

- [EDSDK 精確相機／playback staging 退場](bridge-contract-retirement-2026-10-18.zh-TW.md)：錯誤 provider 相機拒絕＋cleanup；兩個 ticket 撤銷交接競態不再遺留原檔暫存，iterator／配置錯誤保留正確 ownership 與 primary error。
- [gphoto upload inventory](gphoto-upload-inventory-2026-10-18.md)：嚴格目的地／count／identity／UTF-8 admission 與 readback；host-only 列表不能授權 camera upload。
- [gphoto 精確大小收據](gphoto-upload-size-receipt-2026-10-18.zh-TW.md)：一次 exact-target show-info，以唯一 primary File Size 驗證精確 bytes；不把 list-files 的取整 KB 當精確大小，不重送 upload。
- [整合收據](bridge-contract-integration-20261018.json)：同一來源十個 Bridge suites 210 項通過、Ruff／diff-check 通過。這是已執行 Python provider/API/helper 測試；與先前僅來源檢查的 iOS PTP 切片不同。真相機、native SDK、mobile native 未驗。
- Release Assessment：patch；未改版本、未合 main 或發布。原 89 列仍未全部完成。

### 2026-10-18 04:00 UTC：本地 USB ownership 退場整合

EDSDK 回傳錯誤 session 後 close 失敗，現保留精確 rejected owner，後續 local admission/discovery fail closed；明確 reconnect 或 shutdown 才嘗試原 owner cleanup。正常 DELETE/shutdown close 尚未完成時，原 camera reservation 同樣擋住其他引擎、同相機與 AUTO 新開啟，CCAPI 與既有 owned Stop 保持可用。新增兩者同時存在的交互測試，確認先拒絕新 admission，不提前重試 pending close。合併後 103 個 distinct Python 測試、Ruff 與 diff check 通過。詳見 edsdk-rejected-open-cleanup-2026-10-18.md 及 local-close-reservation-2026-10-18.zh-TW.md。正常 close 拋錯後 recovery、active duplicate open 的 side effect、實體 cleanup 及三平台 native 驗收仍未完成；此列不改寫原始 89 項歷史狀態。

### 2026-10-18 04:04 UTC：active 相機重複 open 前置拒絕

六個 API 紅案例證明原先相同 canonical camera ID（兩引擎、explicit/AUTO）與 gphoto raw-port alias 雖回 409，仍先呼叫第二次 provider.open。現於既有 local admission 內依已解析 engine 與既有 reservation 提早拒絕；gphoto port alias 只讀已登錄 descriptor，不新增探測。AUTO 原有 health/discover、default 選機及不同相機保持原契約。合併後 111 項測試、Ruff/diff 通過，詳見 local-duplicate-admission-2026-10-18.zh-TW.md。未新增真機或 native 驗收證據。

### 2026-10-18 04:15 UTC：清理失敗持續隔離，修正先前 retry 推論

正常 registered close 失敗後現保留精確 owner／reservation，local POST、AUTO、discovery 持續拒絕，CCAPI 與其他已登錄 session 的 Stop／DELETE、ordinary-Exception shutdown drain 保留。真 GPhoto2Session＋FakeRunner 證明 close 可能先置 closed 再拋 release error，第二次 close 無命令卻返回 None，因此不能當成功釋放收據。EDSDK protocol 同樣未提供失敗後 retry ACK；先前 04:00 允許 reconnect 重試清除的推論現明確撤回，rejected session 首次 close 失敗後也持續 process quarantine，不再自動重試／解鎖。初次 close 成功的既有路徑保持。代價為需確認相機狀態、必要時修復 provider 後重啟，restart 本身不證明物理 release。合併驗證 125 項＋新增 3 項 distinct，共 128 項已執行通過；變更後兩個測試模組 34 項通過，Ruff/diff clean。詳見 registered-close-contract-audit-2026-10-18.md 與更新的 edsdk-rejected-open-cleanup-2026-10-18.md。

## 2026-10-18 08:47 Android 原始協定 JVM 補證

[恢復與真執行紀錄](android-protocol-jvm-recovery-2026-10-18.zh-TW.md)：完整原 Kotlin 檔編譯與 72 個不同原 JUnit 案例通過，包含 USB observed folders、真 OkHttp Direct Release／Focus／Live View／DELETE。Live View fixture 的 localhost 雙位址造成 2 個 retry／cleanup 失敗，明確綁同一 IPv4 origin 後 8/8通過；產品 strict transport／timeout／owner 不變。這取代相關 authored-only 測試的未執行狀態，不取代完整 Android Gradle／裝置／相機驗收。

### 2026-10-18 09:09 UTC Android 原始網路契約恢復

[詳細證據](android-liveview-recording-recovery-2026-10-18.zh-TW.md)：31 類 baseline 500 項有 56 fail；據此修復 AUTO Live View fallback geometry owner 與 REC malformed-success unknown 分類。完整 106 檔重新編譯及最終 61 項通過。兩大網路類剩餘失敗另行保留，未計為全套綠燈；Android native／真機、Apple-only build／真機門檻不變。

### 2026-10-18 09:24 UTC 原網路 500 項整批收斂

[詳細證據](android-network-suite-2026-10-18.zh-TW.md)：31 類原網路 JUnit 500/500 passed、106 檔完整編譯及 source hash 一致。原56fail經產品修正與嚴格匹配的fixture逐項收斂，沒有放寬安全gate；所有185個test檔、完整App/native與三端真機仍有獨立未驗門檻。

### 2026-10-18 09:38 UTC ViewModel 完整 caller 閉包

[詳細證據](android-viewmodel-caller-2026-10-18.zh-TW.md)：恢復官方 Compose/Lifecycle 最小材料，確證並補齊原 CameraViewModel 缺失的四個 import；完整48檔編譯，五類 caller 106/106 通過。保留原105/106失敗證據及 recording listing 計數修正；第一幀取消8案、完整App/native與真機仍獨立未完成。

### 2026-10-18 09:47 UTC startup 退役與單一 Stop owner

[詳細證據](android-startup-retirement-2026-10-18.zh-TW.md)：真socket／Robolectric首幀契約揭露並修復restart等待舊Start、rapid off/on遺失replacement、disconnect雙Stop三個缺口。完整來源編譯及186個不同host案例通過，包含failed cleanup保留責任且不重送。新增官方host runner不代表Android裝置／完整App／三端真機驗收。

### 2026-10-18 Android UI full-source host closure

原 UI 77 類已有 72 類本輪完整來源 host 執行證據；新增 media caller 51 案、其餘 58 類 498 案（兩個 Android runtime harness 修正後均通過）、label 3／language 3／connection 8 案。剩餘 5 類完整來源共用 Media3 UI 材料門檻。未宣稱 UI／真機／三端完成。詳見 [完整收據](android-ui-host-closure-2026-10-18.zh-TW.md)。

### 2026-10-18 Android data full-source closure／音訊事件一致性

原剩餘 48 類完整來源執行：USB 97 案修正漏參數後全過；其餘 420 案 baseline 2 紅，LATM 16 案用合法 Robolectric 通過，音訊事件錯誤先於 availability 撤銷已用兩個 listener 紅測確證並修正，最終 34 項音訊／owner 回歸全過。RTP 三個 codec readiness／failure 案仍需真 codec 或明確故障 fixture；預設 Robolectric passthrough 不具該證據力。詳見 [紅綠與材料邊界](android-data-host-closure-2026-10-18.zh-TW.md)。

### 2026-10-18 #129 production coordinator

原 PTP value port／reducer 已補 production serial effect pump，逐 effect admission、跨 thread callback、重入取消及精確 cleanup ownership；原 29 案改用真 owner，加 8 案，共 37 XCTest 真執行通過。仍無 Apple adapter／wire codec／產品入口，非 iOS USB 完成。詳 [coordinator 交付範圍](ios-ptp-coordinator-2026-10-18.zh-TW.md)。

### 2026-10-18 15:34 UTC：#129 首次 session 原碼與精確拒絕契約

共同 dev c32f1db549958975ac94ed914b051a9ec316035c。共享核心 50/50 XCTest 真執行通過；包含取消／替換後 late-ready 與未送出拒絕的紅→綠反例。Apple adapter 原碼採首次 session、process-lifetime 唯一 owner、最多 4 個 retained native objects；Apple 分支 NOT_COMPILED／NOT_RUN，DeviceInfo／同物件重開仍 unsupported。未開產品入口，原 #129 仍未完整；詳 [隔離 lifecycle 驗證](ios-ptp-apple-lifecycle-2026-10-18.zh-TW.md)。

### 2026-10-18 15:38 UTC：#129 純 DeviceInfo dataset

新增有界 Swift DeviceInfo dataset decoder，原 Android 完整 production classes 的 13 組同 byte oracle 逐欄對照；Swift 共 64/64 XCTest，原 50 保持。這只處理已分離 dataset，不接 Apple completion、不新增 raw command；#129 native wire／產品接線仍未完成。詳 [跨語言 dataset 驗證](ios-ptp-device-info-dataset-2026-10-18.zh-TW.md)。

### 2026-10-19 Apple SDK／原生合成旅程補證（第十七輪）

[完整逐輪證據](apple-actions-2026-10-19.zh-TW.md) 的 run `39232965133`／dev `ca4eefd25b10255419293838b629d3af057f80dd` 已完成 Apple Core、App 825/825 與 UI 43 案中的42案。原 LUT27、JPEG original15／LiveView decode3 已有真 Apple 結果；本輪 direct 控制往返、雙語 XXXL 日期／metadata、真 OS picker 取消重開及六種合成上傳結果／重連通過。尚有 canonical English 錄影選段1案失敗，不能宣稱 Apple 全綠；Android exact Media3 UI 材料、codec runtime、Windows/native provider、實體相機及 #129 wire／產品入口各自仍有未完成範圍。這些是更新後的執行證據，不將原89列歷史狀態一概改為已對齊。

### 2026-10-19 11:00 UTC：#129 Apple SDK 條件分支真編譯補證

run `39240488333`／dev `285ca4b246158f4575cb62c62bf722234ec9b9b2` 的 App SDK probe 真 PASS、非 SKIP，確實引用 concrete `ImageCapturePTPApplePort` 並核 protocol conformance／static budget。這收斂原 Apple 分支 NOT_COMPILED 門檻，無 adapter 實例、權限請求或裝置操作；native session/event/runtime、DeviceInfo wire 與產品接線仍未完成。App826全過，UI仍有1案失敗，詳逐輪Apple證據。

### 2026-10-19 13:14 UTC：Apple-only 全批真驗通過

[run39252314539](https://github.com/js051/open-eos-control/actions/runs/39252314539)／dev `0a2035fcc84f4f18c19ab8b1c8863e340077f457`：App828/828、UI43/43、Core與安全gate全部通過；包括同epoch音訊退場倒序新回歸、原背景案例及真picker六組。原DocumentManager crash本批只有診斷、未證實修復，保留穩定性風險。此更新關閉目前Apple gate紅案，不把額外Apple工作流當原ci-complete、Windows/Android/native或全部89列驗收。


### 2026-10-20 平台證據對帳 / Platform evidence reconciliation

本次是已有終態的文件對帳，不是新跑測試；不將不同 SHA 組成虛構的共同全綠 head，也不宣稱原 89 項全功能已驗收。

- Apple：[39350717367](https://github.com/js051/open-eos-control/actions/runs/39350717367)，`c7cdd68a9cbccd48c5569abec1bd7b218644891b`，2026-10-20 01:39:25 UTC success，Core669／App833／UI43。詳 [Apple 原生證據](apple-actions-2026-10-19.zh-TW.md)。
- Desktop：[39342666839](https://github.com/js051/open-eos-control/actions/runs/39342666839)，`1e7929f71099a179c74083cb46e6ea40e7b25d4e`，2026-10-19 23:49:03 UTC success，原完整 Chromium／Python 與 wheel/sdist。詳 [Desktop 證據](desktop-actions-2026-10-19.zh-TW.md)。
- Windows：[39342666985](https://github.com/js051/open-eos-control/actions/runs/39342666985)，同 Desktop SHA，2026-10-19 23:38:51 UTC success；原生 standalone build／launch verification，不是 USB／相機驗收。
- Android host：[39360736976](https://github.com/js051/open-eos-control/actions/runs/39360736976)，`bd9cf61365809c4ad14a1fa8f4469bb6def936fc`，2026-10-20 02:57:52 UTC success，1,867 tests／164 suites、零 failure／error／skip，debug APK／signer smoke。五個 UI-source suite 與三個負向 RTP host 缺口已關閉；詳 [原案例及完整證據](android-ui-host-closure-2026-10-18.zh-TW.md)。

These are separate successful supplemental platform runs at their stated source commits, not a single common-head acceptance run. Historical NOT_RUN statements for those specific completed gates are superseded only within the documented scope. This reconciliation does not represent new execution or 89/89 feature acceptance.

仍未關閉 / Still open:

- Android API34／36 instrumentation 的既有 RTP pixel/orientation、Compose／SAF 失敗不因 JVM 全綠而消失；真 MediaCodec、GPU／Surface、系統 provider 與裝置行為仍需相應執行。
- #129 的 Apple SDK concrete adapter 編譯已證，但 DeviceInfo native wire／runtime／產品接線仍未完成；真 EDSDK 仍需合法匹配 SDK／ABI／provider。
- direct CCAPI upload、gphoto normalized AF／Click WB、逐檔 metadata mutation 等契約或未實作邊界保留，不能改稱只待測試。
- 真相機／USB／網路／物理結果、原 PR `ci-complete`、main acceptance 與 release 均為獨立門檻。No emulator/KVM authorization, merge or release is implied.

Release Assessment: none，僅對帳驗證文件；保留所有先前失敗與實機限制。
