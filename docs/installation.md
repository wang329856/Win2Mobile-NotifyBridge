# 安装、配对与升级

[返回项目介绍](../README.md) · [跨网教程](relay.md) · [隐私说明](../PRIVACY.md)

本教程以正式版 **3.1.2** 为例。要求 Windows **x64 / Windows 10 build 19041 或更新**，以及 **Android 8.0 或更新**。Windows 安装套件自带 .NET 运行时，无需安装开发 SDK；当前没有 iOS 客户端或 Windows ARM64 原生包。

## 下载与校验

从 [v3.1.2 Release](https://github.com/wang329856/Win2Mobile-NotifyBridge/releases/tag/v3.1.2) 下载：

| 文件 | 用途 |
| --- | --- |
| `Win2Mobile-Setup-x64.zip` | 推荐的 Windows 套件，包含 MSIX、公钥证书、安装脚本与说明 |
| `Win2Mobile-3.1.2-release.apk` | Android 正式安装包 |
| `SHA256SUMS.txt` | 发布附件的 SHA-256 清单 |

Release 页面自动生成的 Source code 是源码。首次安装 Windows 建议下载 ZIP，独立 MSIX 适合已信任证书的电脑。

在下载目录打开 PowerShell，分别计算文件哈希，与 Release 的清单核对：

```powershell
Get-FileHash -LiteralPath '.\Win2Mobile-Setup-x64.zip' -Algorithm SHA256
Get-FileHash -LiteralPath '.\Win2Mobile-3.1.2-release.apk' -Algorithm SHA256
```

哈希不一致时重新下载并核查来源。3.1.2 Windows 包包含可信时间戳与隐私修复。若你仍使用 3.1.1 或更早版本，请下载新版并按升级步骤安装；旧安装不会自动获得这些修复。

## 安装 Windows

### 1. 解压并退出旧程序

将 ZIP 解压到普通目录。若已运行 Win2Mobile，请从系统托盘选择退出；仅关闭窗口可能仍在后台运行。

### 2. 首次安装核验发布证书

3.1.2 使用自签名证书，尚未通过公共 CA 或 Microsoft Store 分发。附带 `Win2Mobile-LocalDevelopment.cer` 是公钥证书，不含私钥。

双击证书，核对以下信息，并与 [发布记录](releases/v3.1.2.md) 对照：

| 字段 | 3.1.2 发布信息 |
| --- | --- |
| Subject / 发布者 | `CN=Win2Mobile` |
| 证书指纹（SHA-1，证书标识） | `81D47A2CADFAEEA35CFB47F1D18C2DA9F1193C19` |
| 公钥证书文件 SHA-256 | `BD5EC3A6846D2AD44301A4A2DEB312F04FB862BB103A91155A7E89BAFF99941F` |
| 证书有效期 | 至 2026-12-31；本次更新包含可信时间戳 |

确认来源与信息后，点击「安装证书」→「本地计算机」，按提示授予管理员权限，手动选择 **「受信任人 / Trusted People」** 证书存储。不要选择「受信任的根证书颁发机构」。该操作使此电脑信任使用该证书签名的应用；项目安装脚本不会替你导入证书。

安装信任方式参见 [微软 MSIX 证书说明](https://learn.microsoft.com/en-us/windows/msix/package/create-certificate-package-signing)。可信时间戳与证书到期的区别见 [Windows 打包说明](../packaging/windows/README.md#可信时间戳与证书到期)。

### 3. 安装到系统应用卷

在解压目录打开 **当前用户的普通 PowerShell**。可以先检查签名：

```powershell
Get-AuthenticodeSignature -LiteralPath '.\Win2Mobile-Setup-x64.msix' |
    Select-Object Status, StatusMessage, SignerCertificate
```

核验并信任证书后，签名状态应为 `Valid`。运行以下内置命令安装到系统应用卷（通常为 C 盘），无需更改脚本执行策略：

```powershell
$systemVolumes = @(Get-AppxVolume | Where-Object IsSystemVolume)
if ($systemVolumes.Count -ne 1) { throw '未找到唯一的系统应用卷。' }
Add-AppxPackage -Path '.\Win2Mobile-Setup-x64.msix' -Volume $systemVolumes[0]
```

允许运行本地脚本的环境也可使用套件附带的安装脚本：

```powershell
.\Install-Win2Mobile.ps1 -PackagePath '.\Win2Mobile-Setup-x64.msix'
```

脚本检查签名并选择系统卷，不修改机器默认应用存储位置、证书信任或防火墙。已经安装到其他卷的应用不会因此自动迁移。

### 4. 启动并授权

从开始菜单打开 **Win2Mobile**，点击「授权通知访问」并允许系统提示。若曾拒绝，可在 Windows 设置 → 隐私 → 通知中恢复访问，具体名称随系统版本略有不同。

必须使用安装后的 MSIX 应用身份；解压并直接运行 EXE 无法采集真实系统通知。启动时已有通知只作为基线，验证时请产生新的 Toast 通知。

## 安装 Android

1. 将 `Win2Mobile-3.1.2-release.apk` 下载到手机，通过浏览器或文件管理器打开。
2. 按系统提示，允许当前来源安装此应用，然后完成安装。
3. 打开 Win2Mobile，允许通知权限；使用扫码时允许相机访问。
4. 需要后台接收时，在应用诊断入口检查电池优化与厂商后台限制。实际息屏接收请在自己的手机上验证。

不需要安装 ntfy App，也不需要为 Win2Mobile 授予手机通知监听、短信或通讯录权限。

开发者可在已配置 ADB 的电脑上安装：

```powershell
adb install -r '.\Win2Mobile-3.1.2-release.apk'
```

若报签名冲突，按下方升级说明处理；不要将覆盖失败解释为可以无损强行替换。

## 局域网首次配对

1. 手机与电脑连接到互相可达的局域网。无需访问互联网，也不需要开启跨网中转。
2. 电脑打开「连接手机」，选择手机可访问的 IPv4 地址，显示局域网二维码。
3. 手机点击「扫码连接电脑」，扫描二维码；二维码 120 秒有效，过期后在电脑刷新。
4. 在电脑核对设备名称并批准请求。拒绝、过期或未批准的请求无法获得设备凭据。
5. 点击电脑「发送测试通知」，确认手机收到；再让邮件、日程等应用产生新的 Windows 系统通知，验证真实采集。

若无法连接，先检查 Windows 私人网络的 TCP **47721** 入站权限与 Wi-Fi 隔离。程序不会自动修改防火墙或路由器设置。

跨网络首次配对、移动数据测试和自建服务设置见 [跨网教程](relay.md)。

## 升级与旧版迁移

- **Windows 同一包身份升级**：退出托盘程序后安装新 MSIX。身份与签名满足系统要求时覆盖安装并保留数据；更换发布证书时按新版本说明重新核验信任。
- **Android 正式版升级**：相同应用 ID 且使用相同发布密钥的 APK 可以覆盖安装。3.1.2 使用独立 release 签名，不能覆盖签名不同的 debug APK。
- **Android 签名冲突**：先保留自己需要的旧消息或配置，再卸载旧 App、安装正式包，重新授权和配对。卸载会删除应用私有数据；项目当前没有通用的一键迁移或历史导入功能。
- **旧 Flutter / 早期协议迁移**：旧 IP、topic 配置与订阅不会自动迁移，两端更新后需重新扫码；新版数据库不导入 Flutter 旧历史。
- **避免重复采集**：Windows 新包使用独立于旧实现的包身份，请退出旧代理，避免两套程序同时采集。

版本变化、下载与验证范围见 [更新日志](../CHANGELOG.md)。

## 常见问题

| 现象 | 检查与处理 |
| --- | --- |
| MSIX 提示不信任发布者，或安装脚本提示签名非 Valid | 核验下载来源、哈希和证书指纹，再按教程导入受信任人；检查系统时间及是否仍使用旧无时间戳包 |
| 安装错误 `0x80073D02` | 从托盘退出旧 Win2Mobile 后重试 |
| 启动错误 `0x800701C0` | 检查是否安装在非系统应用卷；重新安装不会自动迁移既有安装，处理前先保留所需数据，不要手工删除 WindowsApps 联接 |
| 权限未授权或 EXE 无法采集 | 从开始菜单启动 MSIX，授权系统通知访问；测试通知按钮成功不等于真实采集权限已可用 |
| 扫码超时或局域网不可达 | 刷新二维码、选正确网卡地址，检查 TCP 47721、访客 Wi-Fi / AP 隔离及 VPN 的 LAN 策略 |
| Android 提示签名冲突 | 参见升级说明；不同签名无法直接覆盖，卸载前保留所需数据 |
| 息屏后接收延迟 | 查看前台服务、通知权限、电池优化与厂商后台策略；不要将持续状态通知视为网络必定保持连接 |
| 跨网不可达、HTTP 429 或消息缺口 | 检查两端是否可访问中转、是否有限流及缓存过期；参见 [中转限制](relay.md#服务可用性与限额) |
| 暂停后内容没有补齐 | 区分电脑暂停采集与手机暂停接收；电脑已清理或旧会话内容不会补发 |

仍有问题时使用 [反馈模板](https://github.com/wang329856/Win2Mobile-NotifyBridge/issues/new/choose)，提供两端版本、连接模式和复现步骤，截图与日志先脱敏。
