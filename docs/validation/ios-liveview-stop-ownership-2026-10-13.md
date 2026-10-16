# iOS Live View stop ownership

Status: implementation and source regressions prepared; runtime verification NOT_RUN.
Base: ac6b4dfc1ca0189fa1a18cb24c927c917e6056c8.

## Source evidence and behavior

The base direct client continued AUTO after any source error, suppressed RTP and multipart cleanup errors, and cleared the active source after a nonthrowing Stop. CameraAppState.restartLiveView then always started again. Those paths could lose the original stop responsibility after response loss.

The direct client now captures the exact stop URLRequest (source, method, versioned path, body and session credentials) before dispatching JPEG, multipart or RTP Start. Unsupported/pre-dispatch failures do not create camera cleanup responsibility. Native RTP allocation is closed if cancellation or Close wins before camera dispatch. A failed Stop retains its original request; another Start, AUTO fallback and JPEG size downgrade cannot pass it. Successful cleanup still permits AUTO fallback. Multipart reader DELETE remains best effort; general Live View Stop is authoritative.

Owned Start tasks settle before Stop/Close finishes. Independent cleanup survives caller cancellation; cancellation remains the caller's result even when compensation loses acknowledgement. Concurrent Stops share cleanup, and a later explicit retry sends only the retained Stop. Close retires the old command while preserving an unresolved old-camera warning.

The Bridge client applies the same session ownership with the existing /liveview/start and /liveview/stop routes. After an ambiguous Start or any failed Stop, proof requires active=false and explicit liveViewStopUnconfirmed=false. A first Stop after an acknowledged active=true Start can accept the old active=false reply with the new field absent or null. False/missing status cannot discharge a retained command. Delayed status cannot recreate a stop after its acknowledgement. Start rechecks ownership inside its actor task immediately before dispatch. A true status arriving while Stop is in flight revokes that owner’s legacy eligibility; settlement checks the current matching obligation, while explicit false remains authoritative.

The additive CameraLiveViewStopState API preserves nonthrowing stopLiveView compatibility and offers throwing retryLiveViewStop. CameraStatus.liveViewStopUnconfirmed is optional; Bridge parsing defaults missing status to false while local responsibility remains authoritative. App restart stops on failure, displays a dedicated stop-only recovery action, and treats restart during recovery as Stop-only. Disconnect warnings refer to the previous camera; acknowledging them sends no command to its replacement. English and Traditional Chinese strings are included. Sleep and sensor cleaning await confirmed Stop before dispatch; the maintenance interval prevents concurrent Start, and successful non-poweroff cleaning can restart a new view. App restart waits for both Core cleanup and the original App Start task to release its busy ownership.

## Source regression coverage

- LiveViewStopOwnershipTests: DELETE/POST-off retention, exact retry payloads, multipart/RTP failed cleanup blocking AUTO, reader failure with successful general stop, caller cancellation, pending Start versus Stop, concurrent start/stop, late RTP allocation, unsupported source, retired/new client isolation, Bridge original-session retry, Bridge close/reinitialize isolation, legacy and explicit proof controls, successful AUTO fallback, failed-Stop maintenance gates and successful cleaning followed by a new Start, held-Stop status revocation (absent/null/explicit-false controls), delayed-status ownership, and newer uncertainty revoking legacy eligibility across a late Start acknowledgement. The admission-to-task actor-turn recheck is source-reviewed; no deterministic scheduler/runtime proof is claimed for that sub-boundary.
- LiveViewStopRecoveryTests: production App/CameraSession/Bridge flows for failed restart, Start compensation failure, explicit Stop-only recovery, old-camera warning versus replacement, caller cancellation with late Start, failed-Stop maintenance recovery, successful cleaning/restart, direct cleaning retry clearing discharged recovery without a separate Stop action, and a held-Start restart ownership barrier.
- Existing ControlResponseOwnershipTests and DesktopBridgeShutterRecoveryTests fixtures use actual Start/Stop replies and retain their old assertions, including a safe Live View Stop while shutter recovery is locked.
- CCAPIClientRTPTests: existing listener-before-dispatch fallback control now expects no unnecessary camera Stop; existing acknowledged cleanup/AUTO controls retained.

## Verification record

- git diff --check: PASS in this worktree.
- Swift availability: command -v swift returned no executable.
- Xcode availability: command -v xcodebuild returned no executable.
- Swift package compilation and tests: NOT_RUN (toolchain unavailable).
- App unit/UI runtime tests: NOT_RUN (Xcode/iOS Simulator unavailable).
- Physical iPhone/Canon camera verification: NOT_RUN.

No runtime red/green evidence is claimed. Before delivery, run the full existing Swift Core and iOS App suites on the final combined source, including the new ownership tests and existing JPEG size, RTP, multipart, foreground-media, and shutter recovery suites. No framework, installation, commit, push, CI invocation, main merge or release is part of this local change.

## First integrated CI and bounded recovery

Run 38405544208 at 70a180d passed all 383 Core tests. App compilation passed, but the App test log recorded one real failure: `testRecordingReviewOpensExistingVideoPlaybackAndKeepsOriginalDownloadSeparate` observed two original requests where one was required. Later `testSensorRecoveryLiveViewCompletionCannotClearReplacementWarning` began without completing. The run was cancelled after the App/UI step exceeded twice the preceding successful duration; it is CANCELLED, not a passing or completed App/UI run. The existing always-upload step retained its result bundle (artifact 11821514886).

Source inspection found the held-Start fixture awaited disconnect before releasing the Start that disconnect must drain. The fixture now runs disconnect concurrently, verifies the held operation prevents completion, releases it, and observes both disconnect and the outer maintenance completion with bounded guards. It retains replacement-state assertions. Workflow test-step and job limits of 45/55 minutes leave time for the existing always-upload result collection; they do not remove tests or change business assertions.

The video fixture previously returned 503 for every playback read, which could legitimately invoke local fallback while open. It now serves the bundled video with valid byte ranges and matching size; the successful playback journey still requires zero original downloads before the explicit manual action and exactly one afterward. Separately, queued fallback code lacked a cancellation/closed check before its first filesystem/download work. An entry guard now prevents that retired request, and controlled tests cover close-before-start, current-owner fallback deduplication, and late success/error cleanup. Source inspection supports both issues; the old aggregate count alone does not establish which ordering occurred in that run.

These follow-up source changes passed independent review and whitespace checks. Swift/XCTest for the revised candidate are NOT_RUN locally and require a new exact-head normal CI run. No prior green result is inherited.


## P2 整批續驗（2026-10-13）

來源 45251eb00f496fa510160159c8a0f4486d7df76a 的 CI 38436309357 已完成：App 467 例全部通過，包含先前等待／播放清理及新工作清單接續控制。UI 38 例中的錄影回顧旅程在繁中切換錄影模式時失敗；英文同旅程已完成。兩筆 failure 屬同一旅程的 simulator state timeout 與原 error 重拋，不能寫成兩個獨立產品缺陷。

原始 trace 顯示一次錄影 segment tap 後 movie_mode 仍 off、update_count 0，尚未進入 REC。官方 artifact 11834822434（170742597 bytes、SHA-256 666f5107cafc7f5cc4fe8d3f41f3bc1edefc1cf5587ce5bb45cd4a20808a53c5）已核大小/hash；該旅程原錄影的 54.5／56／70 秒畫面保持拍照選取及 Live View spinner，沒有可見 alert。事件座標在錄影 segment 區域。這不足以證明 setter、Core guard 或錯誤曾出現後清除的哪一分支；不預稱根因已確認。

失敗分支新增固定欄位診斷：tap 前驗 Photo／Video 選取；原單次 tap 與 simulator 等待期限不變。失敗時只保存兩 segment 的有限 flags、record 按鈕是否存在及第一個 alert 最多兩段各 512 字，附一次截圖後重拋原錯誤。不重試 tap、不改 pass 判準、不延長 wait。XCTest 同步 AX 查詢與截圖沒有單呼叫硬期限，仍由原 45 分鐘 step／55 分鐘 job 外層界線控制。

本次共用 workflow 的 Android API34 gate 成功，API36 在 357 例中有 48 例失敗；raw log 明確記錄 Quickstep isn't responding 遮住 DocumentsUI。保留真失敗，不能當環境 pass，也不能用它否定或證明 iOS 功能。


同群 source review 發現獨立 Core 一致性缺口：設定刷新先清空 shared routes、跨多次 await 重建，而 values cache 尚是上一份；同期使用者設定可被半成品路徑表拒絕、在 POST 前丟 invalidSetting。修正以函式內暫存全部 values/routes/衍生能力，再在同 actor turn 提交；讀取 revision 與 command/cache epoch 分別保護較新完整結果與命令 readback。必要 force-fresh 能力檢查不改成舊 cache fallback，失敗／取消亦不發布半成品。

新增七個受控 gate 測試來源涵蓋：暫停 status 重載仍可明確送出 mode POST、舊讀不得覆寫新完整讀、取消保留原配對 snapshot、成功刷新撤回不合法能力、首次無 cache 重疊、force-fresh 與較新尚未完成 passive refresh，以及 command readback 未完成時拒絕被退休讀。獨立 source review 通過；Swift/XCTest 新版仍 NOT_RUN，沒有用 UI 再跑成功預先證明這就是前次原因。
