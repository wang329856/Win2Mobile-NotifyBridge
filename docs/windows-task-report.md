# Windows 模块实施报告

状态：Windows编译、自包含发布、桌面采集状态测试、MakeAppx默认schema校验及未签名MSIX生成均已通过。签名安装、系统通知授权和真实双设备运行尚未验证。

## 文件范围

- `src/Bridge.Windows/Bridge.Windows.csproj`：.NET 10、Windows 19041、WPF/WinForms 托盘、QRCoder 和核心/传输引用。
- `src/Bridge.Windows/App.xaml*`、`MainWindow.xaml*`：中文权限与服务状态、配对二维码与地址选择、批准/拒绝、设备撤销、近期记录与详情、应用过滤、启动任务、系统托盘。
- `src/Bridge.Windows/NotificationCapture.cs`：真实 WPF UI Dispatcher 调用 UserNotificationListener.RequestAccessAsync；权限检查、NotificationChanged + 15 秒快照补偿、现有通知启动基线、暂停恢复时间边界、不依赖旧采集 exe。
- `src/Bridge.Windows/DesktopSettings.cs`、`LocalTimeConverter.cs`、`app.manifest`：用户 LocalApplicationData/Win2Mobile 设置、原子保存、当地时间展示、asInvoker。
- `scripts/build_windows.ps1`、`scripts/package_windows.ps1`：自包含 win-x64 发布、实际工具路径/退出码检查、全 MSIX 打包、可选显式证书签名与信任验证。
- `packaging/windows/AppxManifest.xml`：full trust WPF、uap3 userNotificationListener、默认关闭的 Win2MobileStartup。
- `packaging/windows/New-PackageAssets.ps1` 和 `Assets/*.png`：静态图标与生成器。
- `packaging/windows/Install-Win2Mobile.ps1`、`README.md`：显式安装入口与中文构建/签名/安装说明，不导入证书、不更改防火墙。

没有修改核心、传输、Android、旧版、根 README/global.json/.gitignore；没有提交或推送。

## 前期核验（历史记录）

- PowerShell Parser 对 4 个脚本解析：退出码 0、无语法错误。
- AppxManifest.xml 和 MainWindow.xaml 的 XML 结构读取：退出码 0。此检查不等价于 WPF 编译或 MSIX schema 验证。
- 实际运行 New-PackageAssets.ps1：成功生成 150×150、44×44、50×50 三个 PNG；已保存在 packaging/windows/Assets。
- `scripts/build_windows.ps1 -BuildOnly`：退出码 1，global.json 需要 .NET SDK 10.0.100，当前可用 SDK 只有 6.0.428 和 9.0.313，且根线程正在准备 .tools/dotnet。失败由脚本明确报告，未假称构建成功。
- 未发布时运行 package_windows.ps1：退出码 1，正确提示先生成 Windows 自包含发布文件。
- PATH 和常规 Windows SDK 工具目录未发现 MakeAppx/SignTool；尚未执行实际 MSIX schema 验证、签名或安装。

## 最新已执行核验

- 工作区SDK10.0.401，Microsoft.Data.Sqlite10.0.12。scripts/build_windows.ps1 自包含win-x64发布退出0，日志.artifacts/windows-publish.log没有漏洞警告，输出Win2Mobile.exe及完整依赖目录。
- scripts/test_windows.ps1退出0：基线、去重、保存失败重试、单条失败隔离、暂停/恢复时间边界、权限恢复与不可读旧条目不重放均通过。这是状态逻辑测试，不是WinRT系统授权实测。
- scripts/package_windows.ps1退出0：微软SDK BuildTools 10.0.26100.9169的MakeAppx完成默认schema检查和打包；生成packaging/windows/output/Win2Mobile-3.0.0.0-x64.msix，94,946,630字节，未签名。
- 运行自定义OutputDirectory位于PublishDirectory内部的负向检查，正确拒绝，未创建输出目录；成功打包后本次GUID staging目录已清理。
- 独立Windows静态审查原Important均修复并复核，见windows-review-report.md。通知回执由成功保存/主动过滤/持久重复确认，临时保存失败的可见Toast继续重试；新增「发送测试通知」检查配对链路。

## 实机待验证

1. 匹配Publisher的代码签名、显式安装、开始菜单激活与UI线程系统授权提示。
2. 真实Toast：授权/拒绝/撤销、启动基线、短时通知、重复事件、暂停边界、稳定AppUserModelId过滤；15秒补偿无法保证捕获在两次快照间出现又消失且事件未送达的Toast。
3. 系统托盘打开/关闭/退出，StartupTask启用/任务管理器禁用/登录恢复。
4. 多网卡地址切换、二维码刷新/过期、Android待请求批准拒绝、撤销即断开、局域网防火墙条件。

官方实现参考：Microsoft Learn [Notification listener](https://learn.microsoft.com/en-us/windows/apps/develop/notifications/app-notifications/notification-listener)、[桌面 MSIX 组件](https://learn.microsoft.com/en-us/windows/msix/desktop/desktop-to-uwp-manual-conversion)、[打包桌面应用激活](https://learn.microsoft.com/zh-cn/windows/apps/desktop/modernize/get-activation-info-for-packaged-apps)。
