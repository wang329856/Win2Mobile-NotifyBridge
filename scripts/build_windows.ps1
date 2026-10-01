[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')][string]$Configuration = 'Release',
    [string]$DotNetPath,
    [string]$OutputDirectory,
    [switch]$BuildOnly
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$env:DOTNET_CLI_HOME = Join-Path $repoRoot '.tools/dotnet-home'
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$env:DOTNET_GENERATE_ASPNET_CERTIFICATE = 'false'
$project = Join-Path $repoRoot 'src/Bridge.Windows/Bridge.Windows.csproj'
if (-not $DotNetPath) {
    $bundled = Join-Path $repoRoot '.tools/dotnet/dotnet.exe'
    if (Test-Path -LiteralPath $bundled) { $DotNetPath = $bundled }
    else { $DotNetPath = (Get-Command dotnet -ErrorAction Stop).Source }
}
if (-not (Test-Path -LiteralPath $DotNetPath)) { throw "找不到 .NET 10 SDK：$DotNetPath" }
& $DotNetPath --version
if ($LASTEXITCODE -ne 0) { throw '.NET SDK 检查失败。' }
if ($BuildOnly) {
    & $DotNetPath build $project -c $Configuration
    if ($LASTEXITCODE -ne 0) { throw "Windows 构建失败，退出码 $LASTEXITCODE" }
} else {
    if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repoRoot 'src/Bridge.Windows/bin/publish/win-x64' }
    & $DotNetPath publish $project -c $Configuration -r win-x64 --self-contained true -p:PublishSingleFile=false -p:PublishTrimmed=false -o $OutputDirectory
    if ($LASTEXITCODE -ne 0) { throw "Windows 发布失败，退出码 $LASTEXITCODE" }
    if (-not (Test-Path -LiteralPath (Join-Path $OutputDirectory 'Win2Mobile.exe'))) { throw '发布输出缺少 Win2Mobile.exe。' }
    Write-Output "已发布自包含 Windows x64 程序：$OutputDirectory"
    Write-Output '通知采集需安装带 userNotificationListener 权限的 MSIX，请继续运行 package_windows.ps1。'
}
