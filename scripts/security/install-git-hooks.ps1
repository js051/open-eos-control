[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (& git rev-parse --show-toplevel).Trim()
if (-not $repoRoot) {
    throw "Run this script from inside the repository."
}

& git -C $repoRoot config --local core.hooksPath .githooks
if ($LASTEXITCODE -ne 0) {
    throw "Unable to configure Git hooks."
}

Write-Host "Git security hooks enabled for $repoRoot"
