# Build the C# NotificationForwarder as a self-contained Windows EXE
param(
    [string]$Configuration = "Release",
    [string]$Runtime = "win-x64",
    [string]$OutputDir = "$PSScriptRoot/../dist/NotificationService"
)

$ErrorActionPreference = "Stop"
Set-Location "$PSScriptRoot/../src/NotificationService"

Write-Host "=== Building NotificationForwarder ===" -ForegroundColor Cyan
Write-Host "Configuration: $Configuration"
Write-Host "Runtime: $Runtime"
Write-Host "Output: $OutputDir"
Write-Host ""

Write-Host "Restoring packages..." -ForegroundColor Yellow
dotnet restore

Write-Host "Publishing..." -ForegroundColor Yellow
dotnet publish `
    --configuration $Configuration `
    --runtime $Runtime `
    --self-contained true `
    --output $OutputDir `
    -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true `
    -p:DebugType=none

$exePath = Join-Path $OutputDir "NotificationForwarder.exe"
if (Test-Path $exePath) {
    $size = [math]::Round((Get-Item $exePath).Length / 1MB, 1)
    Write-Host ""
    Write-Host "=== Build successful ===" -ForegroundColor Green
    Write-Host "EXE: $exePath"
    Write-Host "Size: ${size}MB"
    Write-Host ""
    Write-Host "To run as a console app:"
    Write-Host "  $exePath" -ForegroundColor White
    Write-Host ""
    Write-Host "To install as a Windows Service (admin):"
    Write-Host '  New-Service -Name "NotificationForwarder" -BinaryPathName "' + $exePath + '" -DisplayName "Windows Notification Forwarder" -StartupType Automatic'
    Write-Host "  Start-Service NotificationForwarder"
} else {
    Write-Host "=== Build FAILED ===" -ForegroundColor Red
    exit 1
}
