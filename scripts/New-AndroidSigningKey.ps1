[CmdletBinding()]
param(
    [string]$Destination = (Join-Path $env:LOCALAPPDATA 'Win2Mobile/Signing'),
    [string]$JdkHome = 'C:\Program Files\Java\jdk-21'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Security
$metadataPath = Join-Path $Destination 'android-release.signing.json'
$keyPath = Join-Path $Destination 'win2mobile-release.p12'
if (Test-Path -LiteralPath $metadataPath) { Write-Output "Existing signing configuration preserved: $metadataPath"; return }
if (Test-Path -LiteralPath $keyPath) { throw 'A signing key already exists without configuration; recover it instead of replacing it.' }
$keytool = Join-Path $JdkHome 'bin/keytool.exe'
if (-not (Test-Path -LiteralPath $keytool)) { throw 'JDK keytool not found.' }
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$passwordBytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
$password = [Convert]::ToBase64String($passwordBytes)
$oldPassword = $env:WIN2MOBILE_KEY_GENERATION_PASSWORD
try {
    $env:WIN2MOBILE_KEY_GENERATION_PASSWORD = $password
    & $keytool -genkeypair -keystore $keyPath -storetype PKCS12 -alias 'win2mobile-release' -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 -dname 'CN=Win2Mobile, OU=Release, O=Win2Mobile' -storepass:env WIN2MOBILE_KEY_GENERATION_PASSWORD -keypass:env WIN2MOBILE_KEY_GENERATION_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Release key creation failed.' }
    $plain = [Text.Encoding]::UTF8.GetBytes($password)
    try { $protected = [Security.Cryptography.ProtectedData]::Protect($plain,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser) }
    finally { [Array]::Clear($plain,0,$plain.Length) }
    @{ keyStore=[IO.Path]::GetFullPath($keyPath); keyAlias='win2mobile-release'; passwordDpapi=[Convert]::ToBase64String($protected) } | ConvertTo-Json | Set-Content -LiteralPath $metadataPath -Encoding utf8
    Write-Output "Release key created outside repository: $keyPath"
    Write-Output "Current-user protected configuration: $metadataPath"
} finally {
    $env:WIN2MOBILE_KEY_GENERATION_PASSWORD = $oldPassword
    [Array]::Clear($passwordBytes,0,$passwordBytes.Length)
    $password = $null
}
