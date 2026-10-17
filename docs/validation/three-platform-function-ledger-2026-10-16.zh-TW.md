# 原功能與三端交付對帳（2026-10-16 UTC）

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
| 129 | iOS USB/PTP | G12 | D/E：iOS direct USB/PTP未實作；Bridge不等同直連。 | 原生N／browser待驗／成品無 |

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
