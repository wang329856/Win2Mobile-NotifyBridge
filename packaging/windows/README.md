# Windows 打包与安装

Windows 10 2004（19041）或更新版本；.NET 10 SDK 用于构建。发布内容自包含，不要求目标机器预装 .NET。采集通过系统 UserNotificationListener，必须安装 MSIX 后从开始菜单启动；直接启动发布 exe 会提示缺少身份。桌面代理不安装 Windows 服务。

```powershell
./scripts/build_windows.ps1
./scripts/bootstrap_windows_packaging.ps1 # 未安装 Windows SDK 打包工具时
./scripts/package_windows.ps1
# 有匹配 Publisher 的受信任代码签名证书后：
./scripts/package_windows.ps1 -Publisher 'CN=Win2Mobile' -SigningCertificateThumbprint '<40 位证书指纹>'
./packaging/windows/Install-Win2Mobile.ps1 -PackagePath './packaging/windows/output/Win2Mobile-3.1.1.1-x64.msix'
```

MakeAppx 和 SignTool 从 PATH 或 Windows SDK `bin/<版本>/x64` 中查找，也可分别指定路径。工具缺失、构建失败、签名失败、信任验证失败均会停止并明确报错。默认只生成未签名 MSIX；不创建、不导入也不默认信任证书。签名证书应位于当前用户 My 证书存储，具有私钥、代码签名用途和与 Publisher 完全一致的 Subject。测试自签名证书的信任必须由操作者核对后自行处理；本项目脚本不更改信任库。

安装脚本为本次安装指定系统应用卷，不修改 Windows 的默认应用存储位置。2026-10-01 实机发现：默认应用卷为 D 盘时，C 盘 WindowsApps 下的目录联接触发启动错误 0x800701C0；首次安装改为系统卷后正常启动。已有非系统卷安装不会因再次运行安装脚本自动迁移，需单独处理；不要手工删除 WindowsApps 联接或放宽系统路径信任策略。

安装前从托盘退出正在运行的 Win2Mobile，否则 Windows 可能返回资源正在使用的错误 `0x80073D02`。安装后进入「授权通知访问」，允许系统授权提示。拒绝后需在 Windows 设置 → 隐私 → 通知中恢复。首次快照只建立基线；暂停期间不补发。应用过滤使用 AppUserModelId，不以可修改的显示名称作为标识。

数据位于当前用户 `%LOCALAPPDATA%/Win2Mobile`：bridge.db、证书和 desktop-settings.json。安装目录只含静态程序文件。开机启动使用 MSIX 的 `Win2MobileStartup` StartupTask，默认关闭，并遵守任务管理器禁用和系统策略；无需注册表 Run 项。

手机与电脑需处于可互通的局域网。在「配对手机」中选择正确 IPv4 地址，扫码后核对待请求设备并批准。二维码 120 秒有效，刷新后旧码失效。必要时由操作者允许私人网络 TCP 47721 入站；脚本不修改防火墙。系统托盘提供打开、暂停/恢复及退出。

官方依据：[通知监听器与 UI 线程授权](https://learn.microsoft.com/en-us/windows/apps/develop/notifications/app-notifications/notification-listener)、[桌面应用 MSIX 清单](https://learn.microsoft.com/en-us/windows/msix/desktop/desktop-to-uwp-manual-conversion)、[安装卷参数](https://learn.microsoft.com/en-us/powershell/module/appx/add-appxpackage)。本机签名安装与启动记录见 [实机安装记录](../../docs/local-installation-2026-10-01.md)。真实通知权限、StartupTask 和双设备网络连接仍待验证。

## 可信时间戳与证书到期

签名脚本默认使用 DigiCert RFC 3161 服务 `http://timestamp.digicert.com`，可通过 `-TimestampServer` 指定其他服务。时间戳必须在 MSIX 签名时加入；脚本使用 SHA-256，并验证签名与时间戳，失败即停止，不发布缺少时间戳的签名包。该 HTTP 地址是 DigiCert 官方提供的端点，返回的时间戳令牌有数字签名；不会向服务发送私钥或完整安装包。

有效期内签名并带可信时间戳的安装包，可以在签名证书到期后继续通过安装验证；自签名证书仍须由安装电脑信任。已经成功安装的应用不会仅因签名证书到期而停止运行。时间戳不允许使用过期证书签署新版本，也不绕过证书吊销、包篡改或系统信任策略。后续发布需保持有效签名证书，并保持 Publisher 与包身份一致；更换自签名证书时需安排用户信任新证书。

依据：[微软 MSIX 签名说明](https://learn.microsoft.com/en-us/windows/msix/package/signing-package-overview)、[DigiCert 时间戳端点](https://knowledge.digicert.com/solution/troubleshooting-timestamping-problems)。