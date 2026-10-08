# Android 最近素材讀取失敗與未找到素材的區分

基底：`fd3e9837cf1ce2c783acd5a191d252634c1cc6c1`。本批限於 Android 拍攝／錄影停止後既有最近素材查找，不改相機控制命令、傳輸能力或版本。

## 行為契約

- `awaitCaptureReviewItem` 回傳有型別的 `Found`／`NotReady`／`ReadFailed`，不再把「每輪 listing 都失敗」當成「成功查詢但沒有新候選」。只有本次有界查找的全部讀取失敗，才顯示 `READ_FAILED`；四輪中任一輪成功但未找到合格候選，仍為 `NOT_READY`，包括成功後又讀取失敗的混合順序。
- 英文顯示「Recent media could not be read. New media availability is unknown.」；繁中顯示「無法讀取最近素材，目前無法確認是否有新素材。」。不把原始 exception、HTTP body、位址或其他 private detail 放入 UI state。
- 初次加 250／750／1,500 ms 三次重查、每輪至多八個候選、既有後端 listing 語意均保留。八是候選數，不是 HTTP request 的總上限；native CCAPI 可能需要 page／fallback／metadata 讀取。
- `READ_FAILED` 與 `NOT_READY` 都可手動只讀重查，保留同一次 capture／recording 的已知 ID 邊界。重複點擊與 MEDIA／CAPTURE 忙碌不啟動第二組查找。重查不重播快門、AF 或 REC Stop，也不自動建立額外 baseline scan。
- 原有最近素材及 thumbnail 不因查找失敗被清空，也不把它冒認為本次拍攝產物。成功的後續 event／gallery listing 若仍只有舊素材，將 `READ_FAILED` 改為 `NOT_READY`，並保留原排除集合；新合格候選才結束 pending attempt。
- 錄影停止的查找仍只接受影片。新 JPG 可以證明 listing 成功，不能結束 pending video review。
- 快門 ACK 與 status readback warning 各自保留；媒體讀取成功不清除獨立的 status warning。UI 的既有說明仍明示新可見素材無法確定對應這次拍攝／錄影。
- `CancellationException` 繼續傳出；既有 session／connection／generation 所有權檢查保留。較晚回來的失敗或取消不能改寫較新拍攝／替代連線。

## 因果證據

1. 未改 production 的獨立 HTTP 反例，連線先讀取 `OLD.JPG`，快門 ACK 後四輪 `/ccapi/media` 全部回傳 HTTP 503。測試實際得到 `NOT_READY`，預期 `READ_FAILED`；Gradle exit 1，1 test／1 failure。這是產品紅測，並非編譯失敗。原始 XML、log、test patch 與來源雜湊保留於本地驗證資料，未以重跑覆蓋。
2. 有型別結果與 UI 分流修復後，focused JVM 共 **70/70 通過、0 failure／error／skip**，Gradle exit 0（1m59s）。三組為 still review 21、recording review 21、helper／media 28；包含原始紅測。此後再補兩個 VM guard 案例與一個完整 UI journey，故 focused 不是最終來源的 aggregate 證據。
3. 第一輪 aggregate 同時要求 app JVM、Lint、APK、AndroidTest APK 與 contract checks，在 app test／lint analysis 進行中出現 `Gradle build daemon disappeared unexpectedly`，exit 1。沒有該輪終態 XML，因此記為失敗／未完成，不能推定測試通過；沒有證據確認 daemon 消失原因。原始 log、exit 與一致的來源 manifest 均保留。後續改以單一 worker 分階段執行，未削弱 Lint 或測試設定。

## 自動測試範圍

- Production `CameraViewModel → CameraRepository → CCAPI` 及獨立 MockWebServer：simulator 與 native all-failed、反覆重查仍失敗、failed → successful old-only → new candidate、成功位於四輪任一位置、先失敗後新候選、status 與 listing 失敗並存、event 復原、晚失敗與新拍攝／disconnect 競態、錄影失敗後 new JPG／new MP4 分流。
- Helper 保留原有延遲候選與 cancellation 測試，新增 empty／known-only 的混合輪次、失敗後取消、影片限定的合格候選。
- Compose instrumented source 覆蓋英文／繁中、320×480／480×320、200% 字體、不同無障礙描述與訊息、只讀重查、舊素材入口、無舊素材、MEDIA／CAPTURE disabled 與復原後關閉 notice。
- `CameraPreviewSaveJourneyTest` 增加完整 App／production VM／HTTP／MediaStore 案例：四次 503 後在真實畫面看到讀取失敗，保留舊圖及 ACK、透過 UI 按鈕重查、HTTP gate 期間重複觸碰不增加讀取、成功後開新圖 display preview，再以一次 original GET 保存 byte-exact 原檔並解除 MediaStore pending。整段核對只有一次 shutter POST，沒有額外 AF 或其他 mutation；display bytes 與較大的 original bytes 故意不同。這是供裝置 CI 執行的 source，不能把編譯當成 runtime 通過。

## 驗證狀態

第一個單一 worker app JVM aggregate 已於 2026-10-08 09:07 UTC 結束：Gradle exit 1（12m11s），**983 tests，3 failures，0 errors／skips**。失敗均為 `CcapiClientTest` 的 1-minute `UncompletedCoroutinesError`：

- `canonCurrentSourceAudioControlsExposeR6MarkIIIAbilities`
- `malformedAdvertisedLensAndTemperatureRemainPlanned`
- `realMediaFoldersUseTheSameIdentityForAbsoluteAndRelativeListingsAndRefresh`

本批最終來源的三組 review suites 在此輪為 **72/72 通過**（still 23、recording 21、helper 28）；不抵銷 aggregate 的三個失敗。這三例不在本批改動來源內，但不能據此稱為通過或直接判定為環境問題。原始 XML、log、exit 與 unchanged-source manifest 保留。此輪因 app test 失敗而未執行後續 contract tests。

資源協調後，以 command-local `ActiveProcessorCount=2`、`maxParallelForks=1`、單一 Gradle worker 與 in-process Kotlin 分階段重查，未更動 repository build settings 或測試 timeout：

- 09:36 UTC exact-three recheck：**3/3 通過、0 failure／error／skip**，Gradle exit 0、1m14s，420s 外層上限。三例時間依序 51.016／10.279／0.198s；audio fixture 接近既有 60s coroutine bound，故通過不代表已找出原失敗原因，也不抹除原紀錄。
- 09:37 UTC `compileDebugAndroidTestKotlin`：exit 0、40s，420s 外層上限。兩個更新的 instrumented test 檔與新完整旅程均已編譯；沒有裝置 runtime。
- 09:43 UTC 最終 bounded JVM aggregate：`testDebugUnitTest` 與 `camera-import-contract:test` 都完成，Gradle exit 0、5m23s，900s 外層上限。XML 為 **app 983/983、contract 14/14，合計 997/997 通過，0 failure／error／skip**。
- 09:49 UTC `lintDebug`：exit 0、1m47s，600s 外層上限；**0 errors、66 warnings、2 informational findings**。fatal `LintError`／`ObsoleteLintCustomCheck` 設定未變，沒有這兩種檢查器問題。
- 09:51 UTC `assembleDebug`、`assembleDebugAndroidTest`、`camera-import-contract:jar`：exit 0、1m38s，600s 外層上限；debug APK、AndroidTest APK 與 contract JAR 均存在並記錄 SHA-256。此為本地測試產物，不是公開 release candidate 或裝置執行證據。

以上階段前後 final-source manifest 一致。曾有一次 unit-stage launch 因 exec-server transport disconnected 而無法建立 process；確認沒有 receipt 後才重試，沒有重複啟動 build。

未宣稱 API 34／36 runtime、實體 EOS、USB 或手機驗證通過。

### 來源一致性

本地原始證據 manifest 的 SHA-256：

- 紅測來源 manifest：`5b0e7f3d406ea3d0e4bab7a2b8ca1218bfe0ac4bc570b5368f9e550c77710ef8`
- 70-test focused 來源 manifest：`941f64ad9f18ab1894b117375d7a5dac720fc644ce03621b0888aa1e0e46a792`
- 最終 production／JVM／UI source manifest：`5580407d5e3c8f2f87941f3a744566f79fdcbaaae1ac03e1d9a3eac625f90c90`

manifest 與各階段原始 log／XML 分開保存；失敗紀錄未以後續成功取代。最後一份包含兩個 instrumented test 檔，但包含來源不代表已執行其 runtime。

## 範圍與 Release Assessment

最新公開發布基準為 `v0.12.0` Development Preview；repository 宣告的 `0.13.0` 與其 frozen candidate 是另外的狀態，不代表已公開發布。本批為修正既有錯誤／恢復流程的 `patch`，不在此改版本、合併或發版。不是新的連線時錯誤介面，不改後端對部分／可選 listing 讀取的既有容錯規則，不做全卡查找，不解決素材與物理快門／REC 的因果認定。still 的候選種類繼續為既有 ALL；本批沒有新增 image-only still 規則。

本批交付狀態為 **implemented**；所有上述本地 gate 已有終態證據。尚待精確 head 的 CI／API 34、36 裝置執行；本機環境沒有 `/dev/kvm`，因此只編譯 instrumented tests，不冒稱執行通過。實體 R6 Mark III／手機／USB 驗證仍未執行。交付狀態不得只因本機成功升為 PR ready／main accepted／preview released。

## PR #220 裝置 CI 與 Dialog 測試配置修正

PR head `1063e732f2f130055d47277fedd7b248b70c649e`、run `37760192669` 的 API 34／36 各執行 357 tests，皆為 **356 pass／1 failure／0 skip**。唯一失敗都是 `CameraCaptureReviewUiTest.missingAndFailedReviewsHaveDistinctReachableReadOnlyActionsInBothLanguages`：英文兩個狀態之後，繁中 parent button 的 `contentDescription` 斷言與開啟動作已通過，但 Dialog 的 `performScrollTo` 找不到「尚未找到新出現的素材。」。這是找不到語意節點，不是已證實的文字截斷。兩個平台的完整失敗原件均保留，不重跑或替換掉這筆紀錄。

- API 34 artifact `11544270713`，ZIP SHA-256 `eac8d206de1e47c43e8e0779442141ff3b61252b1d88ab45c569cb5e9244395f`
- API 36 artifact `11544006349`，ZIP SHA-256 `b941890cdaf8a397f1b8a0eef8ab66c039881c044cff86339c0f951ad66a3390`
- 原始 XML／logcat 沒有本例的 Dialog 語意樹或失敗截圖，因此不能冒稱已直接看到原始 Dialog 的英文文字或實際 fontScale。
- 同一 run 的完整 `failedReviewRetryFindsTheNewPhotoAndSavesItsOriginalWithoutAnotherCameraCommand` 在兩個 API 都通過；這證明該 head 的真 App → VM → HTTP → MediaStore 失敗恢復旅程已執行，但不能抵銷本例配置檢查失敗。

來源診斷：現有 `DialogUiTestSupport.kt` 已明示獨立 Dialog 使用 `LocalView.current.context`，其新的 AndroidComposeView 會安裝自己的 density。單純 composition 的 Locale／FontScale override 不能證明 Dialog window 也採用該配置。現有 `DialogFontScaleOverride` 透過實際 ComposeView 傳遞覆寫後 Android context；同一 CI 內 `mediaSelectionActionsRemainReachableInTraditionalChineseAtLargeText` 已用它明確驗證 Dialog root 的 zh-TW resources 與 fontScale，API 34／36 均通過。這與本次「parent 已繁中，但 Dialog 找不到繁中文字」的失敗位置一致，足以支持修復 fixture，而不是改翻譯或放寬預期文字。

本輪修正僅限測試與此證據文件：

- 沿用既有 `DialogFontScaleOverride(2f)`，並在 locale／viewport 改變時以 `key` 重建 AndroidView，避免沿用 factory 第一次建立的 context。
- 保留英文／繁中、320×480／480×320 的 forced-viewport matrix、兩個狀態的 exact text、重查停用、舊素材入口、4 次 retry／open 以及零快門斷言。
- 新增實際 Dialog Android root 的 locale、resource text、resource fontScale 與 Compose density 200% 斷言；重查及舊素材按鈕另驗證完整 touch target 位於實際可見視窗內。forced viewport 不等同於真實裝置視窗旋轉，沒有以它宣稱實體 landscape 證據。
- 若再失敗，嘗試保存該測試的 unmerged semantics，並只在明確 synthetic emulator CI 中擷取失敗畫面；診斷無法取得時另記原因，再原樣傳出原始失敗；不更改 production 或原檔保存旅程。

修正後的本機終態（2 CPU／單一 worker／單一 test fork，前後 repair source manifest 一致）：

- 11:02 UTC Kotlin／Java instrumented compilation：exit 0、38s；Kotlin 實際重編，Java up-to-date，420s 上限。
- 11:03 UTC `lintDebug`：exit 0、1m1s，600s 上限；0 errors、66 warnings、2 informational findings，沒有移除必要檢查。
- 11:04 UTC debug／AndroidTest APK／contract JAR gates：exit 0、13s，600s 上限；AndroidTest APK 實際重新 dex／封裝，未改的 debug APK／contract JAR up-to-date。
- 未重跑 JVM aggregate。已逐檔比對原997-pass source manifest 中的 production／JVM／resource 及完整 original-save journey，全部 byte-identical；另確認 production、JVM 與 contract source scopes 相對 published head `1063e732` 沒有差異。這是沿用相同來源的既有證據，不稱作新的997次執行。

修正仍需新 exact-head API 34／36 runtime 驗證；上述舊 head 的 pass／failure 狀態不因此改寫。
