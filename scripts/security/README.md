# Git secret-scan hooks

The hooks require `pwsh` (PowerShell 7) on `PATH`, or the existing
`powershell.exe` fallback on Windows. Installation remains explicit:

```powershell
./scripts/security/install-git-hooks.ps1
```

The scanner supports the existing Windows x64 package and Linux x64. Other
platforms fail closed. Both use Gitleaks 8.30.1, the unchanged `.gitleaks.toml`,
redacted output, staged pre-commit scanning, and every outgoing pre-push range.

Windows retains its existing per-user cache and ZIP checksum verification on
installation. Linux keeps the official tarball under
`.codex/toolchain/gitleaks/8.30.1/linux_x64/`, verifies its pinned SHA-256 on every
scan, and extracts a fresh executable before use. A missing tool, unavailable
download, invalid checksum, extraction failure, or scanner error blocks the
hook; an old executable is never a fallback for a failed Linux installation.
The tarball must come from the official Gitleaks release:

https://github.com/gitleaks/gitleaks/releases/tag/v8.30.1

## Linux hook integration tests

Tests use temporary local repositories, the actual `.githooks` entry points,
and synthetic fixtures. Nothing is pushed or uploaded. Provide PowerShell on
`PATH` and a copy of the official archive (the suite checks its pinned hash):

```sh
OPEN_EOS_TEST_GITLEAKS_ARCHIVE=/path/to/gitleaks_8.30.1_linux_x64.tar.gz \
  python3 -m unittest discover -s scripts/security/tests -p 'test_*.py' -v
```

Tests exercise clean and secret-bearing staged changes, every commit in outgoing
ranges (including a secret removed by a later commit), multiple updated refs,
new branches, and fail-closed download/checksum/extraction/execution errors.
Download errors are injected inside the test PowerShell process; no production
scanner override or bypass is added. The suite requires Linux x64 and fails
rather than silently skipping when prerequisites are missing.
