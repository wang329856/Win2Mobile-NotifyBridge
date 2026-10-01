[CmdletBinding()]
param([string]$DotNetPath)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$env:DOTNET_CLI_HOME = Join-Path $repoRoot '.tools/dotnet-home'
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
if (-not $DotNetPath) {
    $bundled = Join-Path $repoRoot '.tools/dotnet/dotnet.exe'
    $DotNetPath = if (Test-Path -LiteralPath $bundled) { $bundled } else { (Get-Command dotnet -ErrorAction Stop).Source }
}
& $DotNetPath run --project (Join-Path $repoRoot 'tests/Bridge.Windows.Tests/Bridge.Windows.Tests.csproj') -c Release
if ($LASTEXITCODE -ne 0) { throw "Windows 采集状态测试失败，退出码 $LASTEXITCODE" }
