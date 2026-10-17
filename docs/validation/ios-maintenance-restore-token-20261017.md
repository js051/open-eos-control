# iOS Bridge maintenance restore permits

## Scope and state

Implemented against development base `06e02db764a0ae6d5982b1e135502ced95a514b4`.
This is the iOS Bridge caller migration for the additive maintenance restore
ownership contract. No direct CCAPI wire behavior, feature, version, release,
main merge, CI policy, or local-retirement semantics is changed.

Only keep-power sensor cleaning with an actual frozen prior preview requests
`prepareRestore:true`. The original still-current strict Stop must return a valid
24–64 character URL-safe permit to authorize Restore. Missing, null or malformed
optional permits preserve affirmative Stop evidence and allow the maintenance
command, maintaining old-Bridge compatibility; only Restore fails closed before
Start dispatch. Lost/cancelled prepare responses still retain explicit global
cleanup because they supply no current affirmative Stop evidence. An idle journey
does not request a permit; its App-local restoration is unchanged.

The same-session attempt stores the prepared permit under its lock. Restore
consumes it once and freezes it in the actual Start obligation before transport
await. Successful Start requires the exact echoed permit, `started`, strict
`active:true` and `liveViewStopUnconfirmed:false`, plus the existing source proof.
Explicit Stop and close use the actual obligation, including after Start timeout
or cancellation; synthetic cleanup attempts never supply a permit.

A matching receipt-only `stopped` or `cancelled-before-start` response discharges
only the old Start responsibility. Warning ownership has a distinct revision
that retry failures cannot reset. A newer warning survives and becomes a fresh,
tokenless Stop obligation. The next explicit Stop obtains fresh global cleanup;
there is no automatic extra Stop and no terminal-receipt retry loop. Similarly,
a strict owned Stop response superseded during await retires its old token and
preserves the new warning for the next explicit global Stop. A second cleaning
journey prepares a new token rather than reusing the previous restored owner.

Local DELETE remains session retirement only, never physical camera Stop proof.

## Authored native regression coverage

`MaintenanceControlTests.swift` adds or strengthens production-client tests for:

- Frozen prepare/Start/cleanup payloads and locally consumed single-use attempts
- Missing/null/malformed optional preparation permits preserve affirmative Stop
  and cleaning while blocking Restore; cancelled/lost prepare uses global cleanup
- Missing/null/malformed/wrong Start token, wrong/missing/null outcome, partial
  state and Start timeout; all retain owned explicit recovery without replay
- Invalid cleanup token/outcome/state, followed by valid terminal recovery
- Terminal receipts with warnings before and during retry, preserving the warning
  and selecting global Stop on the next explicit request
- New status warning after terminal cleanup retains strict 15-second maintenance
  transport, while ordinary non-maintenance warning cleanup remains unchanged
- Fresh physical owned Stop superseded by a warning during await
- Successive cleaning journeys issuing distinct permits
- Idle and power-off paths without preparation
- Cancelled/late preparation, cancelled Start, cross-client/session ownership,
  explicit close, direct CCAPI behavior, and local retirement boundaries

Existing App fixture transports were migrated in MaintenanceJourneyTests,
LiveViewStopRecoveryTests, ControlResponseOwnershipTests and
CameraLifecycleContinuationTests. Their ordinary routes retain existing behavior.

## Evidence and open gates

Executed locally:

```
python -m unittest discover -s scripts/validation/tests -p 'test_ios*.py' -v
# 82 tests passed, including 4 new token wiring source checks.
git diff --check
# passed
```

These are Python source/wiring checks only, **not Swift compilation or execution**.
The initial attempt to invoke pytest found no installed pytest; unittest is the
existing tests' standard-library runner and completed successfully.

`swift` is unavailable. The initial required pre-commit hook stopped because
PowerShell was not on the default PATH. Reusing the existing authorized PowerShell
installation exposed a missing worktree-local Gitleaks archive; the hook's download
attempt was blocked. The exact existing local official archive was then copied,
verified against SHA-256
`551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb`,
and the unchanged hook passed its staged secret scan with no leaks. No download
was completed and no new software installation was performed.

Native gates remain unexecuted:
`cd ios/OpenEOSCore && swift test`, plus the configured iOS App simulator build
and test gates in `.github/workflows/android.yml` at the exact integrated SHA.
Swift compiler/typechecking and native runtime results must be obtained before
claiming native acceptance. No physical camera evidence exists for this change.

Release assessment: patch safety correction to an existing journey. Native
acceptance and exact-SHA integrated CI remain open; no PR
readiness, main acceptance or release is claimed.
