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
