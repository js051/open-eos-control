# iOS Photo/Video 的前次照片模式僅屬於原 session

## 2026-10-22 原 dev 整合驗證（來源計畫，終態見下一節）

原 11 檔及其 coroutine parity 修正現已發布至 `34ddb8c8f39daf3df96c2e6fa3da12f4751060c2`。
本節以該 commit 為新的獨立六檔整合基底；下方原獨立批次紀錄按當時狀態保留，
其中「pending 11」不是目前交付狀態。這次只整合既有 AF 與 iOS session-memory 修正，
不把本批當成原 11 檔的隱含擴張；外部 push／Android／Apple CI 尚未執行。

Android 保留現有八項 Shutter parity 修正及六項負控制，再針對新 AF 的四個 direct-cause
檢查建立真實 backend recovery-chain 反證及六項嚴格拒絕控制。預計 affected Android
213 個唯一方法 fresh run，另 224 個未變方法只保留 `34ddb8` 同源 full-host 證據，
437 是兩者互斥 union，不是 437 個本機新跑成功。未取得結果前不稱 PASS。

iOS 仍只有 source／syntax parse；75 個原方法 body 和八個新增 AppState 方法均保留，
83 個 native 方法本機 **NOT_RUN**。四個 source-predicted RED 不冒稱動態 RED。
本次 hard720秒含診斷／shutdown／seal，2 CPU／768 MiB 取樣 RSS，2 GiB free floor 與
512 MiB admission reserve；先前各批的執行和失敗紀錄不歸零、不被本次覆寫。


## 2026-10-22 六檔整合的本機終態

本機整合 gate 已於 **10:51:52.858 UTC** 終態 PASS，外部交付／Android host／Apple CI
仍未執行。候選基底是 `34ddb8c8f39daf3df96c2e6fa3da12f4751060c2`，不是回退到原 AF
獨立候選的舊 client；保留目前已發布的 strict-card 與 Shutter parity 修正。

- 真實 owner baseline：current dev 的 source-sealed 77-file data jar，配合新鮮完整
  UI/support 與 tests，八方法得到精確四個原 assertion marker RED、四控制 PASS。
- 真實例外形狀 probe：四個原 direct cast 均在凍結行號發生 ClassCastException；
  觀察到 exact AutofocusReleaseException copy → original AutofocusReleaseException →
  PtpResponseException，且 operation=AF_CANCEL、response=GENERAL_ERROR。
  原 Start／shutdown 的 suppressed object identity，以及取消的單一 cancellation
  type/cardinality 逐項核實；不是把這四個 harness shape failure 稱為產品 assertion RED。
- 另有一個不進 repo 的 closed-owner probe，走實際 failed-close→重開 B→saved A Stop。
  觀察到 AF copy → AF → exact closed-session PtpProtocolException；原 direct-cause assertion
  精確 RED。這是預先定義的兩向比較之實際 copy 分支，不是測完後變更 expected result。
- 最後單一 candidate run **213／213 PASS**，0 fail、0 skipped／ignored、0 assumptions；
  包含全部 owner／Stop-only／retirement／VM／no-new-Start／A與B零傳送尾段，以及六個新
  AF strict-helper 拒絕控制。現有 123 USB method bodies、六個 Shutter 控制均 byte-identical。
- 另 224 個未改的 CcapiClient／setting 方法不在本機重跑；保留來源與依賴相同的
  `34ddb8` host39759683302 success 與既有418 local證據。213＋224＝437 是互斥 evidence
  union，不是437本機 fresh PASS。公開 host終態未另下載numeric count，本文件不推算它。
- iOS 原75 body＋八新方法來源檢查，以及 ordinary/DEBUG fixture兩種完整 syntax parse
  均 PASS；**83 actual AppState runtime NOT_RUN**，沒有 Apple SDK typecheck／UIKit證明。

最初 gate10:42:42.324開始，因多帶未選用的 ShootingSettingFixtureLoader 而 compile失敗，
**35.774949秒**紀錄保留，非RED。source確認它只屬 retained setting suites，selected213與
完整 production closure無呼叫後，只從compile命令移除該多餘 helper，不改測試或替代來源。
10:45:39.648以新空 namespace續行 **373.209696秒**；原絕對deadline10:54:42.324未重設。
兩段實際gate執行合計 **408.984646秒**，原整體wall窗 **550.534秒**，包含中間診斷。

每份 UI/support variant 均完整新編 **43檔＝40 UI＋1 USB diagnostics＋2 import-contract**、
七個 whole suites、一個必要 whole CameraFocusMetadataTest helper及JUnit observer。
Candidate另外新編77 data檔。只 baseline重用有完整來源／依賴／artifact SHA綁定的data jar，
沒有舊UI/test overlay、function slices或production substitute。最初將43誤標純UI的preflight
分類錯誤，以及probe simple/full exception-name verifier修正都在runtime前完成，未當測試結果。

外層 supervisor以710秒TERM＋10秒KILL維持原hard720；2 CPU、768 MiB取樣process-tree RSS、
2 GiB floor與512 MiB admission reserve未放寬。最高取樣 **746,620 KiB**，未觸guard；
終態 free **3,032,821,760 bytes**。RSS不是kernel硬上限。全部原失敗與前批消耗獨立保留。
只有本節在gate後補結果，production與test bytes未再變。

此結果不是clean Android Gradle build、physical USB AF／相機Stop驗收、Apple native結果，
也不宣稱既有picker crash永久修好。外部發送及新CI仍依另行範圍確認。

## 範圍與目前結論

本批以 `9f86613bc0da445ecf59e9c8786488402af77e4e` 為獨立來源，只改
`CameraAppState.swift`、`ControlResponseOwnershipTests.swift` 與本文件。
它不包含另行待交付的 USB、雙卡選擇及取消測試修正，也沒有推送或啟動 CI。

來源可到達的問題是：`lastPhotoShootingMode` 只在使用者離開 Photo 時，自目前
非 `moviemode` setting 的 `value` 記下觀察值，卻跨過 session 退役繼續存在。
它不是設定頁可編輯的偏好，也沒有 UserDefaults 儲存項目。若 A 的觀察值為
`Manual`，使用者切到 Video 後離開 A，B 以 `Movie` 開啟且公布
`[Manual, Movie]`，B 上新的 Photo/Apply 會將 A 的 `Manual` 選為 B 的寫入值。
同一 B 若沒有 A 的歷史，既有行為反而是 local fallback，不發出該設定寫入。
UI 這次選的是 Photo，並沒有重新選擇 Manual。

這是 AppState 的跨 session 選值問題，不是已在實體相機觀察到錯誤設定。
嚴格的 CCAPI `moviemode` on/off 分支不使用這個偏好；受影響的是既有通用
Photo/Video 控制在 Bridge `shootingmode` 等能力上的 fallback。
gphoto2 的既有 `autoexposuremode` → `shootingmode` 宣告及 choices 透傳，使這個
能力形狀屬於既有合約；合成選項不宣稱任何特定相機必定公布相同內容。

## 最小修正與保留邊界

只在既有 `session.didSet` 退役界線清空這個觀察值。現有 AppState session 賦值
只有受 `session == nil` 限制的 new connection，以及失敗、取消、斷線和離線預覽的
`nil`；refresh、設定 readback、同 owner 的 Photo/Video roundtrip 都不重設 session。
`ConnectingSessionRetirement` 自己的 `session` 欄位不是此 AppState property。

因此保留同 session 的 Photo → Video → Photo 恢復；不新增全域偏好、設定推測、
重試或 transport。原選項驗證、draft/session ownership、錄影/Bulb/busy admission、
UNKNOWN ACK、read-only recovery 與完整回覆後才能消耗 draft 的規則都不變。
取消或遲到回覆仍只能更新其原本的 bounded record，不能替 replacement 發出命令。
沒有可用 photo 觀察值時，原 local fallback 仍只是 app 控制模式，並不宣稱已改變相機
的物理拍攝模式；本修正沒有改寫這個原有能力不足時的行為。

## 驗證分層

新增八個測試必須呼叫真實 `CameraAppState.connect/setCaptureMode/disconnect` 等入口，
透過既有注入 `CameraHTTPTransport` 的真實 `DesktopBridgeClient`，使用合成資料與受控
continuation，不開 socket、不使用相機，不以純選值 helper 代替 caller。
原 `ControlResponseOwnershipTests` 26 個方法和 `CameraAppTests` 49 個方法 body 保留。
目標 roster 是 75 個原方法加 8 個新方法，共 83，不是本機已執行的數字。

四個 source-predicted baseline 反證涵蓋不同 B、同相機新 session、取消開啟後的下一
owner，以及 A 遲到回覆後 B 的新 Photo request。四個 controls 保留同 owner roundtrip、
無歷史的全新 Movie owner、明確 moviemode on/off，以及 UNKNOWN ACK 不變成可 replay
的成功。這些反證均未在本機執行，不稱為 runtime RED。

| 新增方法 | 性質 |
|---|---|
| `testCaptureModeMemoryCannotCrossToDifferentCameraSession` | source-predicted R1：A → 不同 B |
| `testCaptureModeMemoryCannotSurviveSameCameraFreshSessionReconnect` | source-predicted R2：相同相機身分、新 session |
| `testCaptureModeMemoryCannotCrossCancelledOpeningIntoFreshOwner` | source-predicted R3：取消 B 開啟，完成原清理後 C |
| `testDelayedCaptureModeCompletionCannotRestoreRetiredMemoryIntoReplacement` | source-predicted R4：A late ACK 只保留原 record |
| `testSameCaptureModeOwnerRestoresItsExactPhotoValueInRequestOrder` | control：同 owner Movie / Manual 各一次，順序精確 |
| `testFreshMovieOwnerWithoutPhotoHistoryDoesNotPostAnArbitraryPhotoMode` | control：沒有 history 不任選照片模式 |
| `testMovieModeBranchUsesOnlyOnOffDespitePreviousPhotoModeOwner` | control：AppState 的 moviemode key 分支，不宣稱新 CCAPI transport 測試 |
| `testCaptureModeUnknownACKStaysUnknownAndReadOnlyRecoveryNeverReplays` | control：response loss 後 GET matched 仍 UNKNOWN，不 replay |

四個 R marker 均對 replacement owner 的 mode request log 作精確空集合斷言，不只是看
畫面顯示值。相同相機反例固定相同 model/serial/choices，另核對不同 Bridge session endpoint
和 AppState UUID。新 fixture 的 optional mode 預設為 nil，保留原 ISO 回覆；新模式的 GET、
POST outcome 及意外 legacy POST 都記錄 exact method/path/key/value，不能漏掉 fallback。

既有 Debian Swift 工具能檢查完整修改檔案的語法和來源完整性，但沒有 Apple App SDK。
過去 picker 的 UIKit substitute 或 Core injected-transport gate 不能證明此 AppState。
本批不抽掉 AppState、不加 UIKit/SwiftUI 假類型、不新設環境；八個新增 AppState runtime
方法及整體 83 方法均明列 **NOT_RUN**。語法 parse 也不是 Swift API/typecheck 證明。

本機 static gate 實際於 2026-10-22 04:54:02.372–04:54:04.706 UTC 執行，
總耗時 2.335 秒；結果為 **PASS_SYNTAX_AND_SOURCE_ONLY**，不是 AppState 測試通過。

- ordinary 與 `DEBUG/OEC_REQUIRE_SIMULATOR_E2E` 兩種完整檔案 syntax parse 均 exit 0。
- 前後來源檢查均確認原 26＋49 方法 body byte-identical，精確新增八個方法。
- 生產變更只是一行 reset 與兩行說明；`git diff --check` exit 0。
- 原 UI 測試與另批待交付 11 個檔案 hash 未變；沒有混入本批。
- 原生測試執行數 **0**；八個新方法及 83 個目標方法維持 **NOT_RUN**。
- 2 CPU affinity，峰值取樣 process-tree RSS 86,688 KiB；終態可用磁碟
  3,293,523,968 bytes，沒有任何 guard stop。

原預算上限 180 秒（含終止封存）、512 MiB 取樣 process-tree RSS、2 GiB free floor
及 64 MiB admission reserve 均未放寬。RSS 是取樣的 gate 與存活 descendant 總和，
同一 shared page 可按 process 重複計入；不是作業系統保證的硬記憶體上限。
來源/test bytes 在 gate 後未改；只有本文件補上實際結果，原 frozen manifest 保留。
不改原 UI 測試、Apple timeout 或 workflow。

## 後續驗收仍待執行

實際候選 AppState 與其相依 Apple API 仍需已授權的原 Apple 流程。既有完整路徑約
40–50 分鐘加排隊，App job 上限 55 分鐘、App/UI step 上限 45 分鐘；本批沒有啟動它。
一次候選全套綠燈也不等於已執行 frozen-baseline RED，更不清除既有 picker crash 問題。
真正的 baseline/candidate AppState 配對執行需要另外可用且已授權的 Apple executor，
不能把 Linux syntax 或來源反例冒稱為該結果。

## 2026-10-22 16:52 十八檔整合來源核對

本三檔在同一34ddb8基底加入已告知的18檔本機候選，production與test bytes保持原sealed版本。整合gate再次完成兩種完整Swift syntax parse、原49＋26共75個method bodies逐byte核對、新增8個method IDs及原UI whole-file hash核對。這些是來源／parse證據，**83個native AppState方法仍全部NOT_RUN**，原四個source-predicted RED未被冒稱實際執行。

[整合驗證記錄](android-runtime-and-preview-owner-2026-10-22.zh-TW.md)列出本機Android組合集合與資源，不能用其綠燈填補Apple SDK／UIKit／AppState缺項。外部dev與Apple流程仍獨立待確認，沒有改原UI assertions、time limits或picker結果定義。
