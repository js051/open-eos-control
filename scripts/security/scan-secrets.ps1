[CmdletBinding()]
param(
    [ValidateSet("History", "Staged", "PrePush")]
    [string]$Mode = "History",
    [string]$RemoteName = "origin"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$version = "8.30.1"
$archiveName = "gitleaks_8.30.1_windows_x64.zip"
$archiveSha256 = "d29144deff3a68aa93ced33dddf84b7fdc26070add4aa0f4513094c8332afc4e"
$downloadUrl = "https://github.com/gitleaks/gitleaks/releases/download/v$version/$archiveName"
$repoRoot = (& git rev-parse --show-toplevel).Trim()

if (-not $repoRoot) {
    throw "Run this script from inside the repository."
}

$toolRoot = Join-Path ([Environment]::GetFolderPath("LocalApplicationData")) "OpenEOSControl\tools\gitleaks\$version"
$gitleaks = Join-Path $toolRoot "gitleaks.exe"

if (-not (Test-Path -LiteralPath $gitleaks)) {
    New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null
    $archivePath = Join-Path $toolRoot $archiveName
    Invoke-WebRequest -Uri $downloadUrl -OutFile $archivePath

    $actualSha256 = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSha256 -ne $archiveSha256) {
        Remove-Item -LiteralPath $archivePath -Force
        throw "Gitleaks checksum verification failed."
    }

    Expand-Archive -LiteralPath $archivePath -DestinationPath $toolRoot -Force
    Remove-Item -LiteralPath $archivePath -Force
}

$configPath = Join-Path $repoRoot ".gitleaks.toml"
$baseArguments = @(
    "git",
    $repoRoot,
    "--config", $configPath,
    "--redact",
    "--no-banner",
    "--no-color",
    "--timeout", "300"
)

function Invoke-SecretScan {
    param([string[]]$ExtraArguments = @())

    & $gitleaks @baseArguments @ExtraArguments
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

switch ($Mode) {
    "History" {
        Invoke-SecretScan
    }
    "Staged" {
        Invoke-SecretScan -ExtraArguments @("--staged")
    }
    "PrePush" {
        $zeroObject = "0" * 40
        $updates = [Console]::In.ReadToEnd() -split "\r?\n"

        foreach ($update in $updates) {
            if ([string]::IsNullOrWhiteSpace($update)) {
                continue
            }

            $fields = $update.Trim() -split "\s+"
            if ($fields.Count -ne 4) {
                throw "Unexpected pre-push input: $update"
            }

            $localObject = $fields[1]
            $remoteObject = $fields[3]
            if ($localObject -eq $zeroObject) {
                continue
            }

            if ($remoteObject -ne $zeroObject) {
                & git rev-parse --verify --quiet "$remoteObject`^{commit}" | Out-Null
                $remoteObjectKnown = $LASTEXITCODE -eq 0
            } else {
                $remoteObjectKnown = $false
            }

            if (-not $remoteObjectKnown) {
                $logOptions = "$localObject --not --remotes=$RemoteName"
            } else {
                $logOptions = "$remoteObject..$localObject"
            }

            Invoke-SecretScan -ExtraArguments @("--log-opts", $logOptions)
        }
    }
}
