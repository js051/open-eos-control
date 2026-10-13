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
