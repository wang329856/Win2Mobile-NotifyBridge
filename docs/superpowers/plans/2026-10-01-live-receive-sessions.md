# 接收会话 Implementation Plan

> 按当前用户授权在本会话顺序实施；用户随后授权提交 Git 并同步 GitHub，开发安装包不作为正式发布版本。

**Goal:** 手机只保留本次手动开始接收后的通知，删除不复现；电脑仅保留短期有上限发送队列。

**Architecture:** LAN 批准时冻结起始序号，首次接收使用实时基线；手机持久保存会话和删除标记，重连延续同一会话。电脑队列保留上限 24 小时/1000 条，清理不重置全局序号，过期缺口通过受证书验证的 checkpoint 跳过。ntfy 密钥和批准流程保持现有实现。

**Tech Stack:** C#/.NET/SQLite/HTTPS/WSS；Kotlin/Room/Compose/ntfy。

## Global Constraints

- 手动开始清空旧列表，网络重连及切换保留本次记录与去重进度。
- 不转发授权前通知；旧 ntfy 缓存/延迟发布不能重新填入旧会话消息。
- 清空与单条删除均保留去重标记；保留设备授权与证书校验。
- 原位覆盖更新前备份现有用户数据；不修改系统代理或防火墙。

### Task 1: 临时队列和授权边界

Files: `src/Bridge.Core/BridgeStore.cs`、`src/Bridge.Transport.Lan/PairingService.cs`、`LanBridgeHost.cs`、`src/Bridge.Transport.Ntfy/NtfyPublisher.cs`、`src/Bridge.Windows/MainWindow.xaml.cs`；tests: `tests/Bridge.IntegrationTests/Program.cs`。

- [x] 新设备 `start_sequence = HighWatermark`，批准回复携带该固定值；HTTP/WSS 读取强制 `after = max(after, start_sequence, queueFloor)`。
- [x] 新会话流 `live=true` 从当前最高序号开始，hello/checkpoint 携带 `startCursor`；清理后的序号仍从 `sqlite_sequence` 读取。
- [x] Windows 开启临时队列模式：按入库时间清理 24 小时前内容及超过 1000 条的前缀；没有授权手机时不入库。ntfy 遇到已清理待发记录跳过并继续新消息。
- [x] 运行 `scripts/test_backend.ps1`、`scripts/test_ntfy.ps1`、`scripts/test_windows.ps1`：验证授权前不可读取、首次连接跳旧、重连补发、全清理后单调序号与数据库重启、队列缺口。

### Task 2: 手机会话与删除

Files: `BridgeDatabase.kt`、`BridgeApplication.kt`、`BridgeService.kt`、`Protocol.kt`、`LanClient.kt`、`NtfyClient.kt`、`MainActivity.kt`；tests: `BridgeDaoTest.kt`、`ProtocolTest.kt`、`LanClientTest.kt`、`RemotePairClientTest.kt`。

- [x] Room 增加会话起点、初次连接状态、清空序号及单条删除标记；旧版本迁移清掉通知内容、保留身份与进度。
- [x] 接收协调器在手动 BEGIN 时先取消并等待旧连接结束，再清空列表并开启新会话；网络重连不 BEGIN。
- [x] LAN 初次连接请求实时基线，验证 hello/checkpoint 后推进游标；ntfy 初次会话不读取缓存，其后恢复缓存且忽略会话前的认证事件。
- [x] DAO 保存前拒绝已经删除、清空或会话前的记录，进度仍安全推进；手机详情提供单条删除。
- [x] `scripts/build_android.ps1 -GradleInitScript .artifacts/android-local-repository.init.gradle -Offline`：验证删除后两种传输重放均不复现、会话重启清空与保留授权、重连/地址变更不清空。

### Task 3: 两端更新与记录

- [x] Windows publish、打包为 3.0.0.4、同一证书签名和原位安装；ADB 同签名 `install -r --user 0`；核验实际安装模块/APK 哈希与健康接口。
- [x] 更新 README/协议和实机记录，明确 24 小时/1000 条队列、删除去重和一次手动会话语义；实际网络验收仅声明已有证据。
