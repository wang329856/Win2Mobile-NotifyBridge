[CmdletBinding()]
param([string]$DotNetPath)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$DotNetPath) {
    $local = Join-Path $repoRoot '.tools/dotnet/dotnet.exe'
    $DotNetPath = if (Test-Path -LiteralPath $local) { $local } else { (Get-Command dotnet -ErrorAction Stop).Source }
}
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$env:DOTNET_CLI_HOME = Join-Path $repoRoot '.tools/dotnet-home'
& $DotNetPath run --project (Join-Path $repoRoot 'tests/Bridge.Ntfy.Tests/Bridge.Ntfy.Tests.csproj') -c Release
if ($LASTEXITCODE -ne 0) { throw "ntfy runner failed with exit code $LASTEXITCODE" }
