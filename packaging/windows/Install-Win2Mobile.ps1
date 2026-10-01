[CmdletBinding()]
param([Parameter(Mandatory)][string]$PackagePath)
$ErrorActionPreference = 'Stop'
$resolved = (Resolve-Path -LiteralPath $PackagePath).Path
if ([IO.Path]::GetExtension($resolved) -ne '.msix') { throw '安装入口仅接受 .msix 文件。' }
$signature = Get-AuthenticodeSignature -LiteralPath $resolved
if ($signature.Status -ne 'Valid') { throw "MSIX 签名尚未受到本机信任（$($signature.Status)）。请自行核验证书来源与发布者；此脚本不会信任证书或修改系统设置。" }
$systemVolumes = @(Get-AppxVolume | Where-Object IsSystemVolume)
if ($systemVolumes.Count -ne 1) { throw '未找到唯一的系统应用卷，无法确定安装位置。' }
# 非系统应用卷可能通过目录联接启动，触发 ERROR_UNTRUSTED_MOUNT_POINT。
# 仅为本次安装指定系统卷，不更改 Windows 的默认应用存储位置。
Add-AppxPackage -Path $resolved -Volume $systemVolumes[0] -ErrorAction Stop
Write-Output '已为当前用户安装 Win2Mobile。请从开始菜单启动，然后在程序中授权系统通知访问。'
