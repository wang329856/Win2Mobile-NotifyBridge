# Win2Mobile v3 局域网重构 Implementation Plan

> **For agentic workers:** 使用模块任务 brief 执行；共享接口以 docs/protocol-v1.md 为唯一约束，独立模块可以并行，集成与审查顺序执行。

**Goal:** 实现可构建的 C# 桌面端与 Kotlin Android App，提供扫码批准配对、加密局域网连接、持久消息与断线恢复，并交付运行及打包入口。

**Architecture:** Windows 用户会话 WPF 程序内置 Kestrel，官方监听捕获的 Toast 进入 SQLite 发件箱。Android 原生服务经固定证书指纹和设备凭据接收有序事件，Room 幂等入库，Compose 负责通知、设备、设置与权限引导。

**Tech Stack:** .NET 10 LTS、WPF、Microsoft.Data.Sqlite、ASP.NET Core、Kotlin、Compose、Room、OkHttp、ZXing。

## Global Constraints

- 仅 Android，局域网优先；跨网络以传输接口保留扩展边界。
- 协议字段、默认端口、平台版本与 C# 公共接口严格使用 docs/protocol-v1.md。
- 旧 src/NotificationService 与 src/notification_app 保留；README 与默认构建脚本切换到新实现。
- 用户已授权实施，不要求逐阶段再次批准；不自动提交、推送或发布。
- 分支 codex/lan-kotlin-rebuild；源码与工具只写工作区或明确允许目录。
- 测试覆盖认证、配对审批、补发、幂等、重启、撤销；构建不能替代实机后台与安装验证。

## Task 1: 存储与局域网服务

**Files:** src/Bridge.Core/{Models.cs,BridgeStore.cs,Bridge.Core.csproj}; src/Bridge.Transport.Lan/{LanBridgeHost.cs,PairingService.cs,CertificateManager.cs,Bridge.Transport.Lan.csproj}; tests/Bridge.IntegrationTests; Directory.Build.props; global.json。

**Interfaces:** 使用 protocol-v1.md 中 BridgeStore、LanBridgeHost 等公共接口。

- [x] 固定 SDK，下载工作区 .NET 10，恢复依赖并建立解耦项目。
- [x] 先实现并验证 SQLite 重启连续序号、稳定事件 ID、来源重复过滤、设备 token 哈希与单调确认。
- [x] 实现短期配对码、请求秘密、桌面批准与拒绝、撤销；测试过期与错误凭据。
- [x] 实现 HTTPS health、分页事件、WebSocket 历史与实时同一序列、确认及撤销终止。
- [x] 执行 scripts/test_backend.ps1 中的 console runner（临时数据库与本地 HTTPS 端口），测试超过 200 条事件、重连、鉴权和并发取消；dotnet test 不执行此 runner。

## Task 2: Windows 用户流程

**Files:** src/Bridge.Windows; packaging/windows; scripts/build_windows.ps1; scripts/package_windows.ps1; docs/windows-task-report.md。

**Interfaces:** 消费 BridgeStore、LanBridgeHost、CertificateManager 和 PendingPairing。

- [x] 完成真实 UI 线程的官方通知授权、启动快照基线、应用 ID 规则、事件读取及低频快照补偿。
- [x] 实现 WPF 引导、真实状态、可刷新二维码、批准/拒绝待配对设备、撤销与暂停。
- [x] 实现通知历史详情、来源规则、开机启动与用户 AppData 设置；提供托盘。
- [x] 提供正确 uap3 权限清单与可重复打包脚本，安装身份和启动入口；不写固定假成功状态。
- [x] 编译 Windows 项目，报告可验证项和真实授权/安装未验证项。

## Task 3: Kotlin Android

**Files:** src/android（独立 Gradle Kotlin 项目）；scripts/build_android.ps1；docs/android-task-report.md。

**Interfaces:** 仅消费 protocol-v1.md 的网络协议；不得改公共协议。

- [x] 实现扫码 payload 解析、固定指纹 TLS、请求与批准轮询、Keystore 凭据存储。
- [x] 实现 Room 唯一事件 ID 与每设备游标原子入库，稳定系统通知 ID。
- [x] 实现 connectedDevice 前台接收、网络变化、退避、心跳、开机恢复和真实状态。
- [x] 完成 Compose 通知详情、搜索/筛选、设备与权限/后台诊断、删除设备与历史。
- [x] 执行26项JVM测试、assembleDebug/lint和APK签名验证；ADB没有连接设备，实机/息屏验证保留为待验项。

## Task 4: 集成、交付与审查

**Files:** Win2Mobile.slnx; README.md; scripts/build_service.ps1; scripts/build_apk.ps1; .github/workflows/build.yml; docs/development-progress.md。

- [x] 将默认构建入口切换新实现，保留旧版目录说明。
- [x] 校对 Android/Windows TLS、配对、状态和事件字段；执行后端真实HTTPS/WSS集成、Android真实网络JVM测试与两端构建。双设备运行仍待实机验收。
- [x] 交付 Windows 发布目录和 Android APK，准确记录签名/实机限制。
- [x] 独立代码审查，修复鉴权、数据丢失、并发与后台生命周期问题后复验。
- [x] 更新开发记录与最终使用步骤，不自动推送或发布。
