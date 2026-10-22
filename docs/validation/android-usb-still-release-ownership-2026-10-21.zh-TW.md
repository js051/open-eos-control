# Android Canon USB still capture：原始 release owner

## 範圍與現況

本批以遠端 `dev` 的 `f3708149825e7aab998717b032a4a1f7996e1963` 建立獨立 detached worktree，只修改 USB backend、其測試及本文。既有未發布 DST commit 不在本批祖先或差異內。本批僅本機來源與驗證，不推送、不啟動 CI；完成本機測試不等於 Android 裝置或實體相機驗收。

修復目標是 ledger 63 still capture 的 Android Canon USB release 責任，不更改標準 PTP InitiateCapture、其他平台、PTP transaction/response 驗證、已知操作碼、request timeout、重試策略或共用 `ShutterReleaseSession`。

## 已確認來源反例

原 Canon 分支先送 ON1，然後送 ON2，只有 ON2 成功返回才進入 OFF2 的 `finally`。若命令已送出且可能生效，而 ON2 的 receive 失敗，原路徑只送 OFF1；若 ON1 receive 失敗，兩個 OFF 都不送。這不同於明確收到拒絕回覆，也不能以 exception 推斷相機未按下快門。

PTP 層先 send 再 receive，沒有替 caller 補發 release。既有 USB Bulb 補償已使用 OFF2→OFF1；本批重用相同 release 順序及原有跨後端 shutter owner helper，不新增相機 framing/命令，也不重送 ON。

## 修復契約

- 在第一個 ON 之前註冊原 `PtpSession` 的 stop closure；ON1/ON2 任一失敗或取消仍在既有 NonCancellable cleanup 內嘗試 OFF2，再嘗試 OFF1。
- 任一 OFF 未確認，保留原 owner，拋出既有 `ShutterReleaseException`；若原 ON 也失敗，保留其 suppressed evidence。下一個 still、half-press 或新的 Bulb press 被拒，不允許藉重拍清掉責任。
- 明確 retry 僅向保存的原 session 送 OFF2→OFF1；兩個回覆都成功才清掉 release owner。它不補送 ON、不製造拍攝成功或新素材證據，也不替使用者自動重拍。
- status 只在本批仍有未確認 release 時提供 `shutterReleaseUnconfirmed=true`；其他情況保留原本 null，不宣稱其他控制的物理安全已確認。
- close 先嘗試保存的 release，仍關閉原 USB transport，並把 typed release failure 留為 primary error。即使欄位與 active state 清空，也不吞掉 repository retirement warning。
- 原 `CameraRepository` 會將此 typed failure 映射為 SHUTTER warning、增加 retirement revision；原 connect 會在新 backend factory 前拒絕替換。失敗的原 owner 不移植到 B。

本批不重寫既有 half-press/Bulb 自身的控制流程。它只在未解的 still release 存在時禁止新的 press。原 VM/repository 的操作序列化、Stop-only 通路和連線退休 gate 保持原樣；不聲稱所有直接並行 backend 呼叫都已獲得新的生命週期證明。

## 凍結驗證計畫

保留原 USB 97 methods 的完整原始 bytes；新增 8 methods：lost ON1、lost ON2、ON2 receive 時取消、OFF2 failure/new-press gate、OFF1 failure/suppressed ON failure、明確 OFF-only retry、close typed failure/transport close、實際 repository A→B retirement。

Synthetic transport 在 send 時記錄「可能已套用 press」，在 receive 時丟失回覆；取消事件一次性消耗，不以重播事件或 PTP rejection 代替未知 ACK。所有物件名稱均為合成值，不使用相機或使用者媒體。

Baseline 只選 6 methods，預期 2 個精確 assertion RED 與 4 個原正向控制：

1. `canonStillLostFullPressResponseReleasesBothStagesWithoutPressReplay`：`USB_STILL_LOST_FULL_RELEASE`。
2. `canonStillOff2FailureRetainsUnknownOwnerAndBlocksNewPresses`：`USB_STILL_RELEASE_OWNER`。
3. `backendExposesOnlyAdvertisedStandardOperationsAndRunsMediaPath`。
4. `canonAppCaptureOwnsObjectEventsWhileBackgroundPollingIsActive`。
5. `canonCaptureRefusesHostDestinationWhenNoCardTargetIsAdvertised`。
6. `canonCaptureStopsBeforeShutterWhenCardDestinationWriteIsRejected`。

Candidate 原 160 + 新 8 = 168 methods：USB 105、ShutterReleaseSession 4、CCAPI control recovery 14、CCAPI shutter autofocus 6、capture review recovery 39。既有 HTTP 測試只使用原本的 synthetic localhost peer，不接觸外部服務。任何環境 bind 拒絕均須停止，不改安全旗標或換路由繞過。

原 108 thumbnail/LRU regression 測試來源不改；本批局部 gate 不重跑它們，也不把其歷史 pass 加到本次 168。原共用 helper、repository、VM 和 capture review assertion 不修改。

## 執行與限制

離線 gate 已於 2026-10-21 05:51:12–05:55:55 UTC 完成，累計 283.71 秒，未重跑或增加預算。使用既有 Kotlin/JDK/JUnit 與快取依賴，baseline 重新編譯 77 個完整 data production files；candidate 重新編譯同組 77 files 及 43 個完整 UI/related files，沒有舊 UI/data bytecode overlay 或 helper 片段。這是來源封閉的局部 JVM gate，不是 Gradle 全 app build、Compose 畫面編譯、Android SDK instrumentation、USB 硬體或實體快門驗收。

資源上限為單次 600 秒，2 CPU，768 MiB sampled process-tree RSS，2 GiB 剩餘磁碟 floor，起始再保留 512 MiB。RSS 是取樣總和，shared pages 各 process 計算，不宣稱 kernel hard limit。Compile/setup failure 不能算 baseline RED，只有凍結的兩個 assertion 可接受。

收到 synthetic OFF ACK 只證明程式保存及使用了正確 closure，不證明相機已物理放開。失去 ACK 後可能有 late response、USB disconnect 或 PTP transaction 不同步；原協定驗證不放寬，無法證明時保留 uncertainty。實體 press/release、各機型 opcode 行為與斷線後人工復原仍待另外授權的相機驗收，不提供固定 Stop latency 保證。

## 本機實測結果與交付狀態

- Baseline 六個完整指定方法實際執行：精確 2 個上述 assertion RED，4 個既有正向控制 PASS。沒有 initialization、compile、fixture 或額外 failure 被當成 RED。
- Candidate 168/168 PASS：USB 105、ShutterReleaseSession 4、CCAPI control recovery 14、CCAPI shutter autofocus 6、capture review recovery 39；無 ignored/assumption/duplicate/missing methods。
- Fresh baseline data/test compile 75.64 秒；candidate data compile 68.97 秒；candidate UI/test compile 57.91 秒；baseline runtime 0.51 秒；candidate runtime 79.06 秒。累計包含預檢、jar origin 與封存為 283.71 秒。
- 最高 sampled process-tree RSS 751,564 KiB（約 734 MiB），低於 786,432 KiB 上限；無時間/RSS/磁碟 guard stop。起始剩餘 4,716,630,016 bytes，結束剩餘數值記於本地 immutable receipt。
- 實際 structured JUnit receipt 逐一比對 frozen class/method roster、開始與結束、failure 類別及 baseline marker。Runtime 使用 registry JSON jar 在 android-all 前，沒有 SDK android.jar 或舊 app classes；重要 production class 皆只有預期 fresh jar 一個來源。
- 驗證前後 production、test、依賴及原 regression sources hashes 相符。原 97 USB methods 完整區段保留，新增 fixture controls 預設不改原行為。

本批只完成本機候選驗證與封存，尚未 commit/push/CI 或裝置驗收。它不包含本機另存、尚待發布確認的 DST 修正，不以新批次改繞既有 upload 停止狀態。
