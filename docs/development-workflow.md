# Development And Release Workflow

[繁體中文](#繁體中文) | [English](#english)

## 繁體中文

這套流程把「寫完程式」、「通過對應驗證」、「進入 main」與「真的發布」分成機器可判定的狀態。PR 會依變更路徑執行相關平台矩陣；workflow 或版本本身改變時仍跑完整矩陣。相同 Git tree 不會在 `main` 與 tag 再各跑一次。

### 狀態定義

| 狀態 | 唯一判定依據 | 不代表 |
| --- | --- | --- |
| Implemented | worktree 內已有實作與對應測試 | PR 已可合併 |
| PR ready | exact PR head 的 `ci-complete` 成功 | 已進入 `main` |
| Main accepted | `Main acceptance / main-accepted` 已驗證 squash merge 與成功 PR 的 tree；只有版本變更 merge 另產生 `release-candidate-<commit>` | 已建立 Release |
| Released | `Release development preview / release-published` 成功，且 GitHub Release 資產存在 | 已完成實體相機驗證 |

模擬器、mock server、HTTP fixture、AVD 與 iPhone Simulator 都屬於可重現自動驗證，不能取代實體 EOS 相機／手機／USB 路徑的驗證紀錄。

### 決策原則

產品優先順序由維護者決定，技術狀態則由可重現證據決定。對話中的「要進版了嗎」、「不用發版嗎」或預期答案，只會觸發重新稽核，不會直接改變結論。重新稽核必須先確認最新 tag、相對變更、未解決問題、exact-SHA CI、`main-accepted`、候選資產與實體裝置證據，再提出建議。

如果新證據改變結論，應明確說出改變的是哪一項證據。如果證據沒有改變，不應為了附和最新一句話而反轉建議。維護者可以要求暫停、改變產品優先順序，或要求立即取得一個已準備好的 build；但不能以對話略過失敗測試、缺少資產、版本不一致、未驗證的實機宣稱或下列發版門檻。

### 進入 Main 與發版判定

合併與發版是兩個獨立決定：

| 決定 | 應執行的時機 | 不足以單獨成立的理由 |
| --- | --- | --- |
| 合併至 `main` | PR 範圍完整、non-goals 清楚、對應測試存在、沒有該範圍的 P0/P1 blocker，且 exact head 的 `ci-complete` 成功 | 程式已寫完、focused test 單獨通過、使用者期待已完成 |
| 不發版 (`none`) | 只有文件、測試、CI、內部重構或維護，且沒有使用者需要立即取得的安裝／安全／發版修復 | `main` 有新 commit、CI 綠燈、距離上次發版有一段時間 |
| Patch | 修正既有功能、效能、安全、安裝或封裝，且保持向後相容 | 修改檔案很多、問題看起來重要 |
| Minor | 形成一組可說明、可下載測試的新使用者功能；`1.0.0` 前不相容的產品或協定契約變更也至少升 minor | 單一內部 API、新增未接 backend 的 UI、只有 simulator 假功能 |
| Major | `1.0.0` 後有明確不相容的公開契約或使用流程變更 | 視覺改版、一般重構 |

每個 PR 必須在 `Release Assessment` 記錄：最新 release baseline、建議影響等級、使用者／測試者可獲得的價值、未解 blocker 與實體裝置狀態。問句不是 release approval；即使維護者明確要求立即發版，也要先完成這份稽核。門檻全部通過時不需要等待固定日曆；門檻未通過時則應修正或明確 hold，不得先 tag 再補證據。

發版前至少必須同時成立：

- 自最新 tag 起有值得散布的使用者或測試者價值，或有必須立即送達的安全／安裝／發版完整性修復。
- 發版變更範圍沒有未解 P0/P1；其他 roadmap 缺口可以存在，但 release notes 必須準確列為限制，不能宣稱完成。
- 版本依上述規則分類，所有程式、README、CHANGELOG 與 release notes 完全一致。
- 版本 PR exact head 的 `ci-complete` 成功，squash merge 後 exact main commit 的 `main-accepted` 成功。
- immutable candidate 的預期資產與 checksum 齊全。涉及真機相容性的宣稱另需對應 device evidence；沒有實機證據時必須維持「自動驗證／待實機驗證」措辭。

### PR 階段

- `.github/workflows/android.yml` 只由 pull request 觸發。
- 同一 PR 推入新 commit 時，舊 run 會取消，避免同時測試已過時的 SHA。
- `dorny/paths-filter` 以固定 commit SHA 執行變更分類。所有 PR 都跑秘密掃描、版本一致性、device-evidence／CI／release helper 測試與 actionlint；只有受影響的平台才啟動 Android unit/UI API 34/UI API 36、Simulator、Desktop Bridge、Windows standalone、Swift Core 或 iOS App/UI。
- `simulator/**` 會同步觸發依賴 fake camera 的 Android、PC 與 iOS 整合測試；`.github/workflows/**` 會觸發完整矩陣，避免 workflow 自己未被驗證。
- 版本 PR 會改到各平台版本宣告，因此自然觸發完整矩陣。Desktop Bridge wheel/source distribution 與 Windows executable 由已通過其測試的同一 job 建立，保存為該 run 的 immutable candidate artifacts。
- `ci-complete` 是唯一 GitHub required check。它逐項驗證受影響 job 必須成功、未受影響 job 必須是 `skipped`；只有它成功才能稱為 PR ready。

### Main 接受階段

- `.github/workflows/main.yml` 不重跑完整矩陣。
- provenance verifier 透過 GitHub API 找出 squash merge 所屬 PR，fetch 該 PR head，並比較兩者 Git tree SHA。
- verifier 接著要求 exact PR head 最新的 `CI` workflow run 已完成且成功；不同 tree、失敗 run、進行中 run 或 direct push 都會拒絕。
- workflow 以 TOML parser 比較 merge 前後的產品版本。一般 feature、fix、docs 或 maintenance merge 在 provenance 通過後即完成，不建置或保存無法發版的重複候選包。
- 只有版本確實改變時，workflow 才從成功 PR run 下載 immutable Bridge／Windows candidates，另建置需要 repository secrets 的固定簽章 Android APK。
- 版本 merge 的四個檔案名稱、大小與 SHA-256 會寫入 `BUILD-PROVENANCE.json`，再一起上傳為 `release-candidate-<main commit>`。

### 發版階段

1. 在獨立 PR 同步 Android、iOS、Bridge、Simulator、README、繁中 README、CHANGELOG 與 release notes 的版本。
2. 執行 `python scripts/release/verify-version.py --tag vX.Y.Z`。此命令會拒絕程式與文件版本落差。
3. 等待 `ci-complete`，squash merge，接著等待 exact main commit 的 `main-accepted`。
4. 只在該 accepted commit 建立 annotated `vX.Y.Z` tag。
5. Release workflow 驗證 tag、main ancestry 與 successful main run，下載 exact commit candidate，再次核對 provenance hashes 後發布。
6. 只有 `release-published` 成功且 GitHub Release 顯示 APK、Windows executable、wheel、source archive、`BUILD-PROVENANCE.json` 與 `SHA256SUMS.txt` 時，才回報 Released。

Candidate artifacts 保留 14 天，因此版本 PR 合併後應在此期限內建立 tag。失敗或過期時必須重新經過 PR／main promotion，不可手動替換 Release 檔案。

### 避免重工

- 開始前先看 `git worktree list`、現有 branches、PR 與 Actions runs，避免為同一目標再開一份工作。
- CI 顯示 queued 或正常進行時只追蹤狀態；先定位具體失敗 step 才重跑。
- 每個 PR 在模板列出 Evidence、Validation、Device Status 與 Non-Goals，未完成的相鄰能力留給後續 PR。
- 使用現有 Gradle、SwiftPM、pytest、npm、GitHub Actions artifacts 與小型 Python verifier；不維護另一套平行建置系統。
- GitHub `main` ruleset 只要求 `ci-complete`，不直接要求可能被路徑分類刻意 skip 的平台 job。Repo 僅允許 squash merge，合併後自動刪除遠端分支；若設定與本文件漂移，應先修正設定再宣稱流程生效。

## English

This workflow gives machine-verifiable meanings to implemented, appropriately validated, accepted on `main`, and actually released. Pull requests run the platform matrix selected by changed paths; workflow and version changes still run the complete matrix. The same Git tree is promoted instead of retested on both `main` and a tag.

### State Model

| State | Authoritative evidence | Does not mean |
| --- | --- | --- |
| Implemented | The worktree contains the implementation and focused tests | The PR is mergeable |
| PR ready | `ci-complete` succeeded for the exact PR head | The change is on `main` |
| Main accepted | `Main acceptance / main-accepted` verified the squash-merged tree against its successful PR; only version-changing merges also produce `release-candidate-<commit>` | A GitHub Release exists |
| Released | `Release development preview / release-published` succeeded and release assets exist | Physical-camera validation is complete |

Simulator, mock-server, HTTP-fixture, AVD, and iPhone Simulator results are deterministic automated evidence. They never replace a recorded physical EOS camera, phone, or USB validation.

### Decision Integrity

The maintainer controls product priority; reproducible evidence controls technical status. Conversational prompts such as "should this be versioned now?" or "why not release?" trigger a fresh audit rather than supplying the conclusion. That audit checks the latest tag, release delta, unresolved findings, exact-SHA CI, `main-accepted`, candidate assets, and physical-device evidence before recommending an action.

If new evidence changes the recommendation, identify the evidence that changed. If the evidence did not change, do not reverse the recommendation merely to mirror the latest wording. The maintainer may pause work, redirect product priority, or request an immediately available ready build. Conversation cannot waive failed tests, missing assets, version drift, unsupported physical-device claims, or the release gates below.

### Main And Release Decisions

Merging and publishing are independent decisions:

| Decision | Use it when | Not sufficient by itself |
| --- | --- | --- |
| Merge to `main` | The PR scope is complete, non-goals are explicit, relevant tests exist, no in-scope P0/P1 remains, and `ci-complete` passed for the exact head | Code was written, one focused test passed, or completion is expected |
| No release (`none`) | The delta is documentation, tests, CI, internal refactoring, or maintenance with no installation, security, or release-integrity fix users need immediately | `main` has a new commit, CI is green, or time passed |
| Patch | Backward-compatible correction to existing behavior, performance, security, packaging, or installation | Many files changed or the issue feels important |
| Minor | A coherent new user-visible capability is ready for distribution; before `1.0.0`, incompatible product or protocol-contract changes also require at least a minor bump | An internal API changed, a UI is disconnected from its backend, or only a simulator implements it |
| Major | After `1.0.0`, a public contract or workflow changes incompatibly | A visual redesign or ordinary refactor |

Every PR records a `Release Assessment`: latest release baseline, proposed impact, user/tester value, unresolved blockers, and physical-device status. A question is not release approval. Even an explicit request to release now first runs this audit. When every gate passes, no arbitrary calendar delay is required; when a gate fails, fix it or explicitly hold the release instead of tagging first and adding evidence later.

A release requires all of the following:

- The delta since the latest tag has distributable user/tester value, or an urgent security, installation, or release-integrity fix.
- No unresolved P0/P1 exists in the release scope. Other roadmap gaps may remain only when release notes accurately preserve them as limitations.
- The version classification follows the table and every code, README, CHANGELOG, and release-note declaration agrees.
- `ci-complete` passed for the exact version-PR head and `main-accepted` passed for the exact squash-merged commit.
- The immutable candidate contains all expected assets and checksums. Claims about physical-camera compatibility additionally require matching device evidence; otherwise wording must remain automated-only and pending physical validation.

### Promotion Path

- Every pull request runs security, version consistency, evidence/helper tests, and actionlint. A commit-pinned `dorny/paths-filter` selects only affected Android, iOS, Bridge, Windows, and Simulator jobs; simulator changes also run every fake-camera consumer, while workflow changes run the complete matrix. New commits cancel stale runs. `ci-complete` is the only required check and verifies both required successes and intentional skips.
- `Main acceptance` finds the merged PR through the GitHub API, fetches its head, compares Git tree SHAs, and requires the latest exact-head PR workflow to have succeeded. Non-version merges stop after this provenance check and do not generate disposable release bundles.
- When the declared product version changed, `Main acceptance` reuses the tested Bridge and Windows artifacts, builds only the stable-signed Android APK that needs repository secrets, records every filename, size, and SHA-256 in `BUILD-PROVENANCE.json`, and uploads `release-candidate-<main commit>`.
- A version tag must match all code and documentation declarations, point to an accepted `main` commit, and reuse that exact candidate. The release job verifies provenance hashes, adds `SHA256SUMS.txt`, and publishes the prerelease without rerunning the product test matrix.

### Release Checklist

1. Prepare the version in a dedicated PR, including both READMEs, CHANGELOG, and release notes.
2. Run `python scripts/release/verify-version.py --tag vX.Y.Z`.
3. Wait for `ci-complete`, squash merge, and wait for `main-accepted` on the resulting commit.
4. Create an annotated tag on that exact commit within the candidate's 14-day retention window.
5. Report the version as released only after `release-published` succeeds and the expected GitHub Release assets are visible.

Inspect existing worktrees, branches, PRs, and Actions runs before starting or rerunning work. Keep evidence, explicit non-goals, and physical-device status in every PR so follow-up exploration remains active without inflating the current completion claim.

The GitHub `main` ruleset requires only `ci-complete`, never individual platform jobs that path classification may intentionally skip. The repository permits squash merge only and automatically deletes merged remote branches. Treat drift between those GitHub settings and this document as a workflow defect to correct before claiming the process is active.
