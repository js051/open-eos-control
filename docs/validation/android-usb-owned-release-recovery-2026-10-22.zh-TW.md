# Android Canon USB Bulb／半按的原連線釋放責任

## 交付狀態

此批已完成本機 source 與針對性離線驗證：188 個唯一方法通過。仍未推送、未觸發 CI，並非 PR ready；也沒有真實相機或 Android device/KVM 驗收。

邏輯 baseline 是已發布 dev `9f86613bc0da445ecf59e9c8786488402af77e4e`，加上仍獨立封存、尚未交付的 still-release 修正之兩份精確 production/test bytes。原 still worktree、168-pass receipt 及其文件不修改；本批差異只含 USB backend、同一測試檔與本文件。它不把先前 168-pass 重新命名為本批結果，也不宣稱全案或三平台驗收完成。

## 已確認來源缺口

原 `startBulbExposure` 在 ON1／ON2 receive 失敗後雖會嘗試 OFF2／OFF1，卻只將 OFF 失敗加為 suppressed error，未保存可供 Stop-only retry 的原連線責任；`bulbExposureActive` 尚未設 true 時，之後 Stop／close 可能不再發出釋放。原半按將 ON1 放在 `try/finally` 前，故 ON1 可能已送達但 receive 失敗時，連 OFF1 都不進入。

這是 send／receive 與 caller state 的來源反例，不是已證明真實相機仍曝光，也不是把 explicit rejection 視為 lost ACK。

## 最小修正與不變條件

- 共用既有 `ShutterReleaseSession`；在第一個 ON 前先存 immutable 原 `PtpSession` 的 OFF closure。still、Bulb 均保留 OFF2→OFF1；半按只存 OFF1。沒有新增 opcode、全域 timeout、重送 ON 或新 owner framework。
- Bulb 兩個 ON 回覆成功才標記 acknowledged-active；釋放責任與 active 分開。任一 OFF 失敗保留 typed `ShutterReleaseException`、原 Start failure 與原 closure。
- 半按也沿 still／Bulb 的 event mutex→release helper 順序進入，避免新共享 helper 形成反向鎖順序；三次原 event drain 保持相同相對次序。
- 取消／Start 失敗後沿用 helper 的 `NonCancellable` 釋放。明確拒絕加成功 OFF 仍回原 Start error，不升格成成功拍攝。
- `retryShutterRelease` 只呼叫原 saved OFF；成功後清 held state，不做 captured-object wait、再次 ON、準備目的地或 capture success。後續 status read 的失敗不能恢復已確認結束的 OFF 責任。
- 正常 acknowledged Bulb Stop 保留原 captured-object／host transfer 與成功路徑。release-unconfirmed 的 Stop 只執行 recovery，不進入正常拍攝結果路徑。
- close 每次只選一個釋放路由。正常 active 且 release 已知的 Bulb 沿正常 Stop；其他未解責任只做 saved OFF。失敗仍關閉傳輸資源，但釋放錯誤必須維持 primary，shutdown failure 為 suppressed。釋放已成功後的物件／status read failure 不誤報為 OFF 未確認。
- `CameraViewModel`／`CameraUiState`／repository／helper 本身均不修改。現有 typed warning 令新 press 不可用而保留 Stop/status；OFF-only retry 不會呼叫 capture feedback/review 成功。close 的 typed cause 令 repository 保留 SHUTTER 與 retirement revision，阻擋 replacement acquisition，不能被 finally 清 active 掩蓋。
- 原 dedicated DO_AF／AF_CANCEL、標準 PTP capture、其他平台、workflow、權限、安全檢查與時限不變。

## 驗證設計與精確數量

保留 still batch 的 168 個唯一方法，再加入原 `CameraShutterRecoveryStateTest` 8 個不重疊方法，合計 176 原方法；新 12 方法後 candidate 為 188。原 USB 105 方法的完整方法 bytes 與原其他 suites 保持不變。原 108 個 thumbnail/LRU 相關測試來源保留，但不冒稱這次另外執行 108。

Baseline 固定 8 方法：4 個具名 assertion RED，另 4 個既有 balanced-Bulb／active-close／rejected-ON compensation controls 必須 PASS。不是任何非零 exit 都叫 RED；編譯、fixture、runner 或缺少方法都須停下分類。

新證據須使用完整 production VM→repository→backend→synthetic PTP transport。模型可在 ON 已被合成 peer 記錄後丟失 receive，並控制 OFF 失敗。來源／名稱／裝置皆為 synthetic。repository test 的 transport factory open 計數不是 backend constructor 計數；另外比對 retained backend A identity 並依實際 connect gate 確認 B 尚未 acquisition。

VM witness 的 no-new-listing 僅限受控的虛擬時間：原 poller 已停在下次 delay；同步 retry 後的正常 status drain 先消耗 fixture 產生的 event。這不是禁止獨立 contents event 刷新 gallery 的產品契約。關鍵不變條件是 OFF-only recovery 不自行產生拍攝成功／review 回饋，後續事件讀取與原命令 ACK 必須分開。

新 JVM gate 兩個 variant 均新編完整 77 data＋43 UI 來源，不重用舊 app jar、baseline overlay 或 helper slices。執行前須 seal 精確 method IDs、四個 RED markers、source/dependency hashes、class origin、獨立 JSON 優先序與 empty output namespace。保留六套原測試使用的 synthetic loopback MockWebServer，不連真實相機或外部服務。使用既有 cached Kotlin/JDK/JUnit，2 CPU／取樣 process-tree RSS ≤768 MiB，單次累計 ≤600 秒（590 work＋10 cleanup/seal），2 GiB free floor＋512 MiB admission reserve。本次實際結果列於下節；此 closure 不等同完整 Gradle／Compose 畫面／APK 建置。

## 2026-10-22 實際本機結果

- 02:15:39.871670–02:21:38.675894 UTC，單次 serial gate 完整 PASS；358.806 秒，未重置 600 秒累計期限。最大取樣 process-tree RSS 為 753,624 KiB（上限 786,432 KiB），結束 free disk 3,946,434,560 bytes。
- Baseline 新編 77 data＋43 UI，執行固定 8 方法：4 個原 controls PASS，且只有下列 4 個 assertion RED；無編譯／fixture failure 充當 RED。
  - `canonBulbLostFullPressAndFailedCleanupRetainsOriginalRelease`：`USB_BULB_LOST_FULL_OWNER`。
  - `canonBulbLostStartWithFailedHalfReleasePreservesBothFailures`：`USB_BULB_LOST_START_HALF_OFF_OWNER`。
  - `canonHalfPressLostResponseStillAttemptsOriginalOff`：`USB_HALF_LOST_ON_REQUIRES_OFF`。
  - `canonUsbBulbUnknownStartKeepsVmStopAndSuppressesSuccess`：`USB_VM_BULB_UNKNOWN_STOP_ONLY`。
- Candidate 再新編同一完整 77 data＋43 UI closure；188/188 PASS：USB 117、helper 4、CCAPI recovery 14、CCAPI shutter/AF 6、capture-review 39、UI recovery-state 8。精確 method 集合與 annotations 一對一，0 failure／skip／assumption，無只看總數的驗收。
- 兩套 variant 的 backend／repository／helper／VM／tests class origin 逐一核對；candidate 無 baseline jar，dependency jars 無 app/test classes。兩份 UI/test jar bytes 相同是相同來源與未變 ABI 的 fresh compilation 結果，不是重用輸出；兩份 data jar hashes 不同。
- 原 105 USB 方法 bytes、其他 71 原方法來源與 108 thumbnail/LRU 測試來源保持不變。後者此次只做來源保留核對，不另計入 188。Gate 前後 source/dependency hashes 相符。
- Gate 前的 source review 修正兩個新 fixture 的 available-shots 前提為 0，以真正走既有 capacity-preparation 分支；未執行錯誤 fixture，也未將其算為 RED。Production 沒有為測試改時限或放寬 ACK／ownership。
- 正常 host Stop 的 OFF2→OFF1→event→object→transfer ACK、prepared destination、exact bytes 均經合成 caller 驗證；unknown recovery 不發新 ON、不自行下載物件或宣告成功。close 的 post-release read error 與真正 OFF+shutdown error 明確分開。

本次固定 source/test SHA-256：production `c8d651d69de777c1c4bb77fe261a1544f3cdeed0a860b7f83df5e10960861669`；test `e232775cb869b74071b94566e17261fc2963419603d5ee8e713b01aef371605a`。結果摘要 SHA-256 `e3b0d154e110fd8e70974ac386954c7d253e3944d694553f2640f3a6e659dce7`；完整方法報告 SHA-256 `353ade6e869db87e9edf1432bb1d125ecafbe44c62fe9c6c2d6bebfc7ae06a07`。驗證後僅本文件追加實際結果，production/test 未改。

## 殘餘限制

此批證明目標是原 caller 的釋放責任，不是全 backend 任意並行呼叫皆已序列化，也不是 USB driver／實體快門、光學曝光、相機斷電或關閉後仍能再次送達 Stop 的保證。transport 已關閉時，保留的是未解責任和 replacement gate，不是宣稱硬體已停止。

iOS picker native crash、Apple USB wire contract、合法 EDSDK provider、Android native color 與實體跨平台驗收仍各自未解。本批本機完成不授權外部交付，也不代表整體 EOS 開發目標完成。


## 2026-10-22 Android host 協程例外恢復對齊

原整合提交 `4cd1aafc3167db75aef0e5a899b34d5b4f0faf99` 的補充 Android host run `39753392913` 在 09:21:06 UTC 終止為失敗：2057 項中 8 項失敗。這 8 項均先通過外層 `ShutterReleaseException` 檢查，接著在直接 `cause` 型別或 operation 斷言失敗；後段 owner、OFF、禁止重按與 retirement 斷言因此尚未於該 CI 執行到。保留原失敗，不以本機 412 項通過替代完整 host 結果。

既有 `ShutterReleaseSessionTest.ambiguousPressAndFailedReleasePreserveBothFailures` 已記錄 kotlinx-coroutines 的 stacktrace recovery 可能複製 typed exception，將原例外置於 cause。原本機整合 JVM 未開啟 `-ea`；本次驗證將明確開啟 assertions 與 coroutine recovery，以實際 backend 反證這個差異，不靠停用 recovery 或變更 production 例外來求綠。CI 日誌只有斷言失敗，沒有被捕捉的完整例外鏈，所以來源推論本身不算動態根因證明。

修正範圍只有 `UsbPtpCameraBackendTest.kt` 與本文件。原 117 個 USB 方法均保留，只有上述 8 個方法的例外證據讀法對齊既有 helper 契約，其餘 109 方法 body 不變。允許的 primary 鏈只包含精確的 `ShutterReleaseException` wrapper，終點必須是精確 `PtpResponseException`，operation 必須為 `REMOTE_RELEASE_OFF`、response 必須為 `GENERAL_ERROR`；未知中介例外、錯誤 PTP 欄位、缺少原 suppressed 物件均不可被當成有效證據。所有原釋放順序、原 owner、UNKNOWN、無新 press、無新拍攝成功、關閉及替換阻擋斷言保持。新增 6 項拒絕控制用來防止寬鬆搜尋把無關例外當成通過。

離線計畫保留原 401.957761 秒整合 gate，不重設原 720 秒上限。本次另有一次完整 hard 600 秒修正驗證窗，包含：原編譯產物的 8 個原方法在 recovery-on 下之精確失敗；僅加 assertion 訊息、原判斷式不變的單一 actual-backend 觀察；兩個 variant 的新鮮完整 UI/test closure 編譯；最終 418 個 distinct 方法（原 412 加 6 控制）完整執行。production data jar 只可在來源、原編譯 receipt 與 SHA 完全一致下沿用，不可引用 AF 或 iOS session-memory 候選。

動態鏈若與預期不符，本次必須停止並回報，不擴大 normalization。資源仍為 2 CPU、768 MiB 取樣 process-tree RSS、2 GiB 磁碟 floor 與 512 MiB 起跑 reserve；不是真實相機、裝置或完整 Gradle/Apple 原生驗收。發布與額外 CI 尚未執行，需併原 Apple run 終態後評估；不更動 workflow、timeout、安全設定或 KVM。

實際結果：2026-10-22 09:44:47.335013 至 09:51:35.704360 UTC，修正 gate 使用 408.370215 秒並通過。原 immutable 編譯產物的 8 方法在 recovery-on 下精確重現 8 個原斷言失敗；不是 compile/setup failure。單一實際 backend 的觀察證實 `ShutterReleaseException → ShutterReleaseException → PtpResponseException(operation=37161,response=8194)`，對應原 OFF／GENERAL_ERROR，並保留原判斷式失敗。這是可控制的 runtime parity 反證；CI 原捕捉物件本身未輸出，所以不冒稱已讀取 CI 例外鏈。

兩次完整 UI/test closure 新鮮編譯分別 75.280309／82.393284 秒；最後單一 candidate run 418／418 通過，沒有 skip、ignore、assumption 或失敗，涵蓋 123 USB 方法（原 117＋6 控制）及其他原 295 個方法。最大取樣 RSS 為 757412 KiB，所有來源、依賴、class origin、原候選保護與 postcheck 均通過。production 77 檔來源與本次原整合 data jar SHA 對齊後沿用，非重新乾淨 Gradle app build；43 個 UI 檔與完整直接測試 closure 每個 variant 都重新編譯。

原整合 401.957761 秒加上本次 408.370215 秒，共 810.327976 秒，分屬原 720 秒與另行通知 600 秒兩個明列窗口，沒有將前次消耗歸零。原 Android host 2057／8 失敗與其八項後段未執行事實保留；修正後全 host、APK 與簽章 CI 尚未執行，不能以本機 418 代替。原 Apple run `39753392872` 已於 09:55:14 UTC 完成：Core 694、App 857、UI 44 項皆通過，App/UI 38 分 45 秒，未改原 45 分鐘上限。原六情境 real-picker case 通過，僅代表本次未重現，並非永久修復證明。此 test-only 修正不聲稱解決 Apple picker 問題或證明實體相機釋放。
