[CmdletBinding()]
param([string]$DotNetPath)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$env:DOTNET_CLI_HOME = Join-Path $repoRoot '.tools/dotnet-home'
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$env:DOTNET_GENERATE_ASPNET_CERTIFICATE = 'false'
if (-not $DotNetPath) {
    $bundled = Join-Path $repoRoot '.tools/dotnet/dotnet.exe'
    $DotNetPath = if (Test-Path -LiteralPath $bundled) { $bundled } else { (Get-Command dotnet -ErrorAction Stop).Source }
}
$toolVersion = '10.0.26100.9169'
$directory = Join-Path $repoRoot '.tools/windows-sdk-tools'
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$project = Join-Path $directory 'Tools.csproj'
@"
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup><TargetFramework>net10.0</TargetFramework></PropertyGroup>
  <ItemGroup><PackageReference Include="Microsoft.Windows.SDK.BuildTools" Version="$toolVersion" PrivateAssets="all" /></ItemGroup>
</Project>
"@ | Set-Content -LiteralPath $project -Encoding utf8
& $DotNetPath restore $project
if ($LASTEXITCODE -ne 0) { throw 'Windows 打包工具恢复失败。' }
$package = Join-Path $repoRoot ".tools/nuget/packages/microsoft.windows.sdk.buildtools/$toolVersion"
$tools = @(Get-ChildItem -LiteralPath $package -Recurse -File | Where-Object { $_.Name -in @('makeappx.exe', 'signtool.exe') -and $_.FullName -match '[\\/]x64[\\/]' })
if (-not ($tools | Where-Object Name -eq 'makeappx.exe')) { throw '官方 SDK BuildTools 包中未找到 x64 MakeAppx。' }
$tools | ForEach-Object { Write-Output $_.FullName }
Write-Output '已准备微软官方 Windows SDK BuildTools；package_windows.ps1 将自动使用工作区工具。'
