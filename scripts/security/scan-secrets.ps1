[CmdletBinding()]
param(
    [ValidateSet("History", "Staged", "PrePush")]
    [string]$Mode = "History",
    [string]$RemoteName = "origin"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$version = "8.30.1"
$repoRoot = (& git rev-parse --show-toplevel).Trim()

if ($LASTEXITCODE -ne 0 -or -not $repoRoot) {
    throw "Run this script from inside the repository."
}

if ($env:OS -eq "Windows_NT") {
    # Preserve the existing Windows package and per-user cache.
    $archiveName = "gitleaks_${version}_windows_x64.zip"
    $archiveSha256 = "d29144deff3a68aa93ced33dddf84b7fdc26070add4aa0f4513094c8332afc4e"
    $toolRoot = Join-Path ([Environment]::GetFolderPath("LocalApplicationData")) "OpenEOSControl\tools\gitleaks\$version"
    $gitleaks = Join-Path $toolRoot "gitleaks.exe"
    if (-not (Test-Path -LiteralPath $gitleaks)) {
        New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null
        $archivePath = Join-Path $toolRoot $archiveName
        Invoke-WebRequest -Uri "https://github.com/gitleaks/gitleaks/releases/download/v$version/$archiveName" -OutFile $archivePath
        $actualSha256 = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualSha256 -ne $archiveSha256) {
            Remove-Item -LiteralPath $archivePath -Force
            throw "Gitleaks checksum verification failed."
        }
        Expand-Archive -LiteralPath $archivePath -DestinationPath $toolRoot -Force
        Remove-Item -LiteralPath $archivePath -Force
    }
} elseif ([Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([Runtime.InteropServices.OSPlatform]::Linux) -and
    [Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture -eq [Runtime.InteropServices.Architecture]::X64) {
    $archiveName = "gitleaks_${version}_linux_x64.tar.gz"
    # Pinned official release checksum; also used by the repository-history CI scan.
    $archiveSha256 = "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb"
    $toolRoot = Join-Path $repoRoot ".codex/toolchain/gitleaks/$version/linux_x64"
    New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null
    $archivePath = Join-Path $toolRoot $archiveName
    if (-not (Test-Path -LiteralPath $archivePath)) {
        Invoke-WebRequest -Uri "https://github.com/gitleaks/gitleaks/releases/download/v$version/$archiveName" -OutFile $archivePath
    }

    # Verify on every run, even when an older executable is already cached.
    # A failed download, checksum, extraction, or chmod must never use that executable.
    $actualSha256 = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSha256 -ne $archiveSha256) {
        Remove-Item -LiteralPath $archivePath -Force
        throw "Gitleaks checksum verification failed."
    }
    $extractPath = Join-Path $toolRoot ([Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $extractPath | Out-Null
    try {
        & tar -xzf $archivePath -C $extractPath gitleaks
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to extract the verified Gitleaks archive."
        }
        $extractedTool = Join-Path $extractPath "gitleaks"
        & chmod u+x $extractedTool
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to make Gitleaks executable."
        }
        $gitleaks = Join-Path $toolRoot "gitleaks"
        Move-Item -LiteralPath $extractedTool -Destination $gitleaks -Force
    } finally {
        Remove-Item -LiteralPath $extractPath -Recurse -Force
    }
} else {
    throw "Secret scanning supports Windows (x64 package) and Linux x64. This platform is unsupported."
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
