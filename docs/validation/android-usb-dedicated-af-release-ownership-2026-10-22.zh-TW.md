# Android Canon USB 專用 AF：原始 Cancel 所有權

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

## 狀態與範圍

本批為本機 source／離線驗證候選，不推送、不觸發 Actions、不連接實體相機。
Git base 為 `9f86613bc0da445ecf59e9c8786488402af77e4e`；logical baseline 額外採用
已封存的 still／Bulb／half-press 兩個檔案，而不是將它們視為已發布：

- `UsbPtpCameraBackend.kt`：`c8d651d69de777c1c4bb77fe261a1544f3cdeed0a860b7f83df5e10960861669`
- `UsbPtpCameraBackendTest.kt`：`e232775cb869b74071b94566e17261fc2963419603d5ee8e713b01aef371605a`

本批只改上述 production、test 與本文件三檔。先前 pending 11-file delivery 與
另立的 iOS session-mode-memory 三檔均保持分離，不由此擴大發布或 Apple 驗證範圍。

## 已確認的 caller 矛盾

原 dedicated AF 路徑在 `DO_AF` 後以 `finally` 發送 `AF_CANCEL`，但沒有保留失敗
Cancel。Start 回覆遺失不代表命令未生效；原 finally 的 Cancel 也失敗時，只會在
原錯誤附上 suppressed failure，且 USB 繼承 unsupported `retryAutofocusStop`。
單獨 Cancel 失敗則拋 raw PTP error。這兩者都無法啟用既有 VM 的 typed
`AutofocusReleaseException` → `RELEASE_FAILED`／保留 FOCUS busy 的恢復流程。

Repository retirement 的 pre-close `retryAutofocusStop` 是 `runCatching`，所以
不能只補該 override 就聲稱 replacement admission 安全。`close` 必須再次處理
保存的 release，並使未確認結果以 typed failure 傳到現有 retirement revision／
warning gate；該 gate 在建立下一個 backend 前拒絕新連線。

以上是 source 可達的 ownership／UI-state 矛盾，不是實體鏡頭持續運轉的觀察。

## 最小修正

- 重用未修改的 `HeldAutofocusSession`；在任何 `DO_AF` 前記住捕獲的原始
  `PtpSession` 的 `AF_CANCEL` closure。
- 成功 Cancel 才清除責任；失敗保持 typed primary error，原 Start／cancelled
  operation error 仍為 suppressed evidence。既有 helper 的 NonCancellable Stop
  不變；未 release 前阻止另一個 DoAF，明確 retry 只發 saved Cancel。
- USB 實作 `retryAutofocusStop`；不發新的 DoAF、不新增成功對焦回饋。
- close 在原 shutter cleanup 後重試 saved AF Cancel。保留原 shutter primary
  precedence；若兩者皆失敗，AF 為 suppressed，shutdown failure 亦保留。單獨 AF
  failure 仍為 typed primary，避免清空 session 後被當成乾淨 retirement。
- 原 dedicated-AF 不需 remote-release operation 的 capability 合約、fallback
  half-press、hold 時間、事件讀取、still／Bulb 物件回讀與 ACK／UNKNOWN 邊界不變。
- 不新增控制框架，不修改 VM、Repository、Held helper、一般 timeout 或其他 transport。

## 凍結驗證設計

原 owned-release 188 個方法完整保留；另加入既有 `HeldAutofocusSessionTest` 5 個方法，
共 193 個原方法；新增 8 個實際 USB backend／repository／VM 方法，candidate 201 個
唯一 method IDs。不是 188 與其 subset 重複相加。原 108 個 thumbnail 相關方法檔案保留，
不冒充在此 narrow gate 重新執行。

Baseline 使用相同新增 test fixture 與 sealed 舊 production，選定 8 個方法：

| 類別 | 方法／預期 assertion marker |
| --- | --- |
| RED | `canonDedicatedAutofocusLostStartAndFailedCancelRetainsOriginalStop` / `USB_AF_LOST_START_OWNER` |
| RED | `canonDedicatedAutofocusAcknowledgedStartWithFailedCancelBlocksNextStart` / `USB_AF_ACK_STOP_OWNER` |
| RED | `canonDedicatedAutofocusFailedRetirementBlocksReplacementBackend` / `USB_AF_RETIREMENT_BLOCKS_REPLACEMENT` |
| RED | `canonDedicatedAutofocusViewModelFailedStopKeepsFocusBusyUntilRetry` / `USB_AF_VM_RELEASE_FAILED` |
| control | `canonAutofocusFallsBackToBalancedHalfPressWithoutDedicatedOperations` |
| control | `canonDedicatedAutofocusDoesNotRequireRemoteReleaseOperations` |
| control | `canonDedicatedAutofocusCancelsAfterStartFailure` |
| control | `canonDedicatedAutofocusCancelsCameraWhenCoroutineIsCancelled` |

另外四個新 witness 覆蓋 coroutine cancellation 與 Cancel 失敗、close 成功 Stop-only、
close AF＋shutdown 雙錯誤，以及 Start ACK 遺失但 Cancel 成功的原錯誤保留。
測試只用 synthetic scripted USB response；不對真照片或相機操作。

兩 variant 各 fresh compile 完整 77 data＋43 UI production sources，以及完整指定
測試。不以舊 application jar、片段 helper 或 fake VM 取代當前 caller；驗證 runtime
class origins、JSON priority、exact method roster、無 skipped／assumption、每個 baseline
failure 的 assertion 類別與 marker。Compile/setup failure 不是 RED。

執行預算為一次 hard 600 秒（590 秒工作＋10 秒停止／封存），2 CPU，取樣總 process-tree
RSS ≤768 MiB；此 RSS 是觀察到超額時停止，不是 kernel hard cap。磁碟 admission 要求
2 GiB floor＋512 MiB reserve，stage 保留至少 32 MiB，執行中監測 floor。

## 實際結果

原 gate 於 05:33:20 UTC 啟動。兩 variant 的完整 120-source compile 均通過；
baseline 8 方法得到精確 4 個預期 assertion RED＋4 個原 controls PASS。
初版 candidate 實際 201 方法為 200 PASS／1 個新增 assertion FAIL，原 193 全部 PASS。

失敗的新 VM witness 預期最終 `focusFeedback=FAILURE`，實際為 `null`。既有
`runCamera` finally 會 reconciliation；fixture 原本停用 Live View，故原
`clearLiveViewPresentation` 同步清掉短暫 feedback，但保留 `RELEASE_FAILED`、
FOCUS busy 與 error。只修正該新增 assertion：observer 必須實際見過 FAILURE，
而最終 presentation 為 null。原所有權／Stop-only／不得 ACCEPTED 或 SUCCESS 與
原 193 方法不變。初版錯誤結果完整保留，不當成 production RED 或成功。

原窗內的 focused continuation 準備時間低估，啟動 compile 時已比先前測得的
compile 時間窄；guard 於 05:43:11 UTC 正常終止（SIGTERM 143），原絕對窗耗
591.037 秒，修正後新 runtime 未執行。沒有延長原 timeout、改 expected RED 或
宣稱 composite PASS。兩次 preparation-only 檢查錯誤（command-key 拼接、將既有
重複 CPU flag 誤當 source duplication）亦保留；未變更原 compiler command。

05:44:07 的同批補充通知允許最後一個 hard 300 秒完整剩餘驗證窗。全部靜態準備與
seal 先完成再起算；只重用先前 source-sealed 的兩個 data.jar，兩 variant 重新編譯
完整 UI／tests，再執行 baseline 8 與 candidate 201。資源限制不變，不推送或 CI。
最後窗已於 `2026-10-22T05:46:29.645621+00:00` 啟動，`2026-10-22T05:49:56.860178+00:00` 終態 PASS，
耗時 **207.215 秒**。兩 variant fresh UI／tests compile 均 exit 0；
baseline **8 方法＝精確 4 named assertion RED＋4 controls PASS**；candidate **201／201
PASS**，0 ignored、0 assumptions，逐項 method IDs 對帳。這是修正後同一次完整 201 run，
不是把原 200 與單例結果相加。193 原方法與 8 新方法全部實際通過。

本次最高取樣 process-tree RSS **740,312 KiB**，
未觸資源或時間 guard；結束磁碟 free **3,089,776,640 bytes**。
原 600 秒窗（實際 wall 591.037 秒）與補充 300 秒窗分列，不把前者消失或歸零。
全部 stage 實際執行合計 **610.186 秒**，
不等於 source／review／等待等整體 wall time。封存原未成功的每一份 report 與編譯 log。

只驗證 Linux JVM 上列出的 actual source＋scripted transport／VM／repository，
不是完整 Android APK build、device instrumentation 或實體 USB AF 驗收。

## 限制與後續

即使 synthetic regression 全綠，也不證明實體 AF 停止、OS USB interruption、任意並行
直接 backend 呼叫或整體三平台功能已完成。未 confirmed release 的 retry 保存原 session，
不是重新連線後把 Cancel 送往新相機。外部交付／CI 仍獨立待決；本文件不宣稱批准或完成。

## 2026-10-22 16:52 組合來源驗證

AF候選與既有runtime-profile／preview-owner候選、iOS session-memory在同一34ddb8基底組成18檔本機候選；原各批sealed來源及RED保留。77個data檔（含本backend）與44個UI/support檔均fresh compile，整個12-class集合 **283 distinct／301 invocations PASS**，包含原AF/recovery選集，不是將兩份歷史綠燈相加。單一900秒窗實際320.962秒，沒有外部推送或CI。

結果與精確限制見[整合驗證記錄](android-runtime-and-preview-owner-2026-10-22.zh-TW.md)。本機真source＋scripted transport證據不等於實體AF停止；標準Gradle與裝置證據仍缺，UNKNOWN／原owner Stop-only／禁止重新press規則沒有放寬。
