# Android 原 UI 測試完整來源閉包盤點

2026-10-18，接續已交付的 startup 修復，對尚未在這次恢復環境以完整來源執行的原 UI 測試做有界盤點，不抽取 production 函式、不以 stub 替代 collaborator。

## 已執行

- CameraForegroundJpegImportTest 23/23、CameraMediaPreviewOwnershipTest 28/28：完整 ViewModel／repository，真 loopback HTTP 與合成 output sink；不是 MediaStore 或真設備驗收。
- 66 個剩餘 UI 測試檔先編譯；補齊 repo 原 fixture loaders 後，58 類完整編譯。498 個案例的初輪有 2 個 runtime fail，其餘通過。
- DownloadHistoryTransferTest 的 ContextWrapper 在 android.jar compile stub 上失敗；正確 android-all 上 18/18 通過。CameraMediaPlaybackCacheTest 的 OsConstants 需要 Android runtime shadow，純 android-all 仍報 initConstants native link error。
- 兩類原測試加入官方 Robolectric runner（sdk35、manifest NONE），同一原碼 23/23 通過。僅改 harness，保留所有產品與斷言；原 stub／native-link 失敗均留存。498 的不同案例因此全部有成功執行證據，不將修後重跑 23 項重複加總。
- AppCompat 1.7.1 按原 build 版本從官方 Google Maven 恢復，AAR 1,148,656 bytes，官方 SHA1 與本地 SHA256 收據保存。完整 AppLanguage.kt 與原 3 項通過。
- CameraValueLabels.kt 含原 Composable 函式，以官方 Compose compiler 完整編譯，原 3 項通過。

原 contract resources 按 app/build.gradle.kts 的明列清單平鋪，保留每個原 fixture hash；測試工作目錄為 android/app，沒有猜 fixture 資料或改期望。

## 尚未完成

剩餘 Compose 元件閉包逐項列明；MediaGalleryComponents 同檔直接引用 Media3.ui.PlayerView，包含 CameraVideoPlaybackTest、CameraViewModelPreviewTest、MediaViewerTransformTest。原 media3-ui 下載拒絕路徑不重試；這些測試沒有因純 helper 容易抽出就被改寫成替代測試。

ConnectionRecoveryStateTest 已恢復原 BOM 的 10 個官方 Android variants 共 17,130,527 bytes，官方 module SHA256／SHA1 均一致；完整 AppTheme／ConnectionRecoveryComponents 編譯，原 8 項通過。

CameraOrientationTest／MediaLibraryTest 所需原 Lifecycle runtime-compose 2.8.7、Coil 2.7.0、Lucide 2.2.1 的 6 個官方 AAR 共 1,310,051 bytes 已核 hash 恢復，Lucide R 由原 AAR resources 以官方 aapt2 產生。完整 CameraComponents 編譯仍需要原 CameraActions，而其所在 OpenEosControlApp.kt 同檔呼叫 MediaScreen → MediaGalleryComponents → Media3.ui.PlayerView。因此剩餘 5 類共用這個已知材料邊界；不抽離 CameraActions 或 helper 製造替代綠燈。編譯失敗及材料收據保留。

77 類原 UI 測試中，72 類已有本輪完整來源 host 執行證據；不是同一批次重跑 72 類，也不代表完整 Android App、畫面或真機驗收。

[本批證據](evidence/android-ui-host-closure-2026-10-18/) 包含完整編譯命令、58 類 baseline log、fixture manifest、runtime 紅綠及官方材料收據。本批沒有 Android 畫面、完整 Gradle App、真相機或三端 parity 完成宣稱。

## Release Assessment

`none`：恢復原測試所需合法 host runtime，沒有版本、tag 或 release。

## 2026-10-20：完整 Gradle host 閉包已驗證 / Full Gradle host closure verified

以上 2026-10-18 的限制保留為歷史。2026-10-20 02:57:50 UTC，[Android host run 39360736976](https://github.com/js051/open-eos-control/actions/runs/39360736976) 在精確 dev head [`bd9cf61365809c4ad14a1fa8f4469bb6def936fc`](https://github.com/js051/open-eos-control/commit/bd9cf61365809c4ad14a1fa8f4469bb6def936fc) 成功。原 JDK 17／Gradle 8.7 執行 `:app:testDebugUnitTest` 與 `:app:assembleDebug`，使用未修改的 Google Maven／Maven Central 依賴宣告與完整 Compose／Media3 UI 來源閉包，沒有抽離 helper、替代 collaborator、排除測試或放寬斷言。

XML 合計 **164 suites／1,867 tests／0 failures／0 errors／0 skipped**。先前剩餘五類已在同一原 Gradle 批次執行：CameraVideoPlaybackTest **2**、CameraViewModelPreviewTest **20**、MediaViewerTransformTest **3**、CameraOrientationTest **17**、MediaLibraryTest **28**，全部通過；不再維持這五類 host 材料阻塞狀態。

AndroidCcapiRtpSessionTest **11/11** 通過，包含原三項負向案例：

- `encodedSpsPpsAndKeyFrameDoNotProveDecoderReadiness`
- `sdpParameterSetsAndEncodedKeyFrameDoNotProveDecoderReadiness`
- `decoderFailureRetiresEncodedOnlyInputAndReplaysFailureToALateListener`

這僅關閉原 host 執行缺口：截斷合成 SPS／PPS／keyframe 不得宣告 ready，以及 decoder failure／no rendered frame／late listener failure replay 的原斷言通過，**不證明真 MediaCodec 解碼或輸出畫面**。

完整 debug APK 建置與 signer smoke 通過。APK 20,396,732 bytes，SHA-256 `b06b3cd4d017f2b647b62ac25c07d3039489288373f9e3a6ccd6bbd551deec6c`。[原 XML、HTML 報告、APK 與 signer 收據 artifact](https://github.com/js051/open-eos-control/actions/runs/39360736976/artifacts/12237891744) 的 ZIP SHA-256 為 `9d5ef73d3ed1f2d2d46ad8f7e80373db93d75ce15302e61ec1da0a8373b4062b`；CI artifact 保留 7 天。完整歷史 secret scan 與 actionlint 同時通過。此批僅新增 supplemental host workflow，未修改產品、原測試或原 PR CI；沒有執行 emulator／KVM／udev、安全設定變更、main merge、tag 或 release。

**English:** The exact-head run above passed the unchanged full Gradle unit suite (1,867 tests, zero failures/errors/skips), debug APK build and signer smoke. The five previously blocked UI-source suites and three negative RTP cases are now verified in their original host test context, without reduced assertions or substitute implementations. Full-history secret scanning and actionlint also passed. This does not establish real codec output, Compose instrumentation, GPU/Surface rendering, physical-camera/USB/device compatibility, or PR `ci-complete`. Historical instrumentation failures remain unresolved by this host run. No emulator, security-setting change, merge or release occurred.

Release Assessment: **none**，只補 host 驗證與證據；instrumentation、實機及正式接受／發布門檻維持獨立。This is verification/CI documentation only, not release acceptance.
