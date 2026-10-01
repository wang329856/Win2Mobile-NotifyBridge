# ntfy 跨网络推送开发验收记录

2026-10-01：本文记录首版开发阶段的自动验证与产物，包含用户要求的完全远程首次配对。之后已按用户要求更新电脑与 ADB 手机，并修复公共服务 topic 长度和网络代理、LAN 地址切换、接收会话与删除去重。最新版本、53 项手机测试及实机确认见 [接收会话更新](receive-session-update-2026-10-01.md)；下方 42 项测试和哈希仅属于首版阶段。

## 已实现

Windows 直接向默认免费 ntfy.sh 服务发布 AES-256-GCM 密文，不需要用户租用服务器或购买 ntfy 账号。手机通过 HTTPS/WSS 订阅并本地解密。每台手机使用独立随机 topic 与通知密钥，Windows 使用 DPAPI 保存密钥，Android 使用 Keystore。公共中转仍可观察 topic、来源 IP、时间和密文大小。

电脑开启跨网络推送后生成远程二维码，手机可以使用移动数据扫码或粘贴配对信息。一次性二维码密钥加密申请，临时 P-256 ECDH 派生每个手机申请的回复密钥；两端核对六位校验码，并由电脑明确批准。授权凭据不会以明文发布，二维码不包含长期通知密钥。二维码过期时桌面清除显示。

原 LAN 配对和接收保留。Android 可切换中转或 LAN；两者游标独立，缓存缺口持续提示，切回 LAN 补齐后清除。长消息加密分片，验证后才组装；重连不重复保存或提醒。撤销会取消该设备发布循环并停止后续发送，已缓存密文不可追回。

## 自动验证证据

| 项目 | 结果 | 日志/报告 |
|---|---|---|
| C# ntfy 测试 | 通过：真实 HTTPS/WSS 远程申请与批准、ECDH/SAS、坏帧隔离、重复请求、临时存储失败重试；AEAD 篡改与设备隔离、长中文分片、DPAPI 重载、429 与发布游标、阻塞设备隔离、撤销取消 | `.artifacts/ntfy-tests.log` |
| 原 LAN 后端回归 | 通过：SQLite、批准/拒绝/过期、HTTPS 授权和 WSS 历史/实时/重连/撤销、生产 DPAPI 证书 | `.artifacts/ntfy-lan-elevated.log` |
| Windows 采集回归 | 通过 | `.artifacts/ntfy-desktop-regression.log` |
| Windows 自包含发布 | 通过 | `.artifacts/ntfy-windows-publish.log` |
| MSIX 默认 schema 与打包 | 通过，未签名 | `.artifacts/ntfy-windows-package.log` |
| Android JVM | 42 项，失败/错误/跳过均为 0；包含 HTTPS/WSS 远程配对、断线复用同一申请重试、跨语言加密向量 | `src/android/app/build/test-results/testDebugUnitTest` |
| Android lint 与 APK | 构建脚本退出 0；lint 0 错误、21 警告；APK v2 签名验证通过 | `.artifacts/ntfy-android-build.log`、`src/android/app/build/reports/lint-results-debug.html` |

独立代码复核发现的分片限流后反复重发前缀、缺口提示被重连覆盖、配对断线丢失临时密钥和配对工作器存储异常清理问题均已修复；增加对应回归。六位校验码作为独立可信字段显示，手机名称不能覆盖它。最终复核未发现新的已确认重大缺陷；代码复核不代替真机验收。

## 本次产物

| 文件 | 字节 | SHA256 |
|---|---:|---|
| `dist/android/Win2Mobile-3.0.0-debug.apk` | 12,728,600 | `EF4DFBAA23FE3A7D24E201CEB1B39F76027E4560D4DDCCABB3F982899A27190F` |
| `dist/windows-ntfy/Win2Mobile-3.0.0.1-x64.msix` | 94,995,786 | `1AC23D07799E21E1FCDB0076157F378B874B34AAD7633085337BE87B765137AF` |

Windows 自包含程序位于 `.artifacts/ntfy-windows-publish/Win2Mobile.exe`，需保留整个发布目录。MSIX 未签名，不能直接视为可安装成品。APK 使用 Android Debug 签名。原 GitHub v3-preview 安装包不包含本次功能。

## 待真机确认

按 [实机验收清单](ntfy-device-validation.md) 验证公共服务可达性、手机关闭 Wi-Fi 后完全远程配对及接收、Room v1→v2 旧数据保留、切网、熄屏/Doze、重启与多设备撤销。当前自动回环使用仅供测试的受信任 localhost 证书；生产路径仍执行正常证书验证，不绕过 TLS。

免费公共中转有配额及缓存期限，每台手机与每个分片均消耗发布请求；免费服务和手机后台限制决定实际到达时间，首版不保证熄屏即时送达。没有远程 ACK、FCM/UnifiedPush 唤醒或 iOS 客户端。完整协议、限额来源和隐私边界见 [ntfy 协议](protocol-ntfy-v1.md)。
