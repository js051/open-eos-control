# Bridge 短控制停止責任與拍後只讀恢復：驗收記錄

日期：2026-10-08 UTC。狀態：本地已實作並整合；Bridge、PC、Android 與 simulator 的下列本地 gates 通過。Browser 與目前合併來源的 macOS／iOS CI 尚待完成，尚未 PR ready、main accepted 或發布。

## 來源與範圍

- Task branch：`fix/bridge-control-recovery`。
- 現行 accepted base：`f9e938b11d38f5b6e4d70406b6d2eadb0776592c`，tree `ba31a63c814e6aa43dfe21c1d1b92516ed74ad68`。
- 既有 12 檔從 `fd3e9837cf1ce2c783acd5a191d252634c1cc6c1` 原樣保存後，僅正常 targeted fetch 和 fast-forward。incoming 12 paths 與原 12 檔無 overlap；fast-forward 後逐檔 SHA-256 仍符合原凍結記錄。
- 本次沿原實作 review/補驗收，沒有新建平行實作。所有舊紅／綠／UNKNOWN 證據保留。
- 此記錄的 source-freeze 時尚無 commit、push、PR、merge、版本變更或 release。舊 iOS proposal 原樣保留，未直接套用；已另經 review 整合下述限定產品來源及新的最小接線。

本批包含：Bridge exact same-session 短控制 stop ownership；PC 可達 stop-only 恢復與已 ACK 拍後只讀查圖；Android／iOS narrow error mapping 與共用英繁停止警告；iOS 既有拍後素材／預覽原檔 owner 修復、必要 regression、fixture isolation 與文件。

## 行為與安全邊界

| 情境 | 已實作行為 |
| --- | --- |
| manual full-press、half-press 或 AF start 後 stop 失敗／回應遺失 | 在 start 前保存原 operation method、path、stop payload 和 feature；回傳 `SHUTTER_RELEASE_UNCONFIRMED`，保留原 session 責任 |
| pending stop | 阻擋新快門、AF、錄影開始、設定變更與不安全 Live View start；status/capability/info 等只讀診斷與既有安全 stop/close 仍可用 |
| 使用者明確重試 | 既有 `/bulb/stop` 相容 route 只發原 stop；不重播 start／AF／shutter，不重新 discovery 替換 operation，不把短操作記為 Bulb 能力 |
| stop ACK 後 status 讀失敗 | 伺服器已清除 stop obligation；後續 stop 不再重送該 operation。client 僅使用同次 stop 或其 fresh same-session readback 作為解除證據 |
| close | 對原 session 最後 retry stop 一次；未確認時回報相機端檢查警告，責任不轉到 replacement session |
| PC 晚到 AF／half-press／recording ACK 或 error | 不覆寫 replacement session 的 status、busy owner 或當前 error |
| PC 晚到 close／event-stop 結果 | 原 DELETE 仍只針對 captured session ID；不 reset replacement session、不覆寫它的診斷。舊 close 失敗只保留獨立 previous-camera warning |
| shutter ACK 且必要 manual release ACK 已收到，只有 status 失敗 | 專用 `CAPTURE_STATUS_READBACK_FAILED`；PC／Android／iOS 沿既有只讀 recent-media review，保留警告，不自動重拍 |
| 任意 502、未收到 shutter ACK、cleanup 未確認 | 不轉為已 ACK 拍後恢復；錯誤／stop-only 狀態維持 |
| 共用停止文案 | PC／Android／iOS 英繁文字涵蓋快門或 autofocus；已確認的 Bulb start/stop 仍維持長曝光語義 |

PC close 的新晚回應 regression 是 production function 的 source-level asynchronous contract；尚未以 DOM/browser 證明同一 replacement 交錯可由一般使用者操作觸發。

## 本次新證據

### 1. 凍結來源重新確認

既有 12 檔備份、binary diff、fast-forward 前後 receipts 和 hashes 一致。此結果只證明來源保留，不把歷史 pass 升格為目前完整驗收。

### 2. Bridge 窄契約

- 先對原 12 檔重跑 Python 26/26、PC Bulb contracts、capture-review Node 24/24；全部 exit 0。
- 補充真正 peer POST-only advertised operation、AF-only／沒有 Bulb 能力，以及短控制 pending 時的 write guard／只讀診斷／safe stop 測試後，Python **29/29** 通過，16.006 秒。
- 所有三類短控制均核對 originating feature、saved stop-only request 與不增加 Bulb observed evidence。
- 路徑：production ASGI TestClient → 真 UrllibCcapiTransport → 獨立 loopback TCP Canon-shaped peer。Bridge 入口不是 TCP；此項不是 browser 或實機證據。

### 3. PC cleanup ownership 紅綠

- 未修 cleanup continuation：29 個 Node cases 中 25 pass／4 fail。
- 3 個 red：舊 close 的 ACK、unconfirmed 與 lost-response 結果均可 reset replacement session。
- 1 個 red：舊 event-stop error 可寫入 replacement session 的 diagnostics。
- 最小 owner guard 修正後：**29/29 pass**；既有 Bulb Node contracts 仍 pass。
- 新增的 late AF／half-press success cases 原本即 pass；未冒稱全部新增 case 都是產品紅例。

### 4. Capture handler 內 recording ownership 補驗

獨立 source review 指出同一 handler 的 video branch 尚未先驗 captured session。新增 start／stop × ACK／error 四個 cases：未修 source 為 **33 cases、31 pass／2 fail**，兩個失敗只在 stale recording ACK 覆寫 replacement status；error cases 原本已有 guard，維持通過。

最小修正將 recording URL 綁定 captured sessionId，await 取得 local status，再驗 session 才發布。相同 suite **33/33 pass**；assertions 同時要求只送原 session 一次 write，replacement status／error／busy／stop flag 與 feedback 不受影響。

紅測當時的88個 source hashes已保存；其後逐檔找回完全相符 bytes並另存，清楚標示是 after-run archive，不倒填 launch-time copy。此為非DOM source-level交錯證據。

### 5. 完整 Bridge 本地 light gates

Python／Ruff完整執行時間：2026-10-08 14:53:47–14:54:17 UTC。recording修正後再於15:43:53–15:43:55重跑全部JS syntax／Node modules；Python production／test files未改。412-pass的whole-tree source仍屬14:54當時，不把後來app.js的變更倒填成同一次pass。

| Gate | 結果 | 邊界 |
| --- | --- | --- |
| `ruff check --no-cache . ../scripts/validation` | exit 0 | 正常完整 configured rule set；未縮減檢查 |
| 7 個 CI JS syntax checks | 全部 exit 0 | icons、diagnostics、monitoring、local-video、rtp-audio、media-transfer、app |
| `package.json` 全 9 個 Node module scripts | 全部 exit 0 | recording修正後重新全部執行，包含 capture-review 33 cases；無 browser |
| `pytest -q -p no:cacheprovider` | **412/412 pass，exit 0，27.699 秒** | 沒有以 unittest 取代 pytest functions、沒有 skip/xfail 或平行 workers |
| Source receipt | 88 tracked-source entries before/after 相同 | 含 Bridge／validation source 與本批修改檔 |
| Resource bounds | CPU0，serial，100ms RSS sampling，640MiB pressure stop | sampled aggregate peak **166.45MiB**；無 pressure 或 timeout stop；不是 kernel hard memory cap |

保留先前 full pytest red：411 pass／1 fail。精確原因為既有 `test_shutter_autofocus` fake-runner legacy-capture test 使用 default host storage，得到 `LOCAL_MEDIA_STORE_FAILED: Could not create capture staging: Read-only file system.`

修正僅為該 test 注入 pytest `tmp_path`，與其他 gphoto host-capture tests 一致，並顯示 response body 以便診斷。沒有更動 production storage、權限、測試 assertion 或系統設定。修正後重新跑上述整組 gates，原 red receipt 保留。

最新Node follow-through sampled aggregate peak為63.62MiB，所有88個source entries before/after相同。app.js修改後另於16:18重跑直接讀取它的`test_static_ui.py`：6/6、exit0、0.707秒；其他Python implementation/test source仍未變。

測試 warnings：目前 resolved Starlette 對 httpx TestClient 的 deprecation，以及現有 download route 的 duplicate OpenAPI operation ID warning。兩者未關閉，也不是此處的 failed gate。

## 工具與環境

- Python 3.12 task venv；官方 PyPI 安裝 repo 宣告的 `bridge[dev]` 和 CI 的 `build==1.5.0`。
- 安裝 49.075 秒、exit 0；`pip check` exit 0；native dependencies 使用 wheels。正常 TLS／build isolation，無 global/security 設定變更，舊未完成安裝 receipts 保留。
- 主要 resolved versions：pytest 8.4.2、Ruff 0.16.10、PyAV 18.1.0、FastAPI 0.143.0、Uvicorn 0.54.0、Pillow 12.3.0。
- 本地 Node 為 **24.19.0**，CI 設定是 Node 22。本地 Node 結果是補充契約證據，不能冒稱 exact CI runtime acceptance。

## Browser 本地執行與 packaging

- Locked npm setup最後一次成功：Playwright／playwright-core1.62.0，10.168秒，exit0，package.json／package-lock hashes不變，無browser download。第一次受阻的setup沒有可驗terminal結果，保留UNKNOWN，沒有用後續成功倒改它。
- 2026-10-08 15:31，第一個 `bulb-recovery.browser.test.js` 在Chromium launch階段失敗，6.137秒、exit1，還沒有page assertion。現有system Chromium154回報process-singleton `socket() failed: Operation not permitted`，另有crashpad database setup錯誤。
- 這是本地執行環境block，不是已證實的產品assertion failure；第二個capture-review browser journey未啟動。source不變，所有已知owned browser/server children均結束。
- 原測試只指定headless／executablePath；log的`--no-sandbox`是現有Playwright預設。它是診斷證據，不是本批新增或建議採用的security remedy。沒有重試、修改sandbox／security flags或繞過限制；兩個automated browser gates交正常CI，仍然pending。
- 15:29正常隔離Python build完成sdist／wheel，16.967秒、exit0、sampled94.63MiB；但它在recording修正之前，只算舊app.js的historical packaging。該舊receipt完整保留。16:02再用最終app.js重建sdist／wheel：18.840秒、exit0、sampled94.72MiB、source不變，wheel內ccapi.py／app.js與當前source hash一致。任何本地產物都不是公開release candidate。

## Android 現行完整 JVM

2026-10-08 15:52:08–16:00:13 UTC，正常完整 `:app:testDebugUnitTest :camera-import-contract:test --rerun-tasks`：**app995/995＋contract14/14，合計1009/1009，0 failures/errors/skips**，Gradle exit0，485.012秒，28 tasks全部executed。

- 所有app／contract XML本輪新產生，舊XML另存；不是沿用歷史46或其他先前來源的counts。
- 329 source entries與6 tool hashes前後一致。兩個CPU、一worker、一test fork，Gradle及test JVM皆明訂ActiveProcessorCount=2；原assertions/timeouts未改。
- sampled aggregate peak2.232GiB，未達3GiB pressure-stop，沒有timeout；這不是hard kernel memory cap。
- 新Bridge mapping與英繁共用stop文字test包含在內。這仍不是instrumentation compile、Lint、canary、APK或runtime。
- 初次metadata preflight只因successful empty `lslocks`輸出被JSON parser拒絕而停止，尚未啟動Gradle；其記錄保留。修正空輸出處理後才有上述完整實際執行，沒有刪除任何lock。

## Android compile／ordinary Lint／local artifacts

- 16:10–16:11 instrumentation source compile：exit0、54.150秒；Kotlin和AndroidTest resources實際rebuild，Java task up-to-date；2 tasks executed／27 up-to-date。沒有emulator或phone runtime。
- 16:11–16:14 unrestricted `lintDebug`：exit0、230.303秒；fresh XML為**0 errors／64 warnings／2 information**，沒有`LintError`或`ObsoleteLintCustomCheck`。9 tasks executed／20 up-to-date；無rule suppression或替代成canary。
- 這兩階段329 source entries與6 tool hashes前後一致；sampled peaks為1.370GiB／2.224GiB，無pressure／timeout stop。
- 16:20 debug APK／AndroidTest APK／contract JAR與debug signer metadata：exit0、54.741秒總計，36 tasks executed／30 up-to-date；329 source與7 tool hashes一致。這些是local debug artifacts，沒有上傳／發布／runtime。

| Local artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| app-debug.apk | 19570492 | `d786395792fddaed856812f3544c2f9d2f81d7a7d41216b595c9e90cfde49ac2` |
| app-debug-androidTest.apk | 1992906 | `16556d65d92d8973049a1b2a6b01a0028d18b6dadda38263df7fd6e56255d40b` |
| open-eos-camera-import-contract-kotlin-1.1.0.jar | 73705 | `5957a262e306c442b49134e30b5c5b8daea9e93887f726957ffac285c95c478c` |

## Android canonical 四 registry canary

2026-10-08 16:27:41–16:33:07 UTC，未改動的 canonical verifier 與 assertions 完成：outer exit0、325.458秒；synthetic Gradle 依原負向契約 exit1。Lint8.8.2 的四個預期 findings 均来自精確 generated source path，含對應 line：UnusedBoxWithConstraintsScope13、UnrememberedMutableState18、ModifierFactoryExtensionFunction21、NullSafeMutableLiveData25。

canonical 231 個 app source 的 fingerprint 前後一致，SHA-256 `a5a213c51ad12aff79de39830b52239099277e8e34f04418fe03248d46fe52d3`；外層329 source、7 tool hashes 與上述三個 local artifacts 也不變。兩 CPU／一 worker，Gradle 與 test JVM processor cap2，sampled peak2.431GiB。沒有 skip registry、改 checks 或把 ordinary Lint 替換為 canary。它證明 detector 真正載入，不是 instrumentation runtime。

canary terminal 之後才做一項 protocol 文件澄清：Android「發送前保留責任」明訂為 Bulb；AF／半按／manual 短控制由 Bridge pre-send 保留，Android 沿 error/status 接回。此 docs-only qualification 沒有改動已驗 Android／Bridge source。

## 尚未通過的 gate

1. Final batch 的正常 exact-SHA CI 與安全 hooks；獨立 Bridge／PC／Android source review 及 owner 的 iOS source review 未留下已確認的 blocking finding，但不能替代 CI。
2. 真 Uvicorn → urllib → 獨立 TCP peer → Chromium DOM／JPEG decode 旅程。本地 browser launch 環境受阻、第二條未跑；兩者待正常 CI，不宣稱 browser runtime pass。
3. Windows standalone 與正常 CI 的 security／release-helper gates。最新 app.js 的本地 Python distribution rebuild 已通過，仍不是 release 候選。
4. Android instrumentation runtime。完整 JVM 1009、instrumentation source compile、ordinary Lint、四 registry canary 和 local APK/JAR 已通過，不能替代 emulator／phone runtime。
5. 目前合併來源的 iOS Core／App compile、unit 與完整雙語 UI runtime。此環境無 Swift／Xcode，新增 Core／App tests 沒有本地 compile 或 runtime pass。
6. 真相機／Android／iOS hardware validation。

歷史 Android focused JVM 46/46 與 instrumentation source compile 證據保留；它們既不是本次新增 Android 文案的驗收，也不是完整 JVM／Lint／canary／runtime pass。

## iOS 最小相依性與整合限制

Accepted main 仍用 `previousLatestID` 單筆 helper。原 #219 產品來源改為 `observedMediaIDs`、`pendingCaptureReviewIDs`、`previousIDs` set、generation-owned bounded helper 與可達 read-only retry。舊 proposal 不得直接套回單筆 ID helper或複製它建立另一個 owner。

- Core typed error／窄 code mapping 可獨立檢查，與 #219 App owner source 無直接相依。
- 安全 App catch 必須沿 set-aware helper：先檢查 session generation，保留本次 pending IDs（空集合有效）和 readback warning；不經一般 catch 清理、不偽造 status／成功曝光、不重送 shutter。
- 要宣稱可恢復 App 旅程，必須包含對應 `CameraControlView` 的 previous/searching/notReady/readFailed/retry UI 與英繁字串，並追加 code 正負例、A/B 換序、空卡、重複 retry、media-list 錯誤、event owner、新 shutter／session 取代的測試。
- 本批不納入 #219 Android diagnostic observer，不把其舊 iOS 綠套用到未實作的新 catch。

### 目前整合來源與 provenance

2026-10-08 17:06 UTC，以下來源在隔離候選完成 source review、精確 hash 與 apply check 後，才套回原 task worktree：

- 原產品三個 changesets：`6e0c0741a3ff30e146c4108b02fcf44430260cea`、`11964df11bee6e3b399ca2fe75406e5f5cc2bd0e`、`fca4b92bdab473578d45c4bb5daba55fc064673b`。只保留其 16 個產品／測試／文件 paths，不複製舊 tree 或回退 accepted Android。
- 另行核准的 pure fixture correction：`d8a514c23c3532846fce0f629da13c56089e3cf7` 的單檔 `URLSessionShutterWireTests.swift` blob `9170270c5781d017b8d0a96de47c545db7ebc5f3`。SHA-256 `ddab76fe68494ffe3dc9aaf5f12133559d96bf9df466fdc38fb8807afedabafa`；保留 no-replay、64 KiB request cap、3 秒 socketpair bounds 及 EOF／partial／EBADF controls。
- 在這個 pinned product owner 上增加窄 typed-error mapping、App catch、英繁共用 stop 文案與對應測試。沒有 observer、buildSrc、diagnostic wrapper、workflow 或 mixed later validation docs。
- Reviewed combined patch SHA-256：`96f23cec1428515b38a4f10ec08466a59ff89709d8cc32059139fe17afc51fc2`；新的 incremental integration patch：`ab3f2a85cac7dd9afbb30f6cddced41f7c30841d8b81837781543d42e77bedec`。
- 實際套用只改預期 19 個 iOS／simulator／doc paths；此前 19 個 Bridge／PC／Android／doc bytes 和其餘 tracked source 均不變。套用後逐檔與 reviewed candidate SHA-256 一致。此處的 19＋19 是 source scope，並非測試數。

### 新接線與尚未執行的 Swift tests

Core 僅在 `captureStill` 的單次原 POST 回來時，把精確 `CAPTURE_STATUS_READBACK_FAILED` 轉為專用錯誤；HTTP／network／其他 operation 的同碼不擴大 mapping。既有 requestJSON 已先驗 session generation。App 專用 catch 再驗 capture generation，保留原 `pendingCaptureReviewIDs`（空 Set 有效），沿既有 bounded helper；不 fake status、不閃曝光成功、不 replay shutter。

- 新增 4 個 Core test methods：精確 code 與大小寫／前綴負例、其他 HTTP／network failure、其他 operation、single write、literal AF=false、stop ownership 及 exact warning。
- 新增 10 個 App methods，保留原 17 個方法及其 timeouts：所有已見 IDs、換序／較舊日期、空 baseline／成功空列表和失敗清單、只讀重試 single owner、media busy／不支援 media 零請求、event owner、舊 capture／listing／thumbnail 對新 capture 與同 wire ID replacement session、警告保留／無假曝光，以及 locale／正常 Bulb 文案。
- 共 481 個 en／zh-Hant keys 對齊；已知 Bulb labels 未改。既有 UI tests 的 generic stop/header/confirmation literal assertions 更新為新文字，其字級／旋轉／可達性等 assertions 保留。
- Owner 最後 source review 找到 Core exact-description assertion 仍用舊 warning 文字；修正 exact assertion，未放寬它。舊 bytes 與 patch 保留為「source-review mismatch」，沒有冒稱跑過 Swift red。
- 以上都是 source-level review。Actor／async type checking、held-response timing、Darwin socket fixture 和 UI 顯示仍須目前 combined head 的正常 macOS CI。

### 歷史文件的適用範圍

[原 iOS capture/JPEG 記錄](ios-capture-jpeg-delivery-2026-10-07.zh-TW.md) 及原 product-workflow 文件保持 `fca4b92` 的 pinned bytes。它們記述原 #219 來源與當時 CI；其中 App125、Core245、UI20 等數字、兩個 UI red 及當時「待驗」狀態，均屬歷史，不是目前 combined tree 的 pass。以本 addendum 和之後 exact-head CI 更新判斷，不匯入 diagnostic branch 後來的驗收聲明。

## Combined-source simulator gate

2026-10-08 17:06:29–17:06:32 UTC，在實際合併後 worktree 執行正常 simulator `python -m ruff check .`（exit0、0.105秒）和完整 `python -m pytest`：**59/59 pass**，exit0、outer 2.723秒，含 capture-delivery8、event-reset3 與原 main48；沒有 skips 或 assertion／timeout 變更。

現有 Bridge venv 已符合 simulator runtime／dev 宣告，重用前記錄 resolved versions；沒有新安裝。1 CPU、100ms aggregate RSS sampling、640MiB pressure-stop；sampled peak87.29MiB，沒有 resource stop。全部 tracked 及 nonignored new source hashes 前後一致。唯一 warning 是保留的 Starlette/httpx deprecation。

此結果證明 simulator fixture 和 reset ownership 的目前 Python scope，不證明 Swift compile、iOS UI、browser 或實體相機。

## 本地 release／CI／security helper gates

2026-10-08 17:09:42–17:10:09 UTC，沿正常 repository commands 執行，全部 exit0：版本 verifier 回報 metadata 一致的 `0.13.0`；diagnostic contract tests 20、CI helper tests 37、release helper tests 8、offline security hook integration tests 16。沒有新增 Python dependencies。

Security tests 使用真正 hook entry points、官方 Gitleaks8.30.1 archive 與 synthetic temporary repositories；clean／secret-bearing staged changes、outgoing ranges、多 ref、新 branch 和失敗下載／checksum／extraction／execution controls 全部保留。這不是本批 outgoing commits 的 pre-push scan；正式 commit／push 仍須正常 hooks 掃描。

每階段原 bounds≤60秒、1 CPU、100ms sampled aggregate RSS／640MiB pressure-stop；最大192.87MiB，沒有 resource stop，source hashes 全部不變。CI-helper log 的 incomplete-evidence warning 來自 negative-control fixture，不是目前 iOS runtime 的結果。Camera Import Python contract-helper 的 locked dependencies 未在此 venv 安裝，該額外 gate 留待正常 CI；Android contract JVM14 的已通過結果分開列示。

## 非目標與限制

本批不解鎖手機 Wi-Fi／Bridge-CCAPI 入口、不新增 AutoJPEG 或 Bulb 能力、不修改 Serein、不操作真相機／USB／使用者電腦。

Stop ACK 是協定回應，不保證實體相機已停止。close／程序退出也不能保證實體停止。發現 JPEG 只表示媒體可見，不是新的曝光證明。

## 第一輪 exact-head CI 與後續 fixture 修正候選

[CI 37815838415](https://github.com/js051/open-eos-control/actions/runs/37815838415) 在 head `aa7073e9fdbc1e0b0b55fc803a46f4f24d3646b2` 完整終態為 failure；actual checkout 為 `4ab3701dbf0ce078a5913aedc4a141f7ce78b2b9`，將該 head 合入 accepted base `f9e938b`。沒有取消仍在進行的 gates 或盲目重跑。

- Core **253/253**、App **135/135**（CaptureMediaJourney27）及 UI **20/20** 實際通過。新增 typed-error、owner／generation、只讀 retry 與 pure wire fixture 均在此來源執行；不能繼承為之後新 head 的 pass。
- Android JVM／debug build、Windows standalone、simulator、security／workflow helpers通過。未修改的 affected-surface classification 將 Camera Import contract job/schema step列為 **skipped**，不是新 CI pass；本地 contract JVM14分開保留。
- API36 raw XML **357/357**，0 failure/error/skip；artifact11568938012 SHA256 `e98794b160c7ea4937d4bd09bf70f3ae1e05206b529caf8f9b20a60a88f3eb7b`。
- API34 raw XML **356/357**，1 failure、0 error/skip；artifact11568408260 SHA256 `9d026f0a88ec9c4342636bc22ad8f547b4a4b689b1fc24890cf74e4aa369e303`。失敗是 `CameraSafOsJourneyTest.treePickerSavesExactBatchBytesAndCompletedReceipts` 的 root drawer 未關閉，不是本批產品驗收成功。
- Desktop browser在第三個 `bulb-recovery.browser.test.js:303` 失敗；第四條capture-review browser journey和後續Bridge Python CI gate未執行。`ci-complete`因此失敗，PR尚未ready。

### PC manual-retry fixture

失敗log顯示Check again先是visible／disabled，再變hidden。Production-function/render deterministic harness確認：若自動bounded search還在SEARCHING時便把peer切成media-ready，自動下一次read會找到NEW並隱藏retry；visibility不等於manual retry已可操作。

候選只改兩個test files：先等retry真正enabled／reachable，確認原四次自動read耗盡，再切peer ready並保留原manual click、JPEG decode與exact full_press/release assertions；新增manual第五次read計數。沒有產品code、timeout或retry-budget變更。

候選的九個Node module suites全過，capture-review **35/35**；三個Python readback cases與獨立TCP trace通過。後者保留四次OLD listing、第五次NEW、32×24 JPEG decode和exact兩個camera writes。本地browser仍因既有環境限制未重跑；真正DOM結果待新head正常CI。

### API34 DocumentsUI root-selection fixture

原PNG／hierarchy顯示drawer仍開啟且選中舊internal storage。Log中17:38:09.840的單次label touch，與09.838–09.851的root refresh/rebind重疊；沒有synthetic root load。這符合adapter更新干擾touch的機制，但沒有證明具體Android取消分支，也不能歸因於產品或將failure忽略。

候選只調整既有driver：在roots_list內選取包含精確title的enabled clickable row；500ms accessibility-event quiet仍受原15秒pre-tap deadline約束；idle後重新查詢bounds／foreground，僅送一次touch。以整個roots_list消失確認drawer關閉，不將row rebinding時的暫時缺席當成完成。沒有重試Save／Create／Allow，也沒有增加timeout。

四個artifact-backed檢查通過，包含toolbar同名反例與target row暫時消失時drawer仍應開啟。六個journey方法本體、bytes／receipt／grant／cleanup assertions及failure capture與aa7073e逐byte一致。這些是source／evidence檢查，不是picker runtime。2026-10-08 18:18:51–18:21:01 UTC另執行候選 `:app:assembleDebugAndroidTest`，exit0、129.55秒，10 tasks executed／38 up-to-date；source及tool hashes前後一致。新test APK SHA256 `415cd907e305e9fe910d7e7439b3892bec4408a711e7c4a63f979ea20e7af802`。此階段只有compile／assembly；API34／36 runtime仍須新head正常CI。

兩個fixture候選均保留原red證據。下個head必須完成正常完整必要CI；不移除gate、不把舊head綠燈或本地source checks當成新head驗收。PR219的原Compose原因UNKNOWN及frozen release HOLD不因此改變。

## Release Assessment

- 最新真正公開 baseline：[v0.12.0 Development Preview](https://github.com/js051/open-eos-control/releases/tag/v0.12.0)，2026-10-07T02:38:04Z 發布，draft=false／prerelease=true；2026-10-08 17:06 UTC 由 owner fresh read 確認。repo metadata 的 0.13.0 與 frozen unpublished candidate 分開列示，不能拿本地版號冒充已發布版本。
- 建議影響：`patch`，修復既有停止責任、client 錯誤分類與 owner 清理；沒有新增操作能力。
- 使用者價值：避免遺失短控制 stop、晚回應破壞新連線、以及已 ACK 後 status failure 誘發誤重拍。
- 未解 blocker：上述 missing browser／iOS runtime／平台 gates，以及 final exact-SHA CI。iOS source 已完成限定產品整合，但尚未經目前 macOS compile／runtime。
- Frozen v0.13 source `fb4f555ac94779436d4d9d59800bf81b02e68d21`另有明確停止責任缺口：owner的method-hash比較確認其 `_guaranteed_release`、half-press、autofocus與stop_bulb body和pre-fix main相同；still capture也呼叫未保留責任的helper。這是source-boundary證據，不把本批測試算到舊payload。Frozen assets維持原樣；修正版必須依新的accepted tree／version policy產生，不能替換或重新標記舊payload。
- 實機狀態：未驗證。Development Preview／main promotion／release HOLD 均未由本文件解除。
- 本文件不構成合併、升版或發布授權。
