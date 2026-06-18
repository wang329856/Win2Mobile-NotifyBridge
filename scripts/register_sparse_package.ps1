# Register NotifForward as a sparse package to enable userNotificationListener capability
# Run as Administrator
param(
    [string]$PackageDir = "$PSScriptRoot\..\src\NotificationService\bin\Debug\net6.0-windows10.0.17763.0",
    [string]$ManifestPath = "$PSScriptRoot\..\src\NotificationService\Package.appxmanifest",
    [string]$CertSubject = "CN=NotifForward"
)

$ErrorActionPreference = "Stop"

Write-Host "=== NotifForward Sparse Package Registration ===" -ForegroundColor Cyan

# 1. Create placeholder assets if they don't exist
$assetsDir = "$PackageDir\Assets"
if (!(Test-Path $assetsDir)) { New-Item -ItemType Directory -Path $assetsDir -Force | Out-Null }

$placeholderPng = "$assetsDir\StoreLogo.png"
if (!(Test-Path $placeholderPng)) {
    # Create a minimal 1x1 PNG
    $png = [Convert]::FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
    [System.IO.File]::WriteAllBytes($placeholderPng, $png)
    Copy-Item $placeholderPng "$assetsDir\Square150x150Logo.png" -Force
    Copy-Item $placeholderPng "$assetsDir\Square44x44Logo.png" -Force
    Copy-Item $placeholderPng "$assetsDir\Wide310x150Logo.png" -Force
    Copy-Item $placeholderPng "$assetsDir\SplashScreen.png" -Force
    Write-Host "Created placeholder asset images"
}

# 2. Copy manifest to publish directory
Copy-Item $ManifestPath "$PackageDir\AppxManifest.xml" -Force
Write-Host "Manifest copied to $PackageDir"

# 3. Create self-signed certificate
$cert = Get-ChildItem Cert:\CurrentUser\My | Where-Object { $_.Subject -eq $CertSubject } | Select-Object -First 1
if (!$cert) {
    $cert = New-SelfSignedCertificate -Subject $CertSubject -Type CodeSigningCert -CertStoreLocation Cert:\CurrentUser\My
    Write-Host "Created self-signed certificate: $($cert.Thumbprint)"
} else {
    Write-Host "Using existing certificate: $($cert.Thumbprint)"
}

# 4. Register using sparse package API
$packageManager = New-Object Windows.Management.Deployment.PackageManager
$deploymentResult = $packageManager.RegisterPackageByUriAsync(
    [Uri]::new($PackageDir),
    $null, # dependency packages
    0      # no special deployment options
).GetAwaiter().GetResult()

if ($deploymentResult) {
    Write-Host "Package registration result: $deploymentResult" -ForegroundColor Green
} else {
    Write-Host "ERROR: Failed to register sparse package" -ForegroundColor Red
    Write-Host "Try manually: Add-AppxPackage -Path `"$PackageDir\AppxManifest.xml`""
}
