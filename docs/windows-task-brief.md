# Windows 任务

请先读 docs/protocol-v1.md。你的编辑范围仅 src/Bridge.Windows、packaging/windows、scripts/build_windows.ps1、scripts/package_windows.ps1，以及 docs/windows-task-report.md；不要改核心、传输、Android、根 global.json/README/.gitignore。不提交或推送。

目标：.NET 10 WPF 用户会话桌面 App，中文 UI，以安装授权和配对为核心。按协议列出的 API 消费根线程正在编写的 Bridge.Core 与 Bridge.Transport.Lan；可先写项目引用，编译时若这些文件尚未生成，继续完成独立文件。程序数据在用户 LocalApplicationData/Win2Mobile。Windows 10 build19041+。

实现实际 UI 线程 UserNotificationListener RequestAccessAsync，权限状态处理，NotificationChanged + 快照差异/低频补偿。首次启动默认以现有系统通知为基线，后续只发布新通知；对真实稳定 appId 过滤。声明 uap3 userNotificationListener。缺身份时明确引导安装打包入口，避免自动 UI 抓取。现有 native 采集可读作参考但不可依赖旧 exe。

实现托盘、暂停/恢复、状态与近期通知、详情、应用过滤、设备列表与撤销、可刷新二维码（QRCoder）、待请求批准/拒绝、设置/启动开机，文本和配对流程中文。实际状态由捕获权限和服务状态驱动。服务名和用户 AppData正确处理；多网卡让用户选择二维码地址。WPF 程序中若使用 WinForms NotifyIcon 处理命名冲突。

提供 build_windows.ps1、package_windows.ps1：新程序 self-contained win-x64 发布、正确 MSIX 或外部位置身份包清单和脚本；不默认信任证书/改系统设置，安装脚本可以让用户显式运行。自动打包必须检查实际工具与退出码；不能假称签名完成。动态用户配置不写安装目录。开始运行前已获用户许可，不再要求确认开发。

依赖 .NET10 工具正在根线程准备，路径将为 E:/Win-Note-To-phone/.tools/dotnet/dotnet.exe。你可查询当前工具情况及官方文档。先完成模块，构建/必要本地验证，写 report 提供文件列表、命令结果和无法验证项，然后返回短结论。若发现协议不足，通过协作消息根线程解决，不自行改接口。
