# Windows v3 与构建交付独立审查

审查日期：2026-09-30。审查依据：`docs/protocol-v1.md`、`docs/windows-task-brief.md`、重构计划 Task 2 / Task 4，以及 requesting-code-review 技能的评审模板。

Base / Head 均为 `253534b2c376fa80279726e72768d1cdfc26abe4`。实现尚未提交，因此本次审查对象是实际工作区，而不是两个相同提交之间的空 diff。未修改实现、索引、分支或提交；仅新增本报告。根线程正在集成与打包，下面明确区分原始发现、已落盘修复和集成待完成项。

## 结论

- **Spec review：静态审查通过。** WPF、中文权限引导、启动快照基线、稳定 AppUserModelId 过滤、暂停边界、托盘、扫码审批、设备撤销、多 IPv4 地址选择、StartupTask 和用户数据路径均已实现。首次基线解析失败后的旧通知边界已修复。
- **Quality review：静态审查通过。** 本次范围内原始 Important 均已修复并复核，未发现尚存 Critical / Important。构建入口、MSIX 权限清单和 CI 的静态检查没有发现其他阻断性缺陷；最新桌面状态测试执行结果仍由根线程提供。
- **Ready to merge：Windows 审查范围内可继续集成。** 最终交付仍应确认 Android 构建脚本完成落盘、状态 runner 与真实发布构建通过，并更新验证记录。真实授权、签名安装和双设备后台行为仍需实机验证；未验证本身不列为代码缺陷。

## Strengths

- `NotificationCapture.InitializeAsync` 要求真实 WPF Dispatcher 访问，授权从 WPF 点击事件进入。每次读取前检查权限；启动现有通知只建立基线。暂停继续跟踪快照，恢复以 UTC 时间边界排除暂停期间的通知。
- 捕获使用系统 AppUserModelId；来源过滤发生在 `_store.Append` 之前。来源键包含 appId、系统通知 ID 与创建时刻，核心存储另有持久重复过滤。
- 服务状态只在 `StartAsync` 成功后设置就绪；缺包身份和权限拒绝均有明确说明。单实例 mutex 使用 `Local\\` 会话范围，关闭窗口留托盘，显式退出停止捕获、服务并释放存储和图标。
- MSIX 清单使用 `packagedClassicApp` / `mediumIL`、Windows 19041 最低版本、`uap3:userNotificationListener` 和 `runFullTrust`，StartupTask 默认关闭。Microsoft 的[桌面 MSIX 组件说明](https://learn.microsoft.com/en-us/windows/msix/desktop/desktop-to-uwp-manual-conversion)支持这一清单结构；[桌面激活说明](https://learn.microsoft.com/en-us/windows/apps/desktop/modernize/get-activation-info-for-packaged-apps)支持通过 AppInstance 识别 StartupTask。
- 打包明确区分未签名产物与经过当前机器信任验证的签名产物。安装入口检查签名，不自动信任证书或改防火墙。新版默认构建入口已切换为 Windows 用户会话 App。

## Critical

无已证实的 Critical。

## Important：尚存问题

无。本次发现的 Important 均已修复；见后续记录。

## I3 追加复核：首次基线中解析失败的旧 Toast 恢复后会作为新通知发布（已修复）

位置：`src/Bridge.Windows/NotificationCapture.cs:102-107`、`:120-127`；`src/Bridge.Windows/NotificationSnapshotTracker.cs:15-25`（修复 I1 / I2 并提取 tracker 后的版本）。

触发：首次基线快照返回一条已经存在的 Toast，但其 AppInfo / DisplayInfo / 应用发现处理在这一轮失败。逐条 catch 使它没有加入最终 `_seen`，同时本轮结束将 `_baseline` 设置为 true。下一轮该条 Toast 恢复可读，就会通过新通知判断并进入持久发件箱。这与「每次启动先以已有通知为基线」不符。

当前修复：`NotificationCapture.cs:92-94` 在首次成功获取系统快照后传入 UTC 时刻；`NotificationSnapshotTracker.cs:16-24`、`:29` 将这一时刻作为基线边界。即使旧条目当时无法取得 AppInfo / key，恢复解析后也会因为创建时刻未超过基线而被排除。后续常规快照不推进基线边界，因此新条目保存失败仍能重试；权限恢复通过 ResetBaseline 后的 Complete 重新建立边界。

`tests/Bridge.Windows.Tests/Program.cs:12`、`:41` 已加入首次基线不可读旧条目恢复、权限恢复基线不可读条目不重放的断言。仅复核这三个文件后，确认 I3 关闭，没有发现本次时间边界修改引入新的 Important。最新 runner 的真实执行日志待根线程补充；本审查者未重复执行构建或测试。

## 原始采集发现：修复已落盘并复核

### I1. 单条通知解析异常会阻断整批快照（已修复）

位置：`src/Bridge.Windows/NotificationCapture.cs:94`（foreach）、`:97`（AppInfo）、`:104`（Visual）、`:117`（整批 catch）。

触发：系统返回的某一条 Toast 在读取 AppInfo / DisplayInfo / Visual / 文本时抛出异常。当前整个 foreach 共用最外层 catch，异常后余下通知不会处理，`:112-114` 的 seen / 基线更新也不执行。只要这条异常记录仍在通知中心，同样错误可在每次 15 秒检查中重复，正常通知可能持续无法转发；启动阶段还可能一直无法建立基线。

当前修复：`NotificationCapture.cs:99-125` 已逐条隔离解析错误，并累计失败数；其他有效通知及快照继续推进。Microsoft [Notification listener 文档](https://learn.microsoft.com/en-us/windows/apps/develop/notifications/app-notifications/notification-listener)明确建议单通知处理使用独立 try/catch。静态复核确认原问题关闭，未声称已在本机制造出异常平台 Toast。

### I2. 暂时入库失败后通知永久被标记为已见（已修复）

位置：`src/Bridge.Windows/NotificationCapture.cs:101`、`:103`、`:110`、`:112-113`；`src/Bridge.Windows/MainWindow.xaml.cs:131`、`:142`。

触发：`BridgeStore.Append` 因暂时 SQLite busy、磁盘或 I/O 问题失败。`AppendAndDisplay` 捕获异常后只更新 UI，不向捕获器返回失败；捕获器仍将该 key 加入下一轮 `_seen`。恢复后，只要原 Toast 仍在系统快照中，`:103` 会一直跳过它。通知没有进入持久发件箱，也没有重试，状态文本不足以避免这条数据丢失。

当前修复：`NotificationCapture.Notification` 改为返回 bool 的处理回执；`MainWindow.OnCaptured` 将主动过滤 / 持久重复视为已处理，Append 失败返回 false；捕获器 `:115-119` 移除失败 key，使仍在系统快照中的通知下轮重试。静态复核确认原问题关闭，未执行磁盘 / SQLite 故障注入。

## 原始发现：修复已落盘并复核

1. **Important：打包输出位于发布目录内导致自复制。** 原始 `package_windows.ps1:38-40` 在枚举源文件前创建 stage；`-OutputDirectory` 等于或位于 `-PublishDirectory` 内时，源文件集合包含 stage / 输出目录。当前 `:16-20` 已规范绝对路径并拒绝这两种关系，静态复核通过。
2. **Important：已有 SDK 只凭进程成功就视为指定通道就绪。** 原始 `bootstrap_dotnet.ps1:7-9` 没有验证 SDK 主次版本。当前 `:1` 校验 Channel 格式，`:8-9` 与 `:52-53` 检查 `--list-sdks` 的对应主次版本，静态复核通过。Microsoft [dotnet 文档](https://learn.microsoft.com/en-us/dotnet/core/tools/dotnet)区分受 global.json 影响的 `--version` 与可列出安装 SDK 的 `--list-sdks`。
3. **Minor：重复打包保留完整 stage。** 当前 `package_windows.ps1:71-77` 已以 finally 清理本次 GUID stage，并在递归删除前校验绝对路径范围和目录名。未清理历史 stage 的原始发现已关闭；本次审查未删除任何已有目录。

这三项只复核源码和 PowerShell 解析结果；真实 MakeAppx 打包及异常路径运行由根线程执行，不能把静态复核称为打包实测通过。

## Minor 与集成待完成项

- `docs/windows-task-report.md:3` 仍写等待 .NET 10 SDK，后面的已执行核验也停留在早期失败记录。根线程已报告后续真实 Windows build 成功；最终交付应更新该报告及开发记录，保留原失败为历史、增加最新验证证据，避免当前状态互相矛盾。
- 当前 `scripts/build_android.ps1` 尚未落盘，`scripts/build_apk.ps1:4` 与 README 的默认 Android 构建步骤引用它。根线程已确认 Android agent 正在负责该文件，因此记录为**进行中的集成条件**，不把未完成子任务重复归为终版缺陷。最终交付前仍必须确认文件存在、参数匹配与真实执行结果。

## 审查与验证边界

- 已阅读 Windows 全部源码、XAML、应用清单、桌面设置、MSIX 清单和安装 / 资源脚本；阅读根构建包装、SDK 引导、Windows 发布 / 打包脚本、slnx、Directory.Build.props、global.json 和 GitHub Actions 工作流。后续新增 NotificationSnapshotTracker、桌面状态 runner 和 test_windows.ps1 已静态阅读；最终测试源码覆盖保存失败重试、暂停 / 权限恢复、首次解析失败的旧通知恢复与权限恢复后的旧通知不重放。仅阅读传输 / 核心的相关接口与释放路径，用于确认桌面调用语义；其完整安全审查由其他审查者负责。
- PowerShell Parser 对 8 个相关脚本均返回 0 个解析错误；根线程修复后的 package / bootstrap 两脚本又各返回 0。AppxManifest 和 MainWindow 的 XML 结构加载成功；这不等价于 MakeAppx schema 验证或 WPF 运行。
- `git diff --check` 无空白错误；现有 LF→CRLF 提示不是功能失败。Android `gradlew` 实际字节为 160 个 LF、0 个 CRLF，CI 使用 `bash ./gradlew`，不依赖 executable 位。Windows CI 使用 .NET 10 / pwsh，Android CI 使用 Ubuntu / JDK 21 / 明确的 Android SDK 组件，构建与产物路径对应新工程。未运行远程 CI。
- 按根线程要求没有重复 Windows 发布 / 测试构建，也没有启动桌面 GUI、安装 / 信任证书、MSIX 或修改系统设置。根线程提供的构建通过结果不是本审查者独立执行的测试结果。
- 尚未验证：真实通知授权、拒绝与撤销、异常通知解析、短时 Toast、托盘及登录激活、签名安装、跨设备网络和 Android 息屏接收。已实现的 15 秒补偿不能保证捕获两次快照之间出现又消失且事件未送达的 Toast，实施报告已准确披露该平台边界。
