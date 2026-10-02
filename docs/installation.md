# 首次安装与使用

[返回项目介绍](../README.md) · [跨网使用](relay.md) · [隐私说明](../PRIVACY.md)

本教程适用于最新版 **3.1.2**。准备一台 Windows x64 电脑（Windows 10 build 19041 或更新）和一部 Android 手机（Android 8.0 或更新）。安装包自带运行时，无需安装开发工具。

## 1. 下载两端安装包

打开 [最新版 Release](https://github.com/wang329856/Win2Mobile-NotifyBridge/releases/latest)，下载：

- 电脑：`Win2Mobile-Setup-x64.zip`，解压到普通文件夹。
- 手机：`Win2Mobile-3.1.2-release.apk`，下载到 Android 手机。

不要下载 Source code，它是源码。Release 附带 `SHA256SUMS.txt`，可用 PowerShell 的 `Get-FileHash -Algorithm SHA256` 核对下载文件。

## 2. 安装电脑端

### 核验并信任证书

Windows 安装包使用自签名证书，首次安装需要手动信任。打开解压目录中的 `Win2Mobile-LocalDevelopment.cer`，核对：

| 字段 | 当前发布信息 |
| --- | --- |
| 发布者 | `CN=Win2Mobile` |
| 证书指纹 | `81D47A2CADFAEEA35CFB47F1D18C2DA9F1193C19` |
| 证书文件 SHA-256 | `BD5EC3A6846D2AD44301A4A2DEB312F04FB862BB103A91155A7E89BAFF99941F` |

确认来源和信息后，点击「安装证书」→「本地计算机」，按系统提示授予管理员权限，手动选择 **「受信任人 / Trusted People」**。不要选择「受信任的根证书颁发机构」。项目安装脚本不会自动导入证书。可参照 [微软 MSIX 证书说明](https://learn.microsoft.com/en-us/windows/msix/package/create-certificate-package-signing)。

### 安装 MSIX

双击解压目录中的 `Win2Mobile-Setup-x64.msix`，按提示安装。

如果 Windows 的应用默认安装位置不是系统盘，建议在解压目录打开**当前用户的普通 PowerShell**，通过以下命令指定系统应用卷：

```powershell
$systemVolumes = @(Get-AppxVolume | Where-Object IsSystemVolume)
if ($systemVolumes.Count -ne 1) { throw '未找到唯一的系统应用卷。' }
Add-AppxPackage -Path '.\Win2Mobile-Setup-x64.msix' -Volume $systemVolumes[0]
```

允许运行本地脚本时，也可使用套件里的 `Install-Win2Mobile.ps1`，它会检查签名并选择系统卷：

```powershell
.\Install-Win2Mobile.ps1 -PackagePath '.\Win2Mobile-Setup-x64.msix'
```

### 启动并授权

从开始菜单打开 **Win2Mobile**，点击「授权通知访问」并允许系统提示。拒绝后可在 Windows 设置 → 隐私 → 通知中恢复，具体名称随系统版本略有不同。

必须从安装后的应用启动；直接运行 EXE 无法采集系统通知。

## 3. 安装手机端

1. 打开下载的 APK，按系统提示允许当前浏览器或文件管理器安装此应用。
2. 完成安装后打开 Win2Mobile，允许通知权限。
3. 点击扫码功能时允许相机访问。

不需要安装 ntfy App，也不需要手机通知监听、短信或通讯录权限。

## 4. 扫码连接电脑

1. 手机与电脑连接到可互通的同一局域网。
2. 电脑打开「连接手机」，选择手机能够访问的网卡地址，显示二维码。
3. 手机点击「扫码连接电脑」，在 120 秒内扫码；过期时在电脑刷新。
4. 在电脑核对手机名称并批准请求。
5. 点击电脑「发送测试通知」，确认手机收到，再产生一条新的邮件或日程等系统通知，验证真实采集。

启动时已经存在的 Windows 通知只作为基线，不会批量转发。局域网直连无需互联网；连不上时检查私人网络的 TCP **47721** 入站权限和 Wi-Fi 隔离。

不在同一局域网时，按 [跨网教程](relay.md) 启用中转并远程配对。

## 5. 后台与日常使用

电脑需持续运行。关闭窗口后从系统托盘打开；想随登录启动，可在设置里启用。

手机通过前台服务接收，按应用诊断提示检查电池优化与厂商后台限制。手机暂停接收保留消息与进度，继续后补收仍在电脑队列内的内容；电脑暂停采集期间的通知不会补发。

可在电脑「规则」中屏蔽应用，在手机搜索、筛选或删除消息。「开始新一轮接收」会清空手机列表。离开家中网络后想继续接收，请提前按 [跨网教程](relay.md) 配置并验证移动数据接收。

## 常见问题

| 现象 | 处理方法 |
| --- | --- |
| MSIX 提示不信任发布者 | 检查下载来源与证书指纹，按上文将公钥证书导入受信任人 |
| 安装错误 `0x80073D02` | 从托盘退出正在运行的 Win2Mobile 后再试 |
| 启动错误 `0x800701C0` | 检查是否安装在非系统应用卷，首次安装建议使用上文系统卷命令；不要手工删除 WindowsApps 目录 |
| 已安装但没有真实通知 | 从开始菜单启动并授权通知访问，确认电脑未暂停、来源未屏蔽；让应用产生新的系统 Toast |
| 扫码超时或无法连接 | 刷新二维码、选择正确网卡，检查 TCP 47721、访客 Wi-Fi / AP 隔离及 VPN 的 LAN 策略 |
| 息屏后接收延迟 | 查看前台服务、通知权限、电池优化与厂商后台策略 |
| 跨网失败或 HTTP 429 | 确认两端能访问中转，查看限流状态；见 [服务可用性与限额](relay.md#服务可用性与限额) |

需要帮助时使用 [反馈模板](https://github.com/wang329856/Win2Mobile-NotifyBridge/issues/new/choose)，截图与日志先脱敏。
