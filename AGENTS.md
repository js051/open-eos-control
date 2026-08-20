# Repository Security

- Never commit credentials, authorization headers, signing keys, certificates, `.env` files, or machine-local configuration.
- Treat camera serials, diagnostic reports, personal email addresses, user-home paths, and device identifiers as private data. Use obvious synthetic values in fixtures.
- Do not bypass Git hooks with `--no-verify`, weaken `.gitleaks.toml`, or add Gitleaks allowlist entries without explicit owner approval.
- Before pushing, allow the configured `pre-push` hook to scan every outgoing commit. A failed or unavailable scan must stop the push.
- Keep release signing material outside this repository. Public CI may build unsigned or debug artifacts only.

# Workspace Layout

- Treat `C:\dev` as a container for independent project roots only.
- Keep every Open EOS Control worktree, research file, helper tool, log, build environment, and temporary artifact under `C:\dev\open-eos-control`.
- Create additional Git worktrees under `C:\dev\open-eos-control\.codex\worktrees`; never create sibling `open-eos-control-*` directories directly under `C:\dev`.
- Inspect `git worktree list` and existing branches before creating a worktree. Reuse the worktree for an active task instead of creating another branch for the same objective.

# Delivery States

- Do not describe work as complete merely because code was written or a local test passed.
- `implemented` means the requested code and focused automated tests exist in the task worktree.
- `PR ready` means the branch is pushed and the `ci-complete` check has passed for its exact head SHA.
- `main accepted` means the `Main acceptance / main-accepted` check has verified the squash-merged tree against the successful PR run and uploaded `release-candidate-<commit>`.
- `released` means `Release development preview / release-published` passed and the GitHub Release contains the expected downloadable assets and checksums.
- Automated simulator or protocol-fixture evidence must never be reported as physical-camera validation. Use the device-evidence verifier and name the actual camera, transport, app build, and tested operations when physical validation exists.
- Before rerunning or replacing a CI run, inspect its current job and step state. Do not rerun a queued or normally progressing workflow just because it is slow.

# Decision Integrity

- Treat user proposals, questions, and preferred answers as product input, not as evidence that a technical conclusion is true.
- State the evidence-based recommendation before changing version, merge, release, validation, or completion status. A follow-up such as `要進版了?`, `不用進版?`, or `是不是該發版?` requests a fresh assessment; it does not supply the answer.
- The user controls product priority, scope, pause/stop decisions, and whether a ready build is wanted now. Conversation cannot override failed checks, missing physical-device evidence, Semantic Versioning classification, or the delivery-state definitions above.
- When challenged, re-check the repository, exact CI SHA, release assets, and device evidence. Change the recommendation only when the evidence or explicit product constraint changed, and name that change instead of mirroring the latest wording.
- Never weaken a blocker to agree with an expected outcome. If an explicitly requested release is not ready, fix the blocker when feasible or report that the release is being held and why.

# Release Decisions

- Merging to `main` and publishing a version are separate decisions. A coherent, verified PR may enter `main` without immediately creating a release.
- Every PR must record a Release Assessment: latest release baseline, proposed impact (`none`, `patch`, `minor`, or `breaking`), user-visible or distribution reason, unresolved blockers, and physical-device status.
- Use `none` for documentation, tests, CI, internal refactors, and maintenance that do not change a distributable product, unless the change repairs release integrity, security, or broken installation metadata that users need immediately.
- Use `patch` for backward-compatible fixes to existing behavior, performance, security, packaging, or installation. Use `minor` for a coherent new user-visible capability. Before `1.0.0`, incompatible product or protocol-contract changes also require at least a minor bump; after `1.0.0`, use a major bump.
- Publish only when the release delta has meaningful user or tester value, the changed scope has no unresolved P0/P1 blocker, limitations and device evidence are accurate, versions and notes agree, exact-SHA CI and `main-accepted` pass, and the immutable candidate contains every expected asset.
- Do not publish merely because CI is green, time passed, a version number feels old, or the user asked a leading question. Conversely, do not delay an explicitly wanted distributable build for an arbitrary cadence when the release gates are satisfied.
- Follow the detailed decision table and promotion procedure in `docs/development-workflow.md`. Release tags must still use the immutable candidate flow below.

# Change Flow

- Keep one coherent change per branch and PR. Record explicit non-goals so follow-up work is not accidentally claimed by the current PR.
- Use Conventional Commits with the repository's Chinese subject style, for example `feat(android): 改善最近媒體載入`.
- Merge through a squash PR. Direct pushes to `main` intentionally fail provenance acceptance because they have no successful PR tree to promote.
- Prepare a version in its own PR. Run `python scripts/release/verify-version.py --tag vX.Y.Z`, wait for `ci-complete`, merge, wait for `main-accepted`, and only then create the tag on that accepted commit.
- Tag releases reuse the immutable candidate assembled on `main`; do not rebuild or substitute release files manually.
