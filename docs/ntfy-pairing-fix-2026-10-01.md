# 远程配对无请求故障修复（2026-10-01）

症状：手机扫码后显示远程校验码，电脑没有收到待批准请求。显示校验码只说明二维码解析与本地 ECDH 计算成功，旧界面在联网前就展示它，无法据此判断请求已发送。

## 根因与修复

应用原先生成 `w2m_` 前缀加 64 个十六进制字符的 topic，总长 68。ntfy 官方服务端 topic 与 WSS 路由最大 64，真实 WSS 订阅返回 404，手机无法执行之后的请求发布。本地模拟服务没有限制 topic 长度，因此此前自动测试漏掉该缺陷。依据：[ntfy 官方服务端规则](https://github.com/binwiederhier/ntfy/blob/main/server/server.go)。

所有请求、回复和通知 topic 改为前缀加 60 个十六进制字符，总长 64，保留 240 bit 随机熵；AES 通知和配对密钥仍为 256 bit。C# 与 Kotlin 校验、生成逻辑、合成跨语言测试向量同步更新。模拟服务现在拒绝超长 topic，避免再次漏测。升级遇到旧的无效中转凭据时，仅删除这些无法使用的中转记录，保留 LAN 授权与本地历史，提示重新配对。

另一个实测阻塞是电脑网络：`ntfy.sh` 解析至 `198.18.1.27`，直连 TLS 握手失败；通过现有 FlClash HTTP 代理 `127.0.0.1:7890` 返回 HTTPS 200。新增应用内可选 HTTP 代理字段，发布和 WSS 配对使用同一配置。当前机器已设置该地址，没有修改系统代理；FlClash 需要保持运行。

Android 现在分别显示正在连接（尚未发送）、已连接/正在发布、请求已发送/等待批准，以及 DNS、TLS、超时、HTTP 拒绝或限流后的重试状态，始终保留相同六位校验码。电脑配对连接增加 10 秒超时和明确网络提示。

## 验证与部署

- 修复前，公共 WSS 返回 404；修复后使用生产代理工厂、标准证书验证与随机空 topic，公共 HTTPS 返回 200、WSS 返回 `open`。该验证没有发布消息。
- C# 回归通过：真实 CONNECT 代理把不可解析的测试主机转发至本地 TLS 中转，同时验证 WSS 申请与 HTTPS 授权回复；旧 topic 被拒绝，DPAPI 旧凭据修复保留 LAN 授权。原加密、设备隔离、限流、撤销等测试通过。日志 `.artifacts/ntfy-topic-proxy-tests.log`。
- Android 44 项 JVM 测试通过，失败/错误/跳过均为 0；增加 HTTP 403 连接失败状态与重试、超长 topic 拒绝测试。lint 和 APK 构建通过。日志 `.artifacts/ntfy-topic-proxy-android.log`。
- Windows 发布、MSIX 打包、同一开发证书签名验证通过；原位升级至 `3.0.0.2`，进程正常响应，回环健康状态 ready，原 serverId 与历史上限 15 保留。已安装 ntfy 模块哈希与最终发布产物一致。
- V2352A 经 ADB 覆盖更新并正常启动，手机已安装 APK 哈希与最新产物一致。Android 开发版本号仍为 3.0.0，以下哈希用于区分本次修复。

| 产物 | SHA256 |
|---|---|
| `dist/android/Win2Mobile-3.0.0-debug.apk` | `ADA1C2D257A161E49BA8745B554729877128566FAD17A24AB9CFDE3FDAD6ECC7` |
| `dist/windows-ntfy-fix/local-install/Win2Mobile-3.0.0.2-x64.msix` | `DD195DB88C3A5D058405CF78AB9D140F0959E928DFE3DDF4579E48A7B5CC7BE8` |

用户随后确认“已经成功远程连接”。该反馈确认此次实际远程连接成功；未提供蜂窝网络、通知收取与息屏逐项验收结果。后续发现 LAN 切换使用虚拟网卡地址，修复和更新记录见 [局域网切换修复](ntfy-lan-switch-fix-2026-10-01.md)。
