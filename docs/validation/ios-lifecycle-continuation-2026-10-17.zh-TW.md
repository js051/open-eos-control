# iOS event admission／維護後 RTP watcher 續行（2026-10-17 UTC）

## 固定來源與證據界線

基底為 `20251fcdfa80cabafad17c17a19959fce1aa5e8c`，本批只改 iOS `CameraAppState.swift` 的兩個既有生命週期缺口，增加 App regression 與 source guards。本文件不把原 89 列對帳改成全數 PASS，也不新增 transport、能力宣告、背景常駐或相機命令。

本環境 `command -v swift`、`swiftc`、`xcrun` 均無結果；**Swift compile、Xcode、XCTest、iOS UI、GPU 與真相機均 NOT_RUN**。以下反例由實際 caller/backend source 串接確認；沒有在此環境執行 production Swift，沒有把 Python 翻譯模型或 source guard 當成原生執行。

## 對回原功能列與平台行為

原列號沿用 [三端功能對帳](three-platform-function-ledger-2026-10-16.zh-TW.md) 第一欄的功能 ID，而非新增完成率。

| 原列 | 既有要求與本批進展 | Android／PC 對照及界線 |
|---|---|---|
| #41 CCAPI event polling、#73 Media browser（G06/G08） | iOS content event 已讀回控制狀態，等待 Live View retirement 完成後才能開始 gallery traversal。原先 admission 失敗會同步空轉；本批改成可取消的短暫 suspension。 | Android `CameraViewModel.refreshEventCameraState/refreshEventMedia` 使用 `delay(50L)` 等待 busy，並檢查 event/library generation；PC `startEventLoop` 使用 awaited listing／`sleep(25)` 等待，失敗走有界 backoff。這是 caller 結構對照，不是本批重跑兩端 native/browser。 |
| #55 CCAPI sensor cleaning、#81 iOS RTP H.264/AAC LATM Live View（G03/G06） | iOS 明確 Restore 重新開始既有 RTP source 後，重新註冊已存在的 render-progress watchdog。pause、背景、取消、舊 session 不得重新啟用 watcher。 | Android `continueMaintenance` 透過 `configureNativeLiveViewSession` 重新連接 native session/rendering，`AndroidCcapiRtpSession` 自帶 decoded/presentation deadline；PC Restore 經現有 `startLiveView({maintenanceRestore: ...})`。PC browser 的 JPEG/Bridge 預覽不等於 iOS 原生 RTP GPU。 |
| #61 iOS monitoring assists（G07，間接受影響） | watcher 保護的依據仍為 decoded／submitted／exact-identity presentation 分層。修復 Restore 後遺漏註冊，不改 LUT、scope 數值或 GPU 計算。 | 未新增 GPU、光學清晰、音訊輸出或真顯示驗證。#57/#58 JPEG/multipart 本批不更改 reader/backend。 |

## 反例一：content event 與 Live View retirement 造成 MainActor 空轉

1. `setApplicationActive(false)`，或 JPEG/RTP terminal failure，呼叫 `retireLiveView`。
2. `retireLiveView` 同步設 `liveViewRetiring=true`，另排 cleanup task，並未加入 `busyOperations`。
3. 同時到達的既有 content event 進入 `stableEventSnapshot`。當 busy set 為空，讀回 snapshot 可完成。
4. gallery 的 `refreshMediaAfterEvent` 呼叫 `begin(.mediaLibrary)`；`begin` 因 `liveViewRetiring` 拒絕。
5. 舊程式 `else { continue }` 在 MainActor 沒有 suspension，會阻止同 actor 的 cleanup、取消和 UI continuation 取得執行機會。

修正只在 admission 拒絕分支加入 `try await Task.sleep(nanoseconds: 50_000_000)`。下一輪仍重查原 event generation、Task cancellation、目前 screen 與 admission。沒有預先設 loading、沒有補發 camera mutation、沒有跳過原 ownership gate。取消會退出到既有 event loop 的 `CancellationError` 分支。

新增 native gated-Stop 測試以 event 更新的不同 synthetic model 作為 snapshot-publication 邊界：
- Stop gate 未開時，MainActor continuation 能繼續，且不開始 listing。
- Stop 完成後，待處理 event 僅 traversal 一次，不自動重開 Live View。
- 離開 gallery 不 traversal。
- disconnect／replacement 後舊 event 不 traversal，也不碰新 session 的 start/stop。

這些 XCTest **已撰寫但 NOT_RUN**。未修版本若真的進入 MainActor 空轉，測試裡同 actor 的時間限制本身不能解鎖；原生執行必須另有 runner/process timeout。本文件沒有聲稱已觀察到 native timeout。

## 反例二：sensor-cleaning Restore 遺漏 RTP render watcher

`performMaintenance` 呼叫 `pauseLiveViewForBulb`，會取消並清空 `liveViewRenderWatchTask`。舊 `restoreMaintenance` 成功後只重新開啟 RTP rendering，沒有重新呼叫 `beginLiveViewRenderWatch`；一般 Start、Auto Refresh、倍率完成與 Bulb 恢復皆有此註冊。

修正在既有 `sameMaintenanceOwner`、attempt、Task cancellation、Stop/shutter/REC 安全檢查及 `maintenanceControlsPaused=false` 之後呼叫同一個 watcher。該 watcher 自己仍要求 autoRefresh、applicationActive、RTP source，並保留 session generation／Live View lifetime／cancel checks。

新增 internal read-only `hasLiveViewRenderWatchTask` 只供 App tests 觀察 retained task 是否存在。它不是 watcher 已執行、decode 成功或 GPU/display progress 的證據。

新增 synthetic direct-CCAPI + inert RTP factory 測試：
- Maintenance pause 清掉 task；明確 Restore 在 autoRefresh=true 時重新註冊，false 時不註冊。
- 回背景再次清掉 task。
- 在 RTP Start response gate 取消 Restore，不註冊 watcher。
- gate 中 disconnect，舊 Restore 不替 replacement session 啟動 watcher或 RTP。

inert RTP factory 不接 socket、不解碼、不輸出 GPU；它只隔離實際 App→Core 的既有 caller lifecycle。上述 native 測試全部 NOT_RUN。

## 本地已執行檢查

- `python -m unittest discover -s scripts/validation/tests -p 'test_*source.py'`：130/130 source guards，包括本批 5 個。不是 Swift 執行。
- iOS project source paths 4/4：新測試位於現有 required `Tests` directory，沒有改 XcodeGen target 或把測試排除。
- RTP presentation contract 3/3：112 個既有資料向量及非有限數值檢查，僅 host evidence-policy/data，不是 native rendering。
- Live View contract shape 1/1、maintenance contract shape/boundary 2/2：直接呼叫現有無 fixture 的 Python checks，因環境沒有 pytest；沒有安裝任何套件。
- 兩個窄 source guards 分別對真正的基底 `git show 20251fcd:ios/OpenEOSControl/App/CameraAppState.swift` 返回 false，對修正版返回 true。這是 static negative control，不是 runtime reproduction。
- `git diff --check`：PASS。

最初單次 source run 的 1 個失敗是註解換行造成字串比對未命中，修正 guard 後上述重跑通過。嘗試 `python -m pytest` 回 `No module named pytest`；未以安裝或網路補件繞過。

## G01–G10 本輪查核與仍缺證據

| 群組 | 已查核既有 iOS caller/backend 路徑 | 本輪結論及 residual blocker |
|---|---|---|
| G01 | ConnectionView／connect、cancelConnection、retireConnectingSession → CCAPIClient／DesktopBridgeClient | 未找出新的確定缺口；late allocation/cleanup owner 已有路徑。實際 iOS 網路切換、權限與 camera/Bridge retirement 仍未驗。 |
| G02 | refresh／event snapshot／diagnosticReport／canPublishExport | generation-owned read/export 已有；真 battery/lens/temperature 與裝置診斷未逐列驗。 |
| G03 | setSetting／capture configuration／maintenance histories → session/client readback | 本批補 maintenance Restore watcher。其餘 settings/readback/cancel 既有；相機實際設定及 sensor cleaning 未驗。 |
| G04 | captureStill／toggleBulbExposure／toggleRecording／retryShutterRelease | 既有 command response、Stop duty、read-only recovery 分開；光學曝光、真 REC 停止、斷網/強殺結果未驗。 |
| G05 | held AF／autofocus／Near-Far／tapFocus／magnification → session/client | owner、lifetime、geometry gate 已有；真 AF/鏡頭位置、倍率和 firmware 行為未驗。gphoto 座標能力 D/E 不變。 |
| G06 | start/restart/retire Live View、event loop、RTP controller | 本批兩個 source 反例已修；Swift/XCTest、native decoder/audio/GPU/presentation 仍 NOT_RUN。 |
| G07 | monitor settings／LUT import／presentation progress 與 RTP monitor sources | 本批不改計算；本輪只追蹤與 watcher 相關入口，不代表逐個 LUT/分析算法重審。iOS GPU 真輸出仍缺。PC-only local UVC 不推定為 iOS 需求。 |
| G08 | latest-media review／progressive library／preview/video ticket owner／event refresh | 本批補 admission suspension；大卡、實際相機、原生影片播放器與 UI 未驗。 |
| G09 | original/batch download／Files export／foreground read admission／receipt ownership | caller 路徑已有，未發現另個確定反例；iOS Files provider coordination、權限、真目的地及外部同步未驗。 |
| G10 | per-item writable mutation／batch／delete attempt evidence／upload result | 既有 owner/readback/unknown semantics 保留；真相機寫入、刪除與支援 transport upload 未驗；direct CCAPI upload、gphoto metadata mutation 不憑空補命令。 |

上述「未發現另個確定反例」僅指本輪有界 source 查核，不是各群完成、原89列PASS或沒有任何潛在 bug。

## 交付狀態

本批沒有改版本、合 main、tag、發版、移除必要驗收，沒有新 iOS 安裝包。本文撰寫時尚未 commit/push；後續由獨立 exact-commit readback 記錄 dev source 交付。即使 dev 已有 source，native NOT_RUN 與實機限制仍保留。
