# ntfy 跨网络加密推送实施计划

目标：无需租用服务器，Windows 直接向免费 ntfy 中转发布密文，已授权 Android 解密展示。

架构：支持原 LAN HTTPS 配对和完全远程首次配对，两种都需电脑批准。远程二维码含一次性请求密钥和临时 P-256 公钥，授权回复按手机临时 ECDH 密钥加密，两端核对六位校验码。批准响应附带每设备独立 topic 与 AES-256-GCM 通知密钥，长期通知密钥不出现在二维码、公共请求头或日志中。跨网络默认关闭；手机可切换 ntfy 与 LAN。LAN 游标与中转进度独立，回到 LAN 可补全过期历史。

用户已明确首版包含完全远程配对。相应增加 `NtfyRemotePairing.cs`、`RemotePairClient.kt`、远程二维码 UI、跨语言 ECDH/SAS 向量和两端真实回环网络测试；不再要求首次授权位于同一网络。

约束：.NET 10、Android minSdk26/target35/compile36；保持现有 LAN 协议兼容；不租云、不要求 ntfy 付费账号；不将 TLS 当作端到端加密；不宣称公共服务无限额或 Doze 保证送达。Windows 中转密钥由当前用户 DPAPI 保护，Android 由 Keystore 保护。撤销后停止后续发布，已交付的密文不可追回。

- [x] `Bridge.Transport.Ntfy`：实现 AEAD 分片协议、持久加密设备注册表、顺序发件箱和可取消发布循环。每片小于 4096 bytes，只有整个事件发布成功才持久推进，失败按设备重试且不阻塞其他设备；响应正文和 URI 不作为诊断。
- [x] `Bridge.Transport.Lan/PairingService.cs`：加入可选批准凭据提供者。只向正确 requestSecret 的已批准设备提供中转配置；拒绝/过期/撤销不返回密钥。
- [x] `Bridge.Windows`：加入 ntfy 开关、HTTPS 服务地址、真实发布/限流状态；启停和退出等待发布循环停止；设备授权复用现有批准/撤销。
- [x] `android/NtfyProtocol.kt`、`NtfyClient.kt`：标准公有 CA HTTPS/WSS，拒绝重定向；分片先验 AEAD 再组装；限制长度、组装数量、内存和过期；拒绝错来源、错设备、篡改。消息去重与中转恢复标识事务保存。
- [x] `BridgeDatabase.kt`：Room v1→v2 增量迁移，LAN 连续游标保持原语义；ntfy 独立消息 ID/序号，缓存缺口明确提示，切回 LAN 补齐。
- [x] Android 配对与前台接收：Keystore 保存中转秘密；跨网络走系统默认网络，LAN 保留原路由绑定；中转连接状态不等于电脑在线；重放不响铃，异常分片不阻塞后续合法事件。
- [x] 协议与用户说明：公共中转元数据可见、免费限额、每手机/每片占配额、缓存过期、暂停/撤销语义和实机切蜂窝验收步骤。
- [x] 自动验证：C# 实际 DPAPI 重启恢复与 HTTP 限流/撤销测试、C#→Kotlin 共用加密向量、篡改/错设备/乱序分片/重复/缓存缺口测试。两端真实回环 HTTPS/WSS 配对及重试通过；原后端与桌面 runner、Windows 构建、Android 42 项 JVM 测试、lintDebug、assembleDebug 通过。
- [ ] 实机验收：Room v1→v2 数据保留升级、公共 ntfy 服务、蜂窝网络首次配对和通知、息屏/Doze。详见 `docs/ntfy-device-validation.md`；不将 JVM FakeDao 或回环测试算作这些项目通过。

按上述顺序在当前会话实施。用户工作区原先干净；本次不自动提交、安装或发布。
