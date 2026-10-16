# PocketMCP wireless-ADB unlock helper.
#
# Pairs Android wireless debugging, then applies the one-time grants that let
# the app manage its own accessibility + notification access. This bypasses
# OEM settings blocks (for example Samsung Auto Blocker refusing the
# accessibility toggle).
#
# Usage:
#   .\setup-wireless-adb.ps1
#   .\setup-wireless-adb.ps1 -Address 192.0.2.50 -PairPort 37000 -PairCode 123456 -ConnectPort 40000
#
# On the phone (Developer options -> Wireless debugging):
#   - "Pair device with pairing code" gives the pairing port + 6-digit code
#   - The main screen shows the connect port

param(
    [string]$Address = "",
    [int]$PairPort = 0,
    [string]$PairCode = "",
    [int]$ConnectPort = 0,
    [switch]$SkipNotificationListener
)

$ErrorActionPreference = "Stop"

function Resolve-Adb {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $fallbacks = @(
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
        (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk\platform-tools\adb.exe"),
        "C:\Android\platform-tools\adb.exe"
    )
    foreach ($candidate in $fallbacks) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    return $null
}

$adbExe = Resolve-Adb
if (-not $adbExe) {
    Write-Host "adb not found. Install Android platform-tools or add adb to PATH, then rerun." -ForegroundColor Red
    exit 1
}
Write-Host "Using adb: $adbExe" -ForegroundColor DarkGray

if (-not $Address) { $Address = Read-Host "Phone IP address (Wireless debugging screen)" }
$Address = $Address.Trim()

Write-Host ""
Write-Host "On the phone: Settings > Developer options > Wireless debugging" -ForegroundColor Cyan
Write-Host "  1) Pair device with pairing code  -> note the pairing port + 6-digit code" -ForegroundColor Cyan
Write-Host "  2) Main screen                    -> note the connect port" -ForegroundColor Cyan
Write-Host ""

if (-not $PairCode) { $PairCode = (Read-Host "Pairing code (6 digits)").Trim() }
if (-not $PairPort) { $PairPort = [int](Read-Host "Pairing port") }
if (-not $ConnectPort) { $ConnectPort = [int](Read-Host "Connect port") }

$serial = "{0}:{1}" -f $Address, $ConnectPort

Write-Host ""
Write-Host "==> Pairing with $Address`:$PairPort ..."
& $adbExe pair "$Address`:$PairPort" $PairCode
if ($LASTEXITCODE -ne 0) {
    Write-Host "Pairing failed. Keep the pairing dialog open and re-check the code/ports." -ForegroundColor Red
    exit 1
}

Write-Host "==> Connecting to $serial ..."
& $adbExe connect $serial | Out-Null
& $adbExe -s $serial wait-for-device
if ($LASTEXITCODE -ne 0) {
    Write-Host "Could not connect. Re-check the connect port on the wireless debugging screen." -ForegroundColor Red
    exit 1
}

Write-Host "==> Granting WRITE_SECURE_SETTINGS (one-time accessibility unlock) ..."
& $adbExe -s $serial shell pm grant com.pocketmcp android.permission.WRITE_SECURE_SETTINGS
if ($LASTEXITCODE -ne 0) {
    Write-Host "Grant failed. Is PocketMCP installed on this device?" -ForegroundColor Red
    exit 1
}

$component = "com.pocketmcp/com.pocketmcp.accessibility.PhoneAccessibilityService"
Write-Host "==> Enabling the accessibility service ..."
$current = (& $adbExe -s $serial shell settings get secure enabled_accessibility_services).Trim()
if (-not $current -or $current -eq "null") { $current = "" }
$services = @()
if ($current) { $services = $current -split ':' }
if ($services -notcontains $component) {
    $updated = if ($current) { "$current`:$component" } else { $component }
    & $adbExe -s $serial shell settings put secure enabled_accessibility_services $updated
}
& $adbExe -s $serial shell settings put secure accessibility_enabled 1

if (-not $SkipNotificationListener) {
    Write-Host "==> Enabling the notification listener ..."
    & $adbExe -s $serial shell cmd notification allow_listener com.pocketmcp/com.pocketmcp.tools.NotificationListenerService
}

Write-Host ""
Write-Host "Done." -ForegroundColor Green
Write-Host "Accessibility + notification access are enabled, and the app can toggle" -ForegroundColor Green
Write-Host "accessibility itself from Settings > System Permissions (no OEM screen)." -ForegroundColor Green
