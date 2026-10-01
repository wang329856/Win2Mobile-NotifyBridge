# 本机安装记录（2026-10-01）

用户要求安装桌面端，并通过 ADB 安装手机端。本次完成安装与基本启动检查；尚未进行扫码配对、系统通知授权或真实通知同步验收。

## Android

- 设备：V2352A，Android 16；ADB 仅检测到一台已授权设备。
- 安装：`adb install -r` 返回 `Success`，未清空已有应用数据。
- 安装包：`dist/android/Win2Mobile-3.0.0-debug.apk`，应用 ID `com.notifforward.app`。
- 安装后查询：versionName `3.0.0`，versionCode `30000`，更新时间 `2026-10-01 00:21:06`。
- 启动：`am start -W -n com.notifforward.app/.MainActivity` 返回 `Status: ok`；进程存在。
- 使用 Android Debug 签名；通知、相机等运行权限由用户在使用时授予。

## Windows

- 安装包：`packaging/windows/output/local-install/Win2Mobile-3.0.0.0-x64.msix`，本机开发签名。
- 签名验证：PowerShell `Get-AuthenticodeSignature` 返回 `Valid`，SignTool 验证成功。
- 安装后查询：`Win2Mobile.NotifyBridge`，版本 `3.0.0.0`，状态 `Ok`。
- 最终安装位置：`C:\Program Files\WindowsApps\Win2Mobile.NotifyBridge_3.0.0.0_x64__j8kzrqnxgczda`，目录没有重解析点。
- 开始菜单：`Win2Mobile 局域网通知桥`，AUMID `Win2Mobile.NotifyBridge_j8kzrqnxgczda!Desktop`。
- 启动检查：进程来自上述安装目录，窗口标题 `Win2Mobile · 局域网通知桥`，`Responding=True`。
- 服务检查：应用进程监听 TCP 47721；回环 `GET https://127.0.0.1:47721/v1/health` 返回协议 1、`status=ready`。本次开发探针仅为回环请求跳过自签名 TLS 证书检查，没有更改应用或系统的证书验证策略。此检查不能证明手机到电脑的 LAN 连通性。

### 安装位置问题与处理

本机默认 Appx 应用卷为 D 盘。第一次安装后，C 盘包目录和用户 LocalCache 为指向 D 盘的目录联接。启动时 AppModel-Runtime 返回 `0x800701C0`（不受信任的装入点），应用进程没有创建。

使用系统支持的 `Move-AppxPackage` 迁移本应用，失败于功能授权 `0x800701C5` / 注册失败 `0x80073CF6`，并回滚。随后备份这次尚未成功启动的安装所生成的 10 个文件，逐个核验 SHA256；仅卸载这个新安装的 Win2Mobile 包，再用 `Add-AppxPackage -Volume <系统应用卷>` 安装。最终包目录及 LocalCache 均为普通目录，启动成功。

项目安装脚本现为本次安装显式选择系统应用卷，不改变机器的默认应用存储位置。脚本语法检查通过；关闭本次启动的应用后实际执行脚本返回 0，随后重新打开验证。应用正在运行时安装会返回 `0x80073D02`，需先从托盘退出。没有手动删除 WindowsApps 联接、修改其 ACL 或放宽系统路径信任策略。

官方参数说明：[Add-AppxPackage 的 Volume 参数](https://learn.microsoft.com/en-us/powershell/module/appx/add-appxpackage)、[Move-AppxPackage](https://learn.microsoft.com/en-us/powershell/module/appx/move-appxpackage)。

### 本机签名材料

仅为本次本机测试创建 Subject `CN=Win2Mobile` 的代码签名证书。用户通过 Windows UAC 后，将其公钥证书导入 `LocalMachine\TrustedPeople`。没有加入 Root 信任库。

- 证书指纹：`81D47A2CADFAEEA35CFB47F1D18C2DA9F1193C19`。
- 有效期截至：`2026-12-31 00:24:38 +08:00`；这是开发证书，后续发布需使用正式签名方案。
- 签名 MSIX SHA256：`4A39A779EA0BD3B4E1077FD7A06FAE0635EE4BABF79D7B40146DB7A6A434F895`。
- 加密 PFX、公钥证书及当前用户 DPAPI 保护的密码保存在已忽略的 `packaging/windows/output/local-install`，没有提交或推送。不要上传私钥及密码文件。
- 原始未签名 MSIX 保留，未覆盖。

安装诊断、信任导入回执、签名验证日志及安装数据备份位于已忽略的 `.artifacts`。完整功能验收继续使用 [双设备验收表](device-validation.md)。
