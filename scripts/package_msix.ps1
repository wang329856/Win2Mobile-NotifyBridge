# Build MSIX package for NotifForward
# Requires: Windows SDK (MakeAppx.exe, SignTool.exe)
param(
    [string]$BuildDir = "$PSScriptRoot\..\src\dist\NotifForward",
    [string]$OutputPath = "$PSScriptRoot\..\dist\NotifForward.msix"
)

$ErrorActionPreference = "Stop"

Write-Host "=== NotifForward MSIX Packaging ===" -ForegroundColor Cyan

# 1. Create assets directory with placeholder images
$assetsDir = "$BuildDir\Assets"
New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null

# Generate minimal PNG placeholders (1x1 blue pixel)
$pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg=="
$pngBytes = [Convert]::FromBase64String($pngBase64)
$icons = @("StoreLogo.png","Square150x150Logo.png","Square44x44Logo.png","Wide310x150Logo.png","SplashScreen.png")
foreach ($icon in $icons) {
    [IO.File]::WriteAllBytes("$assetsDir\$icon", $pngBytes)
}
Write-Host "Created placeholder assets"

# 2. Copy appsettings.json
Copy-Item "$PSScriptRoot\..\src\NotificationService\appsettings.json" "$BuildDir\" -Force

# 3. Create AppxManifest.xml
$manifest = @'
<?xml version="1.0" encoding="utf-8"?>
<Package
  xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
  xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
  xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
  xmlns:desktop="http://schemas.microsoft.com/appx/manifest/desktop/windows10">

  <Identity Name="NotifForward" Publisher="CN=NotifForward" Version="1.0.0.0" />
  <Properties>
    <DisplayName>NotifForward</DisplayName>
    <PublisherDisplayName>NotifForward</PublisherDisplayName>
    <Logo>Assets\StoreLogo.png</Logo>
  </Properties>
  <Dependencies>
    <TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.17763.0" MaxVersionTested="10.0.22621.0" />
  </Dependencies>
  <Resources><Resource Language="x-generate" /></Resources>
  <Applications>
    <Application Id="NotifForward" Executable="NotificationForwarder.exe" EntryPoint="Windows.FullTrustApplication">
      <uap:VisualElements DisplayName="NotifForward" Description="Windows Notification Forwarder"
        Square150x150Logo="Assets\Square150x150Logo.png" Square44x44Logo="Assets\Square44x44Logo.png" BackgroundColor="transparent">
        <uap:DefaultTile Wide310x150Logo="Assets\Wide310x150Logo.png" />
      </uap:VisualElements>
      <desktop:Extension Category="windows.fullTrustProcess" Executable="NotificationForwarder.exe" />
    </Application>
  </Applications>
  <Capabilities>
    <rescap:Capability Name="runFullTrust" />
    <rescap:Capability Name="userNotificationListener" />
  </Capabilities>
</Package>
'@
[IO.File]::WriteAllText("$BuildDir\AppxManifest.xml", $manifest, [Text.Encoding]::UTF8)
Write-Host "Created AppxManifest.xml"

# 4. Find MakeAppx.exe
$makeAppx = (Get-ChildItem -Path "${env:ProgramFiles(x86)}\Windows Kits\10\bin" -Recurse -Filter "MakeAppx.exe" -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
if (!$makeAppx) {
    $makeAppx = (Get-ChildItem -Path "${env:ProgramFiles}\Windows Kits\10\bin" -Recurse -Filter "MakeAppx.exe" -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
}

if (!$makeAppx) {
    Write-Host "ERROR: MakeAppx.exe not found. Install Windows SDK." -ForegroundColor Red
    Write-Host "https://developer.microsoft.com/en-us/windows/downloads/windows-sdk/" -ForegroundColor Yellow
    exit 1
}

Write-Host "MakeAppx: $makeAppx"

# 5. Create MSIX
$outputDir = Split-Path $OutputPath -Parent
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

& $makeAppx pack /d "$BuildDir" /p "$OutputPath" /overwrite 2>&1

if ($LASTEXITCODE -eq 0) {
    $size = [math]::Round((Get-Item $OutputPath).Length / 1MB, 1)
    Write-Host "MSIX created: $OutputPath (${size}MB)" -ForegroundColor Green
    Write-Host ""
    Write-Host "To install:" -ForegroundColor Yellow
    Write-Host "  1. Create self-signed cert (Admin PowerShell):" -ForegroundColor White
    Write-Host "     `$cert = New-SelfSignedCertificate -Subject 'CN=NotifForward' -Type CodeSigningCert -CertStoreLocation Cert:\CurrentUser\My" -ForegroundColor White
    Write-Host "  2. Sign the MSIX:" -ForegroundColor White
    Write-Host "     SignTool sign /fd SHA256 /a /f cert.pfx /p password `"$OutputPath`"" -ForegroundColor White
    Write-Host "  3. Install:" -ForegroundColor White
    Write-Host "     Add-AppxPackage -Path `"$OutputPath`"" -ForegroundColor White
} else {
    Write-Host "MSIX packaging FAILED" -ForegroundColor Red
}
