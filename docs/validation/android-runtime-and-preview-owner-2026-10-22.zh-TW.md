# Android 測試 runtime 收斂與媒體預覽責任抽離

基底：`34ddb8c8f39daf3df96c2e6fa3da12f4751060c2`。本機有限接續已完成：87 distinct／105 invocations PASS，新增 witness 的接收邊界假設已由保留 RED 與對照證據釐清。正式 Gradle、原 20 個 whole-class 方法、APK、裝置及後續 CI 仍為 NOT_RUN。

## 前批實際結果與失敗保留（2026-10-22，接續前）

本機補充 gate 終態：**PARTIAL_CANDIDATE_ASSERTION_FAILURE**，2026-10-22T15:37:31.460112+00:00。原起點 15:18:59.947 UTC，絕對上限 15:38:59.947 UTC；累計實際窗 1178.031 秒，所有 setup／診斷／guard stop 保留，沒有重設預算。

- Baseline：78 distinct methods／92 invocations PASS，精確 method IDs 全核對。
- Candidate：86 distinct methods／104 invocations 全部執行；85 distinct／103 invocations PASS，1 個新增 runtime witness FAIL，沒有 ignored／assumption／skip 補數。
- 原 `CameraViewModelPreviewTest` 完整 20 方法仍 **NOT_RUN**；完整 106／124 目標沒有全數驗證。
- 設定負控制：4 方法中精確 2 個預定 assertion RED＋2 個正控制；這是 flags 差異，不是產品故障。
- 遷移後補充 diagnostics：still 3 PASS；focus gate exit 1，失敗 receipt 保留，尚不能宣稱遷移驗證完成。Python helper 8 controls PASS。皆不加進 86 個產品／runtime-contract方法。
- 原 bridge Ruff 命令 `ruff check --no-cache . ../scripts/validation` PASS。
- 正式 Gradle、Gradle resolved manifest 比對、APK／device／實體相機均 **NOT_RUN**。

### 前批新增 runtime witness 的保留失敗

`AndroidUnitRuntimeContractTest#suspendedReleaseFailurePreservesTypedPrimaryAndSuppressedIdentity` 在 assertion「The exact original ambiguous press survives as suppressed evidence」失敗。其前置 typed-wrapper count、wrapper type 和 exact original release-primary identity 檢查通過；觀測到 primary cause chain 的直接 suppressed 集合為空。沒有改原安全斷言或 production helper。這是新受控 interleaving 的真 assertion failure，但目前無法判定是新 witness 的接收邊界設計，還是 production/coroutine recovery 的 evidence-loss 路徑；不得改 expected marker 或直接吞掉錯誤。所有原 78 方法與四個新 preview caller controls 通過，不代表正式 Gradle 或此新 witness 通過。

### 保留的失敗與環境邊界

1. 初次 Ruff 從錯的工作目錄啟動，產生 54 個非原 bridge policy 診斷，0.608 秒停止。改回既有正式命令／工作目錄；新 Python 檔的 import 排序與換行依該 policy 整理，非 import AST 相同、import 集合相同，不宣稱排序前後完整 AST 相同。
2. 一次 setup seal 未更新而停止，沒有進入新測試；原 receipt 保留。
3. 初次 baseline 是 84 PASS／8 Robolectric SDK setup failures，均為唯讀 home lock file，不是 assertion RED。恢復先前已驗證的 offline／非 preinstrumented／本地 SDK 參數；SDK jar bytes 與原已封存 android-all 完全相同，未下載或改 home 權限。之後原 78／92 全 PASS。
4. 重複 candidate data compile 因 sampled process-tree RSS 786,436 KiB 超過 786,432 KiB guard 而 exit 143；此 stop 不算產品 RED。已證 baseline/candidate 77-file data 命令僅輸出路徑不同，後續使用本批剛成功的同來源／同工具／同 classpath jar，SHA 綁定。Candidate owner／VM／44-file UI-support 與全部所選 tests 仍 fresh compile，編譯 heap 降至 384 MiB。**不宣稱兩份 data variant 都 fresh compile 成功。**

整批最低觀測 free space 3,021,516,800 bytes，未跌破 2 GiB。768 MiB 是取樣 guard，不是 kernel 硬記憶體限制；上述 4 KiB 超界被如實終止。原 98 個計畫回歸方法 body 全保留，另外同類原檔未選方法也保持原樣；不把 source preservation 算 runtime PASS。候選 12 檔與獨立待批 6 檔隔離，沒有 push／CI／安裝／下載／KVM。

所有 exact fresh／NOT_RUN IDs、四負控制結果、兩遷移 diagnostics、先前失敗及 source/dependency hash 由本批 evidence roster／receipt 封存。正式 Gradle 仍是唯一正式接受門檻，這次只能證明補充 closure 的具體邊界。

## 目的與實際刪除

先減少既有重複配置，再搬一個完整責任，不新增平行 build system。`run-android-still-transport-host.py` 和 `run_android_focus_ack_host.py` 原本各自持有 artifact verification、compiler/runtime jar 清單、Java 解析與 flags；兩份重複程式已移除，委派到同一 `android_host_runtime.py`。原抽取邏輯、fixtures、baseline ref、RED markers 與 timeout 保留。

小型 `android/gradle/unit-test-runtime.properties` 只描述測試 Java 17、assertions、coroutine debug/recovery。Gradle 原本 assertion-enabled 的預設會啟用 coroutine debug-auto 與 recovery；現在明列原語義，不改 production app 設定。共用 helper 不安裝、不下載、不選用未知工具。編譯器自己的 coroutine 1.8 和產品測試的 coroutine 1.9 仍分離，不混入同一 runtime。

首批新增四個標準 source-set JUnit 方法，接續另增一個成對觀測方法，直接使用原 `ShutterReleaseSession`，檢查實際 Java／依賴來源、flags、suspend 後 typed primary／原始 suppressed identity，以及取消後原 owner 的 Stop-only。標準 Gradle Test task 產生實際 resolved manifest，JUnit 再核對 loaded origin；補充 runner 不能冒稱自己就是 Gradle。缺正式 manifest 時固定記 `GRADLE_COMPARISON_NOT_RUN`。比較少數 jar hashes 即使一致，也不是完整 Android／AGP／resource 等價。

## 預覽責任邊界

`CameraMediaPreviewOwner` 接手原 request identity、Job、cancelled、generation／connection witness、預覽發布及 stream 退休。`CameraUiState` 仍是唯一 snapshot；VM 保留 operation registry、lazy admission、session generation、state revision、CONNECT／disconnect joins 和安全命令。沒有新 coroutine scope、事件匯流排、通用 cancelAll 或 retry 架構。

接點涵蓋 viewer close、離開 MEDIA、gallery refresh、刪除／事件 CAS 成功後退休、disconnect／replacement／onCleared 及原 upload-reset stream façade。background 原本不取消預覽，保持不變。

保留的具體安全契約：

- 只取消 CCAPI／Bridge HTTP image display read；USB transaction 與影片 allocation 不被套用此取消規則。
- request 先建立 ownership，再發 StateFlow，重入 dismissal 可擋住稍後 lazy admission。
- cancellation requested 不等於 cleanup completed；原 MEDIA Job 完成前不能承認 idle 或開始 repository retirement。
- 舊 finalizer／CAS retire 只處理捕捉的原 owner，不能清除 successor。
- 晚到影片來源失去 viewer 時仍 close；取消後 IOException 抑制只留在 display read。
- 所有錄影、Bulb、AF、Stop、release、ACK、mutex 與不確定結果政策不搬動。不得把 status 或取消當成命令成功。

## 原完整計畫與實際本機差額

重構 baseline 是原來源的正常 PASS，不是捏造產品 RED。

| 集合 | Distinct methods | Invocations | 狀態 |
| --- | ---: | ---: | --- |
| 原 preview／VM／recording／release／八項 USB 回歸 | 98 | 112 | 78／92 PASS；20 方法 NOT_RUN |
| Candidate：原 98＋preview 4＋runtime 4 | 106 | 124 | 85 distinct／103 invocations PASS；1 FAIL；20 方法 NOT_RUN |
| 遷移後 still／focus extracted diagnostics | 3＋13 | 3＋13 | still 3 PASS；focus 失敗待分類，非正式驗收 |
| 共用 helper 配置控制 | 8 | 8 | PASS，非產品方法 |
| 關閉 assertions／debug／recovery 的受控設定反證 | 4 | 4 | 精確 2 RED＋2 PASS，非產品故障 |

原 preview 14 個方法 × CCAPI／Bridge 兩參數，新增四個方法也各跑兩參數；其他方法單次。既有 legacy-bridge 方法在 CCAPI 參數直接 return，保留此限制，不能把那次 invocation 當有效 transport 證據。原八個 CI failures 已由 `34ddb8` 修正測試環境語義，本批保留現行原方法，不拿舊錯 assertion 當產品 baseline。

受控設定反證預定只讓 `runtimeEnablesAssertionsAndCoroutineRecovery` 出現 `UNIT_RUNTIME_ASSERTIONS_ENABLED`，及 suspend copy witness 出現 `UNIT_RUNTIME_RECOVERY_COPY_REQUIRED`；其餘兩個 runtime 方法是正控制。compile、missing jar、fixture、資源錯誤不算 RED，遇到非預期原因停止分類，不改預期來求綠。

四個新 preview caller controls：background 保留 pending display read、實際 `ViewModelStore.clear`／onCleared 先 join cleanup、實際 deletion/event caller 只退休被移除來源、重入 observer 發布 successor 後舊 CAS 不得關錯來源。事件控制只反射呼叫既有 suspend `refreshEventMedia`，不加產品測試入口或 timer。

## 本機邊界與尚未完成的正式證據

所有變動的 production owner／VM 已以完整來源編譯，baseline/candidate 分離，沒有舊 app class overlay。原 `CameraViewModelPreviewTest` 的三個 gallery-admission cases 依賴 Compose 檔內的 `canPreviewGalleryItem`，不在本機 non-rendering closure。為避免新增抽取／stub 或改寫測試來湊數，**完整保留該 class 的 20 個方法，全部標示本機 NOT_RUN、待正式 Gradle**。未建立新的 predicate fixture；最初僅在準備程式中的方案已撤回，未執行。

因此本機可執行的 whole-class 集合是 baseline 78 distinct／92 invocations，candidate 86 distinct／104 invocations。原完整目標仍是 98→106 distinct、112→124 invocations，缺少的 20 個原方法不能以其他 controls、保留 source 或不同測試補算成 PASS。

目前只有既有 Java／Kotlin／JUnit／Robolectric closure，未核得完整 Gradle 8.7 distribution／SDK35。因此正式 `:app:testDebugUnitTest` 與 `assembleDebug` 不在本機執行；runtime flags 相同不能填補這個缺口。也不宣稱實體 USB／EOS、Android device、Apple 或 UI 畫面驗收。

本機一次 serial hard 1,200 秒，2 CPU、sampled process-tree RSS 768 MiB、2 GiB free floor，起跑另留 768 MiB reserve；所有 runtime、失敗診斷、清理與 seal 都計入，不歸零。實際結果與全部失敗見上節；沒有以重啟歸零或補充 controls 填補正式 Gradle 缺口。

## 後續仍用同一目標收斂

正式門檻只有標準 Gradle。未取得正式 manifest 前不宣稱環境對齊完成。後續若取得對外交付授權，這 12 檔會依既有路徑觸發 Android host 約 10–20 分鐘，以及因 `scripts/validation/**` 觸發 Apple 約 40–50 分鐘，另加 queue；不是 Android-only。不改 workflow，不啟動 device/KVM。

其他歷史 runner 尚未全部遷移，保留其歷史 evidence，不重寫過往失敗。後續先以正式 tests 取代重複 active path，其餘再共用配置；下一個 controller 責任只在 ownership 與回歸完整時漸進搬移。獨立 AF／iOS session-memory 六檔不混入本批，原 Apple picker 與 native-color acceptance 也沒有因此解決。

## 15:50 已告知接續的實際結果

本次四檔 source followup 沒有改 production helper／backend／repository／owner／VM，也沒有改其他原回歸方法。Gate wrapper 於 2026-10-22T16:17:16.878640151Z 起跑，終態 16:23:09.754 UTC；含 setup、診斷與修正的原絕對窗累計 **352.875 秒**，在一次新 hard 600 秒內完成。前批 1,200 秒窗與全部失敗保留，並非重置前批預算。內層 588 秒 alarm 覆蓋非 child phases，外層 TERM 590＋KILL 10 覆蓋整個程序。

### 真 caller 與額外 await 的實證

先以保存的完整舊 runtime test fresh compile，原失敗精確重現。再 fresh compile 完整 44-file UI/support、8 個 whole test classes、原 helper 與同一 observer，使用未改的 production ShutterReleaseSession 和受控 suspension，成對比較 launch/catch 形狀與額外 async/await receiver：

- 三個 helper caller 都保有恰好一個 direct suppressed，且與原始 ambiguous press 為同一物件；exact original release 仍為 primary root。
- launch/catch 的 caller 與 receiver 為同一 throwable。
- 額外 await 得到不同 typed throwable，直接 suppressed 為空；release primary identity 仍保留。
- 原 witness 的 13 個 assertions 全部實際執行，唯一失敗仍是原 receiver suppressed assertion。兩個 paired modes 與取消控制均保有原 owner、只一次 press、禁止重 press、OFF-only recovery 與清除責任。

這是 **真 helper＋受控 caller 形狀** 的動態證據，不是完整 CameraViewModel error-path 或標準 Gradle 的證明。現有 VM/backend 回歸另在完整所選 candidate 中執行。不能以此聲稱 coroutine 任意邊界都保留 suppressed，也沒有發現足以要求改產品 release helper 的證據。

只有上述條件全部成立後，於 16:18:51.521 UTC 做預先聲明的一行 probe 修正：suppressed 檢查來源從額外 receiver 的 cause chain 改成已捕捉的 helper caller cause chain。外層 typed wrapper、exact release root、suppression purity、原 13 checks 與所有 Stop-only assertions 保留；標準 JUnit aggregation 會報每個安全失敗，沒有吞錯。修正後 whole UI/tests 再 fresh compile，沒有沿用舊 app/test overlay。

### 結果分帳

- 最終 candidate：**87 distinct methods／105 invocations PASS**，逐一 runtime ID、discovery、started/finished、無 ignored／assumption 全核對。原 78 個已選 methods＋4 preview controls＋4 runtime contracts＋1 paired method，不與其他 controls 重複加總。
- 受控 legacy flags：原 4 方法精確 **2 expected assertion RED＋2 PASS**；仍是配置反證，非產品故障。
- Focus current diagnostic：原 **13 fixture methods PASS**。Current guard 改用已提交 focus-ack-receipts-2026-10-17.json 的固定 whole-file SHA、完整 baseline identity、三個不變方法和 REQUEST_OK 原紅／綠 SHA。既有 inverse predicate 必須回到原紅 hash；不是創造 baseline。Baseline mode 仍要求原 Git ref，本地缺 ref 故仍未重跑。
- Python helper controls：原 8 個 bodies 不變，加 5 個 receipt controls，共 **13 PASS**，exact method IDs 核對。
- 原 normal bridge Ruff 命令 PASS。首次 final Ruff 因新 missing-receipt control 的一個 SIM117 nested-with style finding 停止；原 log／247.301 秒 receipt 保留。只合併等價 context managers，normalized AST 相同、原 8 class bytes 相同；在同一絕對窗內重跑 Python 13＋原 Ruff，未重跑 Kotlin 或重置時間。
- 前批 baseline **78／92 PASS** 與 still diagnostic **3 PASS** 只在來源／工具／profile／classpath／artifact hashes 不變時 retained，不列 fresh。77-file data jar 為前批實際成功且 sealed 的相同 artifact；本次沒有再假稱 data fresh compile。

本次最高取樣 process-tree RSS **723,008 KiB**，最低 free **3,372,331,008 bytes**。2 CPU、768 MiB 取樣 guard、2 GiB floor＋768 MiB admission reserve 保持。原稿、原 RED、觀測 phase、條件修正、SIM117 stop、所有 commands／精確 roster／class origins／hash receipts 均封存。

### 沒有被綠燈填掉的缺項

原 CameraViewModelPreviewTest **完整 20 方法 NOT_RUN**，沒有新 extracted predicate fixture，沒有改原三個依賴 Compose predicate 的方法來湊數。加入 paired test 後完整計畫是 **107 distinct／125 invocations**，本機僅完成 87／105；差額明確保留。正式 Gradle、resolved manifest 比對、APK、真 Android／USB／EOS、Apple 都未執行。本機 supplemental PASS 不等於正式 runtime 對齊完成。

整體仍為原 12 檔候選，與待批 AF／iOS 六檔完全隔離；沒有 push、CI、安裝、下載或 KVM。下一閉環是經明確授權後走現有標準 Gradle host，核全 20 方法與 runtime manifest，並承擔 scripts/validation 路徑實際 Apple fanout；不能在此之前又疊新的平行 harness 或宣稱全案完成。

## 16:33 已告知的十八檔本機整合結果

上述十二檔與原 AF／iOS 六檔以同一 `34ddb8c8f39daf3df96c2e6fa3da12f4751060c2` 基底，在獨立候選整合；兩個原 sealed worktree 不變，18 個 changed paths 無重疊。這是新的本機整合 scope，不是借用舊六檔的外部交付批准。沒有新架構、repo runner、workflow、predicate fixture或安全範圍變更。

單一 hard 900 秒 gate 於 **2026-10-22 16:47:19.593 UTC** 起跑，**16:52:40.555 UTC** 終態，累計 **320.962 秒**。先 fresh compile 全部 77 data 來源，再 fresh compile 44 UI/support＋12 whole test classes＋原 whole CameraFocusMetadataTest helper與既有 observer；未使用舊 app/test jar。所有原來源、fixture、dependency及compiled class origins在同一窗內對帳。

- **283 distinct methods／301 invocations PASS**，是 213 AF/recovery＋87 runtime/preview扣除17共用方法；18 preview方法各有CCAPI/Bridge兩參數。逐一discovery、started/finished、0 ignore、0 assumption核對，保留既有legacy-bridge在CCAPI參數提早return的限制。
- 原4個legacy-flags controls精確 **2 named assertion RED＋2 PASS**，不是產品RED。既有caller／extra-await反證與全部13個release safety checks均保留。
- Python **13 controls PASS**、原normal bridge Ruff PASS。兩次完整Swift syntax parse及75原body／8新增source／原UI hash檢查PASS，**不是Apple SDK typecheck或原生AppState runtime**。
- 峰值取樣process-tree RSS **709,520 KiB**；最低shared free **3,128,836,096 bytes**。2CPU、768MiB sampled ceiling、2GiB floor＋768MiB admission reserve不變，無guard failure。

前批1200秒與600秒所有RED／setup／RSS／SIM117證據保留，這次不是改寫那些結果。原baseline78／92、still3、focus13及224項settings／CcapiClient只保留歷史來源與證據，沒有重跑，不計入新fresh PASS；尤其不能稱507項整合runtime通過。

**正式Gradle、原CameraViewModelPreviewTest完整20方法、APK／signer與iOS 83個native方法仍NOT_RUN。** 兩套active診斷配置已共用，但未得到正式Gradle resolved manifest不能稱環境正式對齊。外部18檔dev交付與原Android host＋Apple全流程另待確認；沒有推送、CI、下載、安裝、device/KVM。下一有意義閉環是正式門檻與上述缺項，不再以新的補充harness替代它們。
