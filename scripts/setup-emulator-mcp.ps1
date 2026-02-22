param(
    [string]$Serial = "",
    [int]$HostPort = 8080,
    [int]$DevicePort = 8080
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-AdbPath {
    $fromPath = @(Get-Command adb -ErrorAction SilentlyContinue | Where-Object { $_.Source }) | Select-Object -First 1
    if ($fromPath) {
        return [string]$fromPath.Source
    }

    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe"
    )

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return [string]$candidate
        }
    }

    throw "adb not found. Install Android SDK Platform-Tools or set ANDROID_SDK_ROOT."
}

function Invoke-Adb {
    param(
        [string]$AdbPath,
        [string[]]$Arguments
    )

    $quotedArgs = $Arguments | ForEach-Object {
        if ($_ -match '\s') { '"' + $_ + '"' } else { $_ }
    }
    $cmdLine = '"' + $AdbPath + '" ' + ($quotedArgs -join ' ')
    & "$env:ComSpec" /d /s /c $cmdLine
}

function Get-RunningEmulatorSerial {
    param(
        [string]$AdbPath
    )

    $rowsRaw = Invoke-Adb -AdbPath $AdbPath -Arguments @("devices")
    $rows = @()
    if ($null -ne $rowsRaw) {
        $rows = @($rowsRaw -split "`r?`n" | Where-Object { $_ -and $_.Trim() })
    }
    $devices = @()

    foreach ($row in $rows) {
        $line = [string]$row
        if ($line -match "^(\S+)\s+device$") {
            $devices += $Matches[1]
        }
    }

    $emulators = @($devices | Where-Object { $_ -like "emulator-*" })
    if ($emulators.Count -eq 0) {
        throw "No running Android emulator found. Start an AVD first."
    }

    return $emulators[0]
}

$adb = [string](Resolve-AdbPath)
Write-Host "Using adb: $adb"

Invoke-Adb -AdbPath $adb -Arguments @("start-server") | Out-Null

if (-not $Serial) {
    $Serial = Get-RunningEmulatorSerial -AdbPath $adb
}

Write-Host "Using emulator: $Serial"

Invoke-Adb -AdbPath $adb -Arguments @("-s", $Serial, "forward", "tcp:$HostPort", "tcp:$DevicePort") | Out-Null

$forwardList = Invoke-Adb -AdbPath $adb -Arguments @("-s", $Serial, "forward", "--list")
Write-Host ""
Write-Host "Forward ready:"
$forwardList | Where-Object { $_ -match "$Serial" } | ForEach-Object { Write-Host "  $_" }

Write-Host ""
Write-Host "Use this MCP URL in your config:"
Write-Host "  http://127.0.0.1:$HostPort/mcp"
