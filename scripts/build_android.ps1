[CmdletBinding()]
param(
    [ValidateSet('debug', 'release')][string]$Configuration = 'debug',
    [string]$JdkHome = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-21' }),
    [string]$AndroidSdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }),
    [string]$GradleUserHome,
    [string]$GradleInitScript,
    [string]$OutputDirectory,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
if ($Configuration -eq 'release') { throw 'Release signing is not configured. Use -Configuration debug for an installable debug APK; configure a private signing key before building a release.' }
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
try {
    $env:JAVA_HOME = $JdkHome
    $env:ANDROID_HOME = $AndroidSdk
    $env:ANDROID_SDK_ROOT = $AndroidSdk
    $env:GRADLE_USER_HOME = $GradleUserHome
    $gradleArguments = @('testDebugUnitTest', 'lintDebug', 'assembleDebug', '--no-daemon', '--console=plain', '--max-workers=2')
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
    $sourceApk = Join-Path $androidProject 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path -LiteralPath $sourceApk)) { throw "Gradle completed but APK is missing: $sourceApk" }
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $destination = Join-Path $OutputDirectory 'Win2Mobile-3.0.0-debug.apk'
    Copy-Item -LiteralPath $sourceApk -Destination $destination -Force
    Write-Output "APK: $destination"
    Write-Output 'Unit-test results: src/android/app/build/test-results/testDebugUnitTest'
    Write-Output 'Lint report: src/android/app/build/reports/lint-results-debug.html'
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:ANDROID_HOME = $oldAndroidHome
    $env:ANDROID_SDK_ROOT = $oldSdkRoot
    $env:GRADLE_USER_HOME = $oldGradleHome
}
