# ntfy 新版本安装记录（2026-10-01）

用户要求更新电脑端并通过 ADB 更新手机。本次已完成两端原位更新和启动检查，未执行远程配对或向公共中转发布消息。

本文记录首次 3.0.0.1 更新。最新安装为 3.0.0.4，远程连接及删除后不复现已由用户确认，最新产物和验证见 [接收会话更新](receive-session-update-2026-10-01.md)。

## Windows

- 从 `3.0.0.0` 原位升级至 `3.0.0.1`，包名与包家族不变；未卸载、清空数据或更改默认应用存储卷。
- 使用已有本机开发证书签名，指纹 `81D47A2CADFAEEA35CFB47F1D18C2DA9F1193C19`。复用原有信任，没有导入新的信任证书。证书存储导入返回系统 Access denied 后，改为直接使用已有加密 PFX 签名，验证通过。
- 已签名更新包：`dist/windows-ntfy/local-install/Win2Mobile-3.0.0.1-x64.msix`，SHA256 `2CFAC9C704A8D06301D8E8380AE06CF670A68475F64A0B0FB7F084291F12A159`。SignTool 验证无错误或警告，Authenticode 为 `Valid`。
- 安装位置：`C:\Program Files\WindowsApps\Win2Mobile.NotifyBridge_3.0.0.1_x64__j8kzrqnxgczda`，安装状态正常。
- 开始菜单身份启动后进程 ID `35068`，来自新版安装目录，窗口标题 `Win2Mobile · 安全通知桥`，`Responding=True`。
- 安装目录 `Bridge.Transport.Ntfy.dll` SHA256 与本次发布目录一致：`9A7CD8296E411DFAE1E78E0AB281A43C77E046D8BAEF827DD3B1AF19FE636C16`。
- 仅回环 `GET https://127.0.0.1:47721/v1/health` 返回 `status=ready`、协议 1、历史上限 15。该探针仅对回环自签名证书使用 curl 的 insecure 参数，未更改生产证书验证。
- 原 LAN 的 DPAPI 证书文件哈希与安装前一致；应用数据备份位于已忽略的 `.artifacts/ntfy-upgrade/windows-profile-before`，共 7 个文件。未将凭据内容输出或提交。
- 安装脚本补充 UTF8 BOM，解决 Windows PowerShell 5 对中文脚本的解析问题；实际脚本安装退出 0。执行策略 Bypass 仅用于这次子进程，没有修改系统策略。

## Android

- 在唯一已授权的 V2352A（Android 16）上通过 ADB 更新。
- 执行同签名 `adb install -r --user 0 dist/android/Win2Mobile-3.0.0-debug.apk`，返回 `Success`，没有卸载或清空数据。
- 应用 ID `com.notifforward.app`，versionName `3.0.0`、versionCode `30000` 沿用现有开发版本号。本次以实际安装 APK 哈希确认更新，不能仅靠相同版本号判断。
- 手机 `base.apk` SHA256 为 `EF4DFBAA23FE3A7D24E201CEB1B39F76027E4560D4DDCCABB3F982899A27190F`，与本次构建产物一致。
- 手机包管理器更新时间 `2026-10-01 21:22:48`，首次安装时间仍为 `2026-10-01 00:21:06`。
- `am start -W -n com.notifforward.app/.MainActivity` 返回 `Status: ok`，进程 ID `21433` 存在；当前进程错误级别日志未发现 AndroidRuntime 崩溃，存在设备系统组件日志。

## 后续验收

电脑“跨网络推送”默认关闭。开启后生成远程二维码，手机扫码或粘贴信息，两端核对六位校验码并由电脑批准。本次启动检查不证明公共 ntfy 服务、移动数据收取、Room 历史数据完整性或息屏行为通过；继续按 [实机验收清单](ntfy-device-validation.md) 执行。
