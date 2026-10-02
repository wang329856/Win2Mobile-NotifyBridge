[CmdletBinding()]
param(
    [ValidateSet('debug', 'release')][string]$Configuration = 'debug',
    [string]$JdkHome = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-21' }),
    [string]$AndroidSdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }),
    [string]$GradleUserHome,
    [string]$GradleInitScript,
    [string]$OutputDirectory,
    [string]$SigningConfiguration = (Join-Path $env:LOCALAPPDATA 'Win2Mobile/Signing/android-release.signing.json'),
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$androidProject = Join-Path $repoRoot 'src\android'
if (-not $GradleUserHome) { $GradleUserHome = Join-Path $repoRoot '.tools\gradle-home' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repoRoot 'dist\android' }
$javaExecutable = Join-Path $JdkHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable)) { throw "JDK is missing: $javaExecutable. Pass -JdkHome with a JDK 21 installation." }
$androidJar = Join-Path $AndroidSdk 'platforms\android-36\android.jar'
if (-not (Test-Path -LiteralPath $androidJar)) { throw "Android platform 36 is missing: $androidJar. Pass -AndroidSdk with the installed SDK path." }
if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'build-tools\36.0.0\aapt2.exe'))) { throw 'Android build-tools 36.0.0 are required.' }
$oldJavaHome = $env:JAVA_HOME
$oldAndroidHome = $env:ANDROID_HOME
$oldSdkRoot = $env:ANDROID_SDK_ROOT
$oldGradleHome = $env:GRADLE_USER_HOME
$signingNames = @('WIN2MOBILE_ANDROID_KEYSTORE','WIN2MOBILE_ANDROID_KEY_ALIAS','WIN2MOBILE_ANDROID_STORE_PASSWORD','WIN2MOBILE_ANDROID_KEY_PASSWORD')
$oldSigning = @{}
foreach ($name in $signingNames) { $oldSigning[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    $env:JAVA_HOME = $JdkHome
    $env:ANDROID_HOME = $AndroidSdk
    $env:ANDROID_SDK_ROOT = $AndroidSdk
    $env:GRADLE_USER_HOME = $GradleUserHome
    if ($Configuration -eq 'release') {
        if (-not (Test-Path -LiteralPath $SigningConfiguration)) { throw 'Release signing configuration missing; create or supply a private release key first.' }
        Add-Type -AssemblyName System.Security
        $signing = Get-Content -LiteralPath $SigningConfiguration -Raw | ConvertFrom-Json
        if (-not (Test-Path -LiteralPath $signing.keyStore)) { throw 'Configured release keystore is missing.' }
        $plain = [Security.Cryptography.ProtectedData]::Unprotect([Convert]::FromBase64String($signing.passwordDpapi),$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
        try {
            $env:WIN2MOBILE_ANDROID_KEYSTORE = $signing.keyStore
            $env:WIN2MOBILE_ANDROID_KEY_ALIAS = $signing.keyAlias
            $env:WIN2MOBILE_ANDROID_STORE_PASSWORD = [Text.Encoding]::UTF8.GetString($plain)
            $env:WIN2MOBILE_ANDROID_KEY_PASSWORD = $env:WIN2MOBILE_ANDROID_STORE_PASSWORD
        } finally { [Array]::Clear($plain,0,$plain.Length) }
    }
    $variant = if ($Configuration -eq 'release') { 'Release' } else { 'Debug' }
    $gradleArguments = @("test${variant}UnitTest", "lint$variant", "assemble$variant", '--no-daemon', '--console=plain', '--max-workers=2')
    if ($GradleInitScript) {
        $GradleInitScript = [IO.Path]::GetFullPath($GradleInitScript)
        if (-not (Test-Path -LiteralPath $GradleInitScript -PathType Leaf)) { throw "Gradle init script is missing: $GradleInitScript" }
        $gradleArguments += @('--init-script', $GradleInitScript)
    }
    if ($Offline) { $gradleArguments += '--offline' }
    Push-Location -LiteralPath $androidProject
    try {
        & (Join-Path $androidProject 'gradlew.bat') @gradleArguments
        if ($LASTEXITCODE -ne 0) { throw "Android validation/build failed with exit code $LASTEXITCODE." }
    } finally { Pop-Location }
    $sourceApk = Join-Path $androidProject "app\build\outputs\apk\$Configuration\app-$Configuration.apk"
    if (-not (Test-Path -LiteralPath $sourceApk)) { throw "Gradle completed but APK is missing: $sourceApk" }
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $buildConfig = Get-Content -LiteralPath (Join-Path $androidProject 'app/build.gradle.kts') -Raw
    if ($buildConfig -notmatch 'versionName\s*=\s*"([^"]+)"') { throw 'Android versionName missing.' }
    $destination = Join-Path $OutputDirectory "Win2Mobile-$($Matches[1])-$Configuration.apk"
    if ($Configuration -eq 'release') {
        & (Join-Path $AndroidSdk 'build-tools/36.0.0/apksigner.bat') verify --verbose --print-certs $sourceApk
        if ($LASTEXITCODE -ne 0) { throw 'Release APK signature verification failed.' }
    }
    Copy-Item -LiteralPath $sourceApk -Destination $destination -Force
    Write-Output "APK: $destination"
    Write-Output "Unit-test results: src/android/app/build/test-results/test${variant}UnitTest"
    Write-Output "Lint report: src/android/app/build/reports/lint-results-$Configuration.html"
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:ANDROID_HOME = $oldAndroidHome
    $env:ANDROID_SDK_ROOT = $oldSdkRoot
    $env:GRADLE_USER_HOME = $oldGradleHome
    foreach ($name in $signingNames) { [Environment]::SetEnvironmentVariable($name,$oldSigning[$name],'Process') }
}
