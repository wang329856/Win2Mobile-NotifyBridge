# Build the Flutter app as an Android APK
param(
    [string]$Configuration = "release"
)

$ErrorActionPreference = "Stop"
Set-Location "$PSScriptRoot/../src/notification_app"

Write-Host "=== Building NotifForward APK ===" -ForegroundColor Cyan
Write-Host "Configuration: $Configuration"
Write-Host ""

Write-Host "Cleaning..." -ForegroundColor Yellow
flutter clean

Write-Host "Getting dependencies..." -ForegroundColor Yellow
flutter pub get

Write-Host "Building APK..." -ForegroundColor Yellow
flutter build apk --$Configuration

$apkDir = "build/app/outputs/flutter-apk"
if (Test-Path $apkDir) {
    $apkFile = Get-ChildItem -Path $apkDir -Filter "app-$Configuration.apk" | Select-Object -First 1
    if ($apkFile) {
        $destDir = "$PSScriptRoot/../dist"
        New-Item -ItemType Directory -Force -Path $destDir | Out-Null
        $destPath = Join-Path $destDir "notification_app.apk"
        Copy-Item $apkFile.FullName $destPath -Force

        $size = [math]::Round($apkFile.Length / 1MB, 1)
        Write-Host ""
        Write-Host "=== Build successful ===" -ForegroundColor Green
        Write-Host "APK: $destPath"
        Write-Host "Size: ${size}MB"
    }
} else {
    Write-Host "=== Build FAILED ===" -ForegroundColor Red
    Write-Host "Check the Flutter output above for errors."
    exit 1
}
