# 更新日志

这里记录面向用户的版本变化。**未发布**内容与 GitHub 下载包分开列出；构建、签名和实机验证细节保留在对应发布记录中。下载入口：[GitHub Releases](https://github.com/wang329856/Win2Mobile-NotifyBridge/releases)。

## 未发布

### 文档

- 重整 README：增加品牌页头、状态徽章、两端界面预览、下载表格和快速上手。
- 补齐 MIT 许可证、隐私说明、安装与升级教程、贡献指南，以及 GitHub 缺陷和功能建议模板。
- 明确局域网直连无公共中转依赖；默认跨网使用第三方 ntfy.sh，受服务可用性、限额及缓存策略影响。
- 说明自建 ntfy 的接入条件、地址切换与重新配对要求，以及当前未提供的服务器账号 / token 设置。
- 将详细构建、签名和测试步骤移入开发指南，保留原有协议及验收入口。

本节仅记录发布材料整理，尚未随新版本发布。

## [3.1.2](https://github.com/wang329856/Win2Mobile-NotifyBridge/releases/tag/v3.1.2) — 2026-10-02

### 隐私修复

- Windows 清理过期和超量通知时处理当前 SQLite / WAL 的正文残留；首次启动整理旧库，保留有效消息、设备身份、授权与进度。外部读取阻塞时提示并重试。
- Android 删除、清空、开始新一轮、移除电脑和重新配对时撤回对应系统消息通知；启动整理没有本地记录的旧通知，保留后台状态通知。
- Windows 队列项失效后清空详情与文本撤销记录，禁用复制入口。

### 升级与验证边界

- Windows 文件 / MSIX 版本为 3.1.2.0，Android versionCode 为 30102。
- 保持原发布签名与包身份，可覆盖相同正式签名的旧版；Windows 包带可信时间戳。
- 旧备份、系统快照和其他应用保存的副本不在清理范围；Android 通知栏、锁屏和 ROM 的真实行为仍需实机复核。

详见 [3.1.2 发布记录](docs/releases/v3.1.2.md) 与 [隐私修复及验证记录](docs/security-fixes-2026-10-02.md)。

## [3.1.1](https://github.com/wang329856/Win2Mobile-NotifyBridge/releases/tag/v3.1.1) — 2026-10-02

### 新增与改进

- 两端统一浅色 / 深色界面与品牌图标，Android 支持自适应及单色主题图标。
- 手机通知标题包含来源应用，空应用名与空标题有回退显示。
- 竖屏扫码界面，提供手电筒、返回与相机失败重试。
- 保留暂停 / 继续、可信 LAN 优先的自动通道选择、删除撤销、消息去重和 Room 迁移。
- Android 改用独立 release 签名；Windows 仅提供带应用包身份的自包含安装版。

### Windows 签名更新（同日）

- MSIX 使用原证书重新签名并加入 DigiCert RFC 3161 可信时间戳。
- 更新 MSIX、安装 ZIP 与 SHA-256 清单；应用代码、版本、包身份、证书指纹及 Android APK 不变。
- 之前下载的无时间戳 Windows 包需重新下载，不能自动获得该修复。

### 升级与限制

- Windows 文件版本为 3.1.1.1，Android versionCode 为 30101。
- 新电脑首次安装仍需核验并信任 Windows 自签名证书。
- 签名不同的 Android debug APK 不能直接覆盖为正式版，卸载会删除旧数据。
- 后台接收、重新配对后的完整端到端和网络切换行为，仍以实际设备验证为准。

详见 [3.1.1 发布与验证记录](docs/releases/v3.1.1.md)、[安装教程](docs/installation.md) 和 [界面改版验收](docs/ui-redesign-2026-10-02.md)。

## 跨网与接收会话开发更新 — 2026-10-01

以下为 Git 提交 `43f20fa` 的开发记录，**不是独立的 3.1.0 Release**；相关功能已随 3.1.1 发布。

- 增加可选 ntfy 端到端加密跨网推送和完全远程配对。
- 每手机独立 topic / 密钥，加密分片、发布重试与临时队列。
- 手机支持接收会话、暂停 / 继续、清空与删除标记。
- 支持可信 LAN 优先的自动连接，以及 LAN 地址更新。

详见 [ntfy 协议](docs/protocol-ntfy-v1.md) 与 [接收会话记录](docs/receive-session-update-2026-10-01.md)。

## [3.0.0-preview.1](https://github.com/wang329856/Win2Mobile-NotifyBridge/releases/tag/v3.0.0-preview.1) — 2026-10-01

### 重构

- Windows 改为 C# / .NET 10 / WPF，Android 改为 Kotlin / Jetpack Compose。
- 提供 HTTPS / WSS 局域网连接、120 秒二维码配对、电脑批准与设备撤销。
- 持久化通知、断线补发、去重与游标确认；桌面提供来源过滤、托盘和可选登录启动。
- 清理旧 Flutter、旧 ntfy 轮询与过时构建入口。

### 预发布限制

- 本次包仅支持局域网，Windows 使用自签名开发证书，Android 使用 debug 签名。
- 本次旧 Windows 包没有可信时间戳；新安装建议使用 3.1.1 的更新包。

详见 [预发布说明](docs/releases/v3.0.0-preview.1.md)。更早实现可从 Git 历史提交 `253534b` 查看，旧配置与 Flutter 历史不会自动迁移到 v3。
