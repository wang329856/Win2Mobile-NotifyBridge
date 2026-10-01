# v3 开发记录

2026-10-01 最新接收策略：按用户选择的一次手动接收会话，仅保留首次连接后的通知，重连/切换保留本次内容；手机删除与清空保留标记，消息不会复现。电脑临时发送队列最多 24 小时/1000 条，授权前记录不会提供给新设备。Android 53 项 JVM 测试、lint 与构建通过，后端队列/会话/授权和 ntfy 加密/限流回归通过；电脑已升级 3.0.0.4、手机 ADB 覆盖更新。Room v2→v3 实机迁移与加密凭据保留通过。用户确认“新通知正常，删除后不再出现”。详见 [接收会话更新](receive-session-update-2026-10-01.md)。

2026-10-01 最新更新：用户确认远程连接成功。LAN 快捷切换失败源于手机保存 WSL/Hyper-V 地址 `172.30.96.1`，当前实际网卡地址为 `192.168.1.5`。手机新增独立更新 LAN 地址入口，保留授权、指纹与进度；电脑优先实际网卡并在远程配对页提供校验码批准面板。Android 48 项 JVM 测试、lint 与构建通过；Windows 发布、签名与 3.0.0.3 原位升级通过，手机 ADB 覆盖更新和安装哈希核验通过。局域网实际切换待用户确认，详见 [局域网切换修复](ntfy-lan-switch-fix-2026-10-01.md)。

2026-10-01 安装更新：用户随后授权更新两端，电脑 MSIX 已原位升级至 3.0.0.1 并正常启动，手机 V2352A 已通过 ADB 覆盖更新到本次 APK 并启动；安装包哈希、新模块和电脑健康接口核验通过，详见 [ntfy 新版本安装记录](ntfy-installation-2026-10-01.md)。远程配对、公共服务和蜂窝/熄屏验收仍待确认。

2026-10-01 后续：ntfy 跨网络推送及完全远程首次配对已实现，自动验证通过。每手机独立 AES-256-GCM 密钥、远程 ECDH/SAS 批准、分片限流重试与撤销、LAN/中转独立游标和缓存缺口提示已接入。Android 最终 42 项 JVM 测试通过，lint 0 错误/21 警告；Windows 原回归、自包含发布与未签名 MSIX 打包通过。本次新版尚未安装或发布，蜂窝网络、Room 升级与熄屏实机验收仍待确认。最新产物与 SHA256 以 [ntfy 开发验收记录](ntfy-task-report.md) 为准；下文记录的是此前 LAN 版本。

目标：用户已确认 Kotlin；局域网先可用，后续跨网络扩展；中文安装授权、扫码配对与真实状态。

交付分支：master；开发分支codex/lan-kotlin-rebuild；重构基线253534b。2026-10-01 用户确认测试通过并授权清理旧版、上传 GitHub。旧版源码和构建产物已移除，历史仍可从基线提交恢复；未跟踪的旧 Flutter 打包笔记先备份到已忽略的 .artifacts，再从当前文档中移除。

- 已完成：代码评估、协议v1、模块brief、实施计划、Windows主体、SQLite与HTTPS/WSS后端、Kotlin/Compose Android、默认构建入口、CI配置与独立代码审查。
- 已验证：后端console runner、桌面采集状态runner、Windows自包含发布、MakeAppx默认schema校验与未签名MSIX生成。原生SQLite实际版本3.53.3，安全版本断言通过。
- Android已验证：最终脚本退出0，26项JVM测试通过（失败/错误/跳过均0），lint为0错误/21警告，assembleDebug与APK v2签名核验通过。包含真实回环HTTPS/WSS；FakeDao测试不证明真实Room隔离或Android服务行为。
- 实机已验证（2026-10-01）：本机开发签名 MSIX 安装、开始菜单启动、窗口响应和 HTTPS 回环健康检查；V2352A / Android 16 通过 ADB 安装 APK 3.0.0 并启动。非系统应用卷的目录联接导致 Windows 启动失败，改为系统卷安装后成功；安装脚本已指定系统卷。详见 local-installation-2026-10-01.md。
- 用户实机反馈（2026-10-01）：测试已通过。未提供逐项验收结果，因此此反馈不扩展为一整晚息屏、重启、撤销等所有场景均通过；详见 device-validation.md。

工作区 .NET SDK10.0.401 已按微软官方SHA512校验安装。Microsoft.Data.Sqlite已升级10.0.12，移除首次构建发现的SQLitePCLRaw 2.1.11漏洞依赖。Android使用JDK21/SDK36/Gradle8.13；官方发行包SHA256核验通过，本机网络与中文缓存目录问题及最终命令见android-task-report.md。2026-10-01 ADB 已连接并安装目标手机。

当前Windows产物：src/Bridge.Windows/bin/publish/win-x64/Win2Mobile.exe（自包含目录）；packaging/windows/output/Win2Mobile-3.0.0.0-x64.msix（未签名，94,946,630字节）。未签名MSIX不能直接视为可安装成品。

Android产物：dist/android/Win2Mobile-3.0.0-debug.apk（12,537,743字节，Android Debug签名）。SHA256：2D213F841BD8F4A9857C14D7EDCC6CCC89FB580B4A66D858DC30F50E7B0D6B98。MSIX SHA256：5FEA1A0016E0C89DC7055DB6B73E95D119E3F7574D52C06702FF44ABB9EA9C19。

日志保存在未跟踪的.artifacts：backend-tests.log、windows-tests.log、windows-publish.log、windows-package.log、android-build.log。生产证书加载、跨进程身份竞争、到期事件通知、单条解析阻断、失败保存误记已见和基线边界均已修复。Android另修复取消/重连竞态、LAN路由绑定、事件冲突、ACK吞提醒、误判撤销和重配对游标回退。最新独立复审见相应报告。CI文件已配置，推送后由 GitHub Actions 验证。此次交付上传源码与构建入口，未创建正式签名的 Release。
