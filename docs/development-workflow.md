# Development And Release Workflow

[繁體中文](#繁體中文) | [English](#english)

## 繁體中文

這套流程把「寫完程式」、「通過完整驗證」、「進入 main」與「真的發布」分成機器可判定的狀態。完整測試仍保留在 PR，但相同 Git tree 不會在 `main` 與 tag 再各跑一次。

### 狀態定義

| 狀態 | 唯一判定依據 | 不代表 |
| --- | --- | --- |
| Implemented | worktree 內已有實作與對應測試 | PR 已可合併 |
| PR ready | exact PR head 的 `ci-complete` 成功 | 已進入 `main` |
| Main accepted | `Main acceptance / main-accepted` 成功並產生 `release-candidate-<commit>` | 已建立 Release |
| Released | `Release development preview / release-published` 成功，且 GitHub Release 資產存在 | 已完成實體相機驗證 |

模擬器、mock server、HTTP fixture、AVD 與 iPhone Simulator 都屬於可重現自動驗證，不能取代實體 EOS 相機／手機／USB 路徑的驗證紀錄。

### PR 階段

- `.github/workflows/android.yml` 只由 pull request 觸發。
- 同一 PR 推入新 commit 時，舊 run 會取消，避免同時測試已過時的 SHA。
- 完整矩陣包含秘密掃描、版本與 workflow helper、Android unit/UI API 34/UI API 36、Simulator、Desktop Bridge、Windows standalone、Swift Core 與 iOS App/UI。
- Desktop Bridge wheel/source distribution 與 Windows executable 由已通過其測試的同一 job 建立，並保存為該 run 的 immutable candidate artifacts。
- `ci-complete` 依賴所有必需 job；只有它成功才能稱為 PR ready。

### Main 接受階段

- `.github/workflows/main.yml` 不重跑完整矩陣。
- provenance verifier 透過 GitHub API 找出 squash merge 所屬 PR，fetch 該 PR head，並比較兩者 Git tree SHA。
- verifier 接著要求 exact PR head 最新的 `CI` workflow run 已完成且成功；不同 tree、失敗 run、進行中 run 或 direct push 都會拒絕。
- workflow 從該成功 run 下載 immutable Bridge／Windows candidates，只另外建置需要 repository secrets 的固定簽章 Android APK。
- 四個檔案的名稱、大小與 SHA-256 會寫入 `BUILD-PROVENANCE.json`，再一起上傳為 `release-candidate-<main commit>`。

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

## English

This workflow gives machine-verifiable meanings to implemented, fully validated, accepted on `main`, and actually released. The complete matrix remains on pull requests, while the same Git tree is promoted instead of retested on both `main` and a tag.

### State Model

| State | Authoritative evidence | Does not mean |
| --- | --- | --- |
| Implemented | The worktree contains the implementation and focused tests | The PR is mergeable |
| PR ready | `ci-complete` succeeded for the exact PR head | The change is on `main` |
| Main accepted | `Main acceptance / main-accepted` succeeded and produced `release-candidate-<commit>` | A GitHub Release exists |
| Released | `Release development preview / release-published` succeeded and release assets exist | Physical-camera validation is complete |

Simulator, mock-server, HTTP-fixture, AVD, and iPhone Simulator results are deterministic automated evidence. They never replace a recorded physical EOS camera, phone, or USB validation.

### Promotion Path

- Pull requests run the complete security, Android, iOS, Bridge, Windows, and Simulator matrix. New commits cancel stale runs for the same PR. Tested Bridge and Windows jobs upload immutable candidate artifacts, and `ci-complete` is the single PR-ready gate.
- `Main acceptance` finds the merged PR through the GitHub API, fetches its head, compares Git tree SHAs, and requires the latest exact-head PR workflow to have succeeded. It reuses those candidate artifacts and only builds the stable-signed Android APK that requires repository secrets.
- The accepted bundle records every filename, size, and SHA-256 in `BUILD-PROVENANCE.json` and is uploaded as `release-candidate-<main commit>`.
- A version tag must match all code and documentation declarations, point to an accepted `main` commit, and reuse that exact candidate. The release job verifies provenance hashes, adds `SHA256SUMS.txt`, and publishes the prerelease without rerunning the product test matrix.

### Release Checklist

1. Prepare the version in a dedicated PR, including both READMEs, CHANGELOG, and release notes.
2. Run `python scripts/release/verify-version.py --tag vX.Y.Z`.
3. Wait for `ci-complete`, squash merge, and wait for `main-accepted` on the resulting commit.
4. Create an annotated tag on that exact commit within the candidate's 14-day retention window.
5. Report the version as released only after `release-published` succeeds and the expected GitHub Release assets are visible.

Inspect existing worktrees, branches, PRs, and Actions runs before starting or rerunning work. Keep evidence, explicit non-goals, and physical-device status in every PR so follow-up exploration remains active without inflating the current completion claim.
