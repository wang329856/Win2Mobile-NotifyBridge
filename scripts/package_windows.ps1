[CmdletBinding()]
param(
    [string]$PublishDirectory,
    [string]$OutputDirectory,
    [string]$MakeAppxPath,
    [string]$SignToolPath,
    [string]$Publisher = 'CN=Win2Mobile',
    [ValidatePattern('^\d+\.\d+\.\d+\.\d+$')][string]$Version = '3.1.1.1',
    [ValidatePattern('^[0-9a-fA-F]{40}$')][string]$SigningCertificateThumbprint
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $PublishDirectory) { $PublishDirectory = Join-Path $repoRoot 'src/Bridge.Windows/bin/publish/win-x64' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repoRoot 'packaging/windows/output' }
if (-not (Test-Path -LiteralPath (Join-Path $PublishDirectory 'Win2Mobile.exe'))) { throw '请先运行 scripts/build_windows.ps1 生成 Windows 自包含发布文件。' }
$PublishDirectory = (Resolve-Path -LiteralPath $PublishDirectory).Path.TrimEnd([char[]]@('\', '/'))
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory).TrimEnd([char[]]@('\', '/'))
$publishPrefix = $PublishDirectory + [IO.Path]::DirectorySeparatorChar
if ($OutputDirectory.Equals($PublishDirectory, [StringComparison]::OrdinalIgnoreCase) -or $OutputDirectory.StartsWith($publishPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw '打包输出目录不能等于或位于发布目录内部，以免把 staging 目录递归复制进安装包。'
}
function Find-SdkTool([string]$fileName, [string]$explicitPath) {
    if ($explicitPath) {
        if (-not (Test-Path -LiteralPath $explicitPath)) { throw "工具不存在：$explicitPath" }
        return (Resolve-Path -LiteralPath $explicitPath).Path
    }
    $command = Get-Command $fileName -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $localTools = Join-Path $repoRoot '.tools/nuget/packages/microsoft.windows.sdk.buildtools'
    if (Test-Path -LiteralPath $localTools) {
        $tool = Get-ChildItem -LiteralPath $localTools -Recurse -File -Filter $fileName | Where-Object { $_.FullName -match '[\\/]x64[\\/]' } | Sort-Object FullName -Descending | Select-Object -First 1
        if ($tool) { return $tool.FullName }
    }
    $kits = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits/10/bin'
    $candidates = @(Get-ChildItem -LiteralPath $kits -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending)
    foreach ($candidate in $candidates) {
        $path = Join-Path $candidate.FullName "x64/$fileName"
        if (Test-Path -LiteralPath $path) { return $path }
    }
    throw "未找到 $fileName。请运行 scripts/bootstrap_windows_packaging.ps1，安装 Windows SDK，或显式指定工具路径。"
}
$makeAppx = Find-SdkTool 'MakeAppx.exe' $MakeAppxPath
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$stage = Join-Path $OutputDirectory ('stage-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
try {
Copy-Item -Path (Join-Path $PublishDirectory '*') -Destination $stage -Recurse
[xml]$manifest = Get-Content -LiteralPath (Join-Path $repoRoot 'packaging/windows/AppxManifest.xml') -Raw -Encoding utf8
$manifest.Package.Identity.SetAttribute('Publisher', $Publisher)
$manifest.Package.Identity.SetAttribute('Version', $Version)
$manifest.Save((Join-Path $stage 'AppxManifest.xml'))
& (Join-Path $repoRoot 'packaging/windows/New-PackageAssets.ps1') -Destination (Join-Path $stage 'Assets')
$package = Join-Path $OutputDirectory "Win2Mobile-$Version-x64.msix"
& $makeAppx pack /d $stage /p $package /o
if ($LASTEXITCODE -ne 0) { throw "MakeAppx 打包失败，退出码 $LASTEXITCODE" }
if (-not (Test-Path -LiteralPath $package)) { throw '打包器未生成 MSIX 文件。' }
if ($SigningCertificateThumbprint) {
    $certificate = Get-Item -LiteralPath "Cert:/CurrentUser/My/$SigningCertificateThumbprint" -ErrorAction Stop
    if (-not $certificate.HasPrivateKey) { throw '签名证书缺少私钥。' }
    if ($certificate.Subject -ne $Publisher) { throw '签名证书 Subject 必须与 MSIX Publisher 完全一致。' }
    $signTool = Find-SdkTool 'SignTool.exe' $SignToolPath
    & $signTool sign /fd SHA256 /sha1 $SigningCertificateThumbprint /s My $package
    if ($LASTEXITCODE -ne 0) { throw "MSIX 签名失败，退出码 $LASTEXITCODE" }
    & $signTool verify /pa /v $package
    if ($LASTEXITCODE -ne 0) { throw 'MSIX 已执行签名，但签名信任验证失败。请核对证书有效期、用途与信任链；脚本不会导入或信任证书。' }
    Write-Output "已打包、签名并通过当前机器信任验证：$package"
} else {
    Write-Output "已生成未签名 MSIX：$package"
    Write-Output '尚未签名，不能宣称可安装。使用匹配 Publisher 的代码签名证书，通过 -SigningCertificateThumbprint 重新打包签名。'
}
} finally {
    $resolvedStage = [IO.Path]::GetFullPath($stage)
    $outputPrefix = $OutputDirectory + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedStage.StartsWith($outputPrefix, [StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolvedStage) -notmatch '^stage-[0-9a-f]{32}$') {
        throw '拒绝清理超出本次打包输出目录的 staging 路径。'
    }
    if (Test-Path -LiteralPath $resolvedStage) { Remove-Item -LiteralPath $resolvedStage -Recurse -Force }
}
