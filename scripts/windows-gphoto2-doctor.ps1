[CmdletBinding()]
param(
    [switch]$Json
)

$wslCommand = Get-Command wsl.exe -ErrorAction SilentlyContinue
$usbipdCommand = Get-Command usbipd.exe -ErrorAction SilentlyContinue
$nativeGphotoCommand = Get-Command gphoto2 -ErrorAction SilentlyContinue
$configuredDistro = $env:OPEN_EOS_GPHOTO2_WSL_DISTRO
$distributions = @()
$lxssRoot = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Lxss'

if (Test-Path -LiteralPath $lxssRoot) {
    $distributions = @(
        Get-ChildItem -LiteralPath $lxssRoot -ErrorAction SilentlyContinue |
            ForEach-Object { (Get-ItemProperty -LiteralPath $_.PSPath -ErrorAction SilentlyContinue).DistributionName } |
            Where-Object { $_ } |
            Sort-Object -Unique
    )
}

$selectedDistro = if ($configuredDistro) { $configuredDistro } elseif ($distributions.Count -gt 0) { $distributions[0] } else { $null }
$wslGphotoAvailable = $false
$wslGphotoVersion = $null
$cameraOutput = $null

if ($wslCommand -and $selectedDistro -and $selectedDistro -in $distributions) {
    $wslArguments = @('--distribution', $selectedDistro, '--exec', 'gphoto2', '--version')
    $versionOutput = @(& $wslCommand.Source @wslArguments 2>&1)
    if ($LASTEXITCODE -eq 0) {
        $wslGphotoAvailable = $true
        $wslGphotoVersion = ($versionOutput | Where-Object { $_ } | Select-Object -First 1).ToString().Trim()
        $detectArguments = @('--distribution', $selectedDistro, '--exec', 'gphoto2', '--auto-detect')
        $cameraOutput = (@(& $wslCommand.Source @detectArguments 2>&1) -join [Environment]::NewLine).Trim()
    }
}

$nextSteps = [System.Collections.Generic.List[string]]::new()
if (-not $nativeGphotoCommand -and -not $wslCommand) {
    $nextSteps.Add('Install WSL with: wsl --install -d Ubuntu')
} elseif (-not $nativeGphotoCommand -and $distributions.Count -eq 0) {
    $nextSteps.Add('Install a WSL 2 distribution with: wsl --install -d Ubuntu')
} elseif (-not $nativeGphotoCommand -and -not $wslGphotoAvailable) {
    $nextSteps.Add("Install gphoto2 in '$selectedDistro': wsl -d $selectedDistro -- sudo apt update")
    $nextSteps.Add("Then run: wsl -d $selectedDistro -- sudo apt install gphoto2 usbutils")
}
if (-not $nativeGphotoCommand -and -not $usbipdCommand) {
    $nextSteps.Add('Install usbipd-win interactively: winget install --interactive --exact dorssel.usbipd-win')
}
if (-not $nativeGphotoCommand -and $wslGphotoAvailable -and $usbipdCommand -and $cameraOutput -notmatch 'Canon|usb:') {
    $nextSteps.Add('Attach the Canon device to WSL: usbipd list, usbipd bind --busid <id>, usbipd attach --wsl --busid <id>')
}

$result = [PSCustomObject]@{
    ready = [bool]($nativeGphotoCommand -or ($wslGphotoAvailable -and $usbipdCommand))
    selectedMode = if ($nativeGphotoCommand) { 'native' } elseif ($wslGphotoAvailable) { 'wsl' } else { 'unavailable' }
    nativeGphoto2 = [PSCustomObject]@{
        available = [bool]$nativeGphotoCommand
        path = if ($nativeGphotoCommand) { $nativeGphotoCommand.Source } else { $null }
    }
    wsl = [PSCustomObject]@{
        available = [bool]$wslCommand
        distributions = $distributions
        selectedDistribution = $selectedDistro
        gphoto2Available = $wslGphotoAvailable
        gphoto2Version = $wslGphotoVersion
    }
    usbipd = [PSCustomObject]@{
        available = [bool]$usbipdCommand
        path = if ($usbipdCommand) { $usbipdCommand.Source } else { $null }
    }
    cameraDetection = $cameraOutput
    nextSteps = @($nextSteps)
}

if ($Json) {
    $result | ConvertTo-Json -Depth 6
    exit 0
}

$result | Format-List ready, selectedMode
$result.nativeGphoto2 | Format-List
$result.wsl | Format-List
$result.usbipd | Format-List
if ($result.cameraDetection) {
    Write-Host 'Camera detection:'
    Write-Host $result.cameraDetection
}
if ($result.nextSteps.Count -gt 0) {
    Write-Host 'Next steps:'
    foreach ($step in $result.nextSteps) {
        Write-Host "- $step"
    }
}
