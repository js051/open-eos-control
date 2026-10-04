# Android multipart 停止時的單一回應擁有者

## CI 失敗與證據界線

PR #199 的 head `3d37e833f64de4eaf5a4ce090715682a59473ec9` 在 [CI 37196868464](https://github.com/js051/open-eos-control/actions/runs/37196868464) 出現 565 個 App JVM tests 中 1 個失敗：`cancellingMultipartFrameWaitDoesNotWaitForItsFifteenSecondDeadline`。該 head 的 Android 程式與先前通過的 PR #194 基準相同；這不能作為排除產品缺陷的理由。

Console 顯示 stop cleanup 拋 `CcapiLiveViewReleaseException`，底層是 `IllegalStateException at Timeout.kt:67`。原 CI 未上傳完整 JVM XML stack，因此沒有聲稱已取得其完整排程或 stack。

原單例首次在本機重跑通過，仍繼續做確定性故障驗證，沒有反覆 rerun 直到綠。

## 已確證的根本責任問題

`CcapiMultipartLiveViewSession.close()` 在 `Call.cancel()` 後直接 `Response.close()`，背景 `drain()` 的 finally 也會 close。同一 response 可能在 reader 尚未退出 read／timeout cleanup 時被另一執行緒關閉。

本機實際使用的 Okio 3.9.0 bytecode 顯示 `Timeout.kt:67` 是 `deadlineNanoTime()` 的 `No deadline` 檢查；OkHttp 4.12.0 的 `FixedLengthSource.close → discard → skipAll` 分開讀取 hasDeadline 與 deadlineNanoTime。另一條讀取清理路徑若切換 ForwardingTimeout 或清除期限，可在兩個讀取之間使 deadline 消失。以實際函式庫與同步屏障重現了同樣的 `No deadline → Timeout.kt:67 → ForwardingTimeout.kt:41 → Util.kt:337`。這是底層交錯證據，不是假裝重播原 CI 的精確排程。

## Production-path 紅測與最小修補

新的 `CcapiMultipartLiveViewSessionTest` 使用真 CcapiClient／OkHttp／HTTP peer，只在 multipart Response source 加同步屏障：

1. 確認 reader 已進入 read。
2. Call.cancel 解開 socket 後，暫停 read 的退出路徑。
3. source.close 明確拒絕與尚未退出的 read 重疊。
4. 原 production 確定失敗：stopLiveView → session.close → Response.close。在 multipart／general DELETE 前就中斷。
5. 修正後 stop 不跨執行緒 close，只取消 Call、interrupt reader；Response 由 reader 的 finally 單一關閉。closed 以 volatile 保證無鎖迴圈可見性。
6. 驗收 stream／general DELETE 各一次、pending/source 狀態清除、reader 退出後只 close 一次，第二次 stop 不再送 general DELETE。

沒有把取消等待改長、沒有忽略停止錯誤、沒有 skip，原 1 秒取消斷言保留。也沒有加無期限 join、改相機停止政策或更換依賴。

## 驗證

- focused：27／27，包含原 Recovery 14、parser 7、Client multipart lifecycle 5、新同步回歸 1。
- 完整本機 aggregate：App JVM **566／566**、Camera Import contract **14／14**，各自 0 failure／error／skip；4 分 37 秒。
- Lint：0 errors／55 warnings，含既有 4 個 custom registry 無法完整執行限制，未 suppression。
- App／AndroidTest APK 建置成功；192 個驗證來源檔前後 manifest 一致。主交付工作樹的 158 個 Android source/config 與受測樹逐檔 hash 相同。
- App SHA256：`e6d54598b54efd723684e368bc51b83acf987d094ed5cd02411f94f6eed161eb`。
- AndroidTest SHA256：`c49ab65cda4ccc9cbe66dbc749cb849538c1627811bd03354764506ad2c310db`。
- instrumentation 在本機未執行；精確提交 CI 接續驗證，不能拿 3d37e83 的 Bridge／UI 成功取代新 Android source 的結果。

這是獨立的 Android 基礎可靠性增量，與 PC 版面或 picker 功能分開提交；不宣稱所有其他 response 生命週期都已完成同樣稽核。沒有實體相機證據，release impact 為 v0.10.0 Development Preview 之上的 patch；不改版本、不合併或發布。
