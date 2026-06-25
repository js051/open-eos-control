param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs = @(":app:assembleDebug")
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$androidDir = Join-Path $repoRoot "android"
$studioJbr = "C:\Program Files\Android\Android Studio\jbr"
$javaExe = Join-Path $studioJbr "bin\java.exe"

if (-not (Test-Path -LiteralPath $javaExe)) {
    throw "Android Studio JDK was not found at $studioJbr"
}

$env:JAVA_HOME = $studioJbr
$env:PATH = "$studioJbr\bin;$env:PATH"

Push-Location $androidDir
try {
    & ".\gradlew.bat" @GradleArgs
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
finally {
    Pop-Location
}
