# Win2Mobile v3 后端独立审查

> 此文保留修复前的原始发现。2026-09-30 后续修复和执行结果已更新：生产 UserKeySet/跨进程证书初始化、到期 Changed、停机清理、测试入口及覆盖均已修复；后端 runner 通过。当前结论以 [最终复审](backend-final-review.md) 和 [实施验证](backend-task-report.md) 为准。

审查日期：2026-09-30。分支：`codex/lan-kotlin-rebuild`。给定 base/head 均为 `253534b`；实现尚未提交，所以本次审查对象是 `.artifacts/backend-review-source.txt` 与对应工作区文件，而不是空的提交 diff。依据为 `docs/protocol-v1.md`、实施计划 Task 1 和 `docs/backend-task-report.md`。

使用 requesting-code-review 技能完成独立静态审查；未修改实现、未运行构建或测试、未派生代理。行号为本次读取的源码行号。

## 两个结论

- **Spec 合规：未通过验收。** 公共接口、JSON 字段、头部认证、持久事件/凭据/确认、明确批准和有序补发均已实现；Windows 生产证书的 TLS 私钥模式存在阻断问题，且计划要求的验证尚无执行证据。测试入口也由计划的 `dotnet test` 改为 console runner，需要明确接入默认验证入口或恢复可发现的测试项目。
- **代码质量：需修复后复审，不建议当前状态合并。** 存储及流衔接设计合理，未发现可直接证明的 SQLite 消息丢失或同实例补发遗漏；生产 TLS、证书跨进程初始化及关键测试覆盖仍需处理。

## 优点与已核对行为

- `BridgeStore.cs:25-40,60-80`：WAL/FULL、持久 server UUID、AUTOINCREMENT、event/source 唯一约束与参数化 SQL；同实例使用锁，跨实例插入冲突后重新判断来源重复。单条 INSERT 的提交和唯一约束承担原子性，事件信号在锁外发出。
- `BridgeStore.cs:99-132`：256 bit token 仅持久存 SHA256，设备删除后认证失败；SQL `MAX` 使确认游标单调，拒绝负数和超过当前高水位的确认。
- `PairingService.cs:45-90`：secret 哈希比较、到期判断和批准在同一锁内；批准前无 token，重复批准不会重复注册；刷新立即使旧二维码无法创建新请求。批准响应到期后清除内存 token，已配对设备仍有效，符合请求寿命与设备寿命的分离。
- `LanBridgeHost.cs:116-174`：只读取 Bearer 头；WSS 先订阅事件/撤销再验证设备及读取历史；有界通道只承担唤醒，数据库游标承担事实来源，积压每 200 条继续补取，因此信号合并不会等同于丢消息。撤销取消等待，设备状态也在发送前检查。
- `LanBridgeHost.cs:179-204`：关闭时取消并等待接收任务、解除事件订阅、释放 socket 和流名额；客户端 Close 与主机停止路径都有显式处理。未将这些静态判断当作运行验证通过。
- TLS 指纹取完整 DER SHA256；DPAPI 加密 PFX、随机临时文件和原子替换减少明文私钥与半写文件风险。匿名 health 未公开正文或设备。

## Critical（必须修复）

### C1：生产证书使用 Windows Schannel 不支持的 EphemeralKeySet，HTTPS/WSS 握手被阻断

- **位置：** `src/Bridge.Transport.Lan/CertificateManager.cs:22,41`；消费路径为 `src/Bridge.Transport.Lan/LanBridgeHost.cs:58`。
- **触发：** Windows 上新建或从 DPAPI 文件重载证书，再把返回值交给 LanBridgeHost。两条路径都强制使用 `X509KeyStorageFlags.EphemeralKeySet`；`HasPrivateKey` 为 true 并不保证 Windows TLS 提供程序能使用该私钥。主机可能成功绑定端口，但实际客户端握手会遭遇 Schannel 无凭据错误，所有配对和通知传输均不可用。
- **证据：** Microsoft 的 [SslStream 排错文档](https://learn.microsoft.com/en-us/dotnet/core/extensions/sslstream-troubleshooting) 明确列出 Windows ephemeral 私钥握手问题；[.NET 10.0.9 Windows TLS 实现](https://raw.githubusercontent.com/dotnet/runtime/v10.0.9/src/libraries/System.Net.Security/src/System/Net/Security/SslStreamPal.Windows.cs) 的 `AcquireCredentialsHandle` 仍在 228-233 行处理该模式导致的 `NoCredentials`。这是平台实现与代码组合得出的静态结论，本次未运行本机握手复现。Kestrel 10 的 [HTTPS 中间件](https://raw.githubusercontent.com/dotnet/aspnetcore/v10.0.0/src/Servers/Kestrel/Core/src/Middleware/HttpsConnectionMiddleware.cs) 直接将已有私钥证书构建为 `SslStreamCertificateContext`，未替应用重导入为持久私钥。
- **修复：** 保留 DPAPI 文件保护，但用 Schannel 可用的当前用户密钥存储模式导入，例如明确采用 `UserKeySet` 并管理临时或持久密钥生命周期；不导出明文 PFX 文件。随后用 `CertificateManager.GetOrCreate` 的新建和重载返回值分别完成真实 HTTPS health 与 WSS 握手。
- **测试缺口：** `tests/Bridge.IntegrationTests/Program.cs:29-32,54` 使用另一张临时 CreateSelfSigned 证书启动主机；`164-167` 对生产证书仅检查 HasPrivateKey/Thumbprint，完全没有握手验证。不能由这两类检查拼接推断生产 TLS 通过。

## Important（应修复）

### I1：跨进程首次创建证书可能覆盖已有身份，破坏重启后的指纹固定

- **位置：** `src/Bridge.Transport.Lan/CertificateManager.cs:9,14-17,39-41`。
- **触发：** 两个进程使用同一用户 dataDirectory，首次启动或证书到期时同时进入 GetOrCreate。`static gate` 只能串行化同进程；两个进程都能观察文件不存在，各生成一张证书并用 `File.Move(..., true)` 覆盖最终文件。进程 A 对外返回 A 的证书，但磁盘可能已经变成 B；A 的客户端配对后，在重启时因证书指纹改变而失联。原子替换防止半写入，并不防止该身份竞争。
- **影响：** 破坏持久证书与已配对客户端固定指纹的一致性。单进程部署不触发；同用户多登录会话或重复后台进程会触发，属于初始化并发边界。
- **修复：** 对规范化目录使用跨进程锁，在锁内重新读文件/检查寿命/创建并保存，保证所有调用返回磁盘最终胜出的同一张证书。补充两个独立进程竞争空目录和到期目录的测试，要求返回 DER 指纹与重载指纹相同。

### I2：现有测试并未覆盖宣称的跨实例插入竞争，也未证明取消后名额复用

- **位置：** `tests/Bridge.IntegrationTests/Program.cs:37-42,100-111,118-137`。
- **触发与不足：** 第 37 行已经插入 source-1..100；第 42 行两个 store 只重放这批已存在的来源，因此均在 SELECT duplicate 提前返回，无法进入 `BridgeStore.cs:73-78` 的竞争分支。流测试只用 120 条，未超过 200 条分页边界；客户端收到第 10 帧后追加消息时，服务端可能早已把全部历史发送到缓冲区，因此不能保证测试覆盖历史读取与订阅的关键窗口。停机和撤销各测试单条流，并未在并发取消后重新取得全部 32 个名额。
- **影响：** 核心风险点一旦回归，runner 仍可能报告 PASS；尤其是用户要求的幂等并发和资源生命周期证据不足。此项是测试覆盖缺口，不是已证明存在消息丢失。
- **修复：** 两个 store 对从未写入的相同来源同步起跑并断言唯一事件/总数/重启结果；历史使用超过 200 条，加入可控同步点让追加发生在订阅和首批读取边界；多个流同时撤销、断开、停机后，再连接验证名额可全部复用。不要只用更多重复次数替代明确的同步窗口。

### I3：测试项目不能被计划要求的 dotnet test 发现执行

- **位置：** `tests/Bridge.IntegrationTests/Bridge.IntegrationTests.csproj:1-4`；`docs/backend-task-report.md` 的验证入口。
- **触发：** 按计划 Task 1 执行 `dotnet test`。当前项目只有 `OutputType=Exe` 和 ProjectReference，没有测试 SDK、adapter 或可发现测试；成功构建这个 console 项目不代表运行其 Main 中的任何断言。
- **影响：** 与既定验收命令不一致，默认构建/CI 若使用 dotnet test，存在“命令成功但零验证”的风险。console runner 本身可行，但必须明确被执行。
- **修复：** 接入标准测试项目，或在默认本地/CI 验证脚本中显式执行 `dotnet run --project tests/Bridge.IntegrationTests -c Release` 并检查退出码，同时明确修改验收入口，防止 dotnet test 被当作覆盖证明。

## Minor（可随后修复）

### M1：读取状态抢先处理到期时会吞掉 Changed 通知

- **位置：** `src/Bridge.Transport.Lan/PairingService.cs:37,65,87-94`。
- **触发：** 请求刚过期，在下一次 1 秒 timer 回调前先调用 PendingRequests 或 GetStatus。它们调用 ExpireLocked 将请求变为 expired，却忽略返回的 changed 标志；后续 timer 再执行时已经没有状态变化，因此不会触发 Changed。只依赖事件刷新的消费者可能保留过期请求，直到另一操作发生。
- **修复：** 在锁内统一收集状态变化，锁外发一次 Changed；不要从 getter 锁内调用用户事件。增加推进 fake clock 后先读取再等待 timer 的事件断言。现有桌面还有定期刷新，所以此项未按桌面功能阻断分类。

## 无法验证与后续验证要求

- SDK 尚未完成安装，本次没有编译/恢复/runner 运行证据。没有把源码接口检查当作“可构建”，也没有确认任何现有集成测试已通过。
- StopAsync 在内部 Stop/Dispose 抛错时 `application` 不会清空；DisposeAsync 在停机抛错后还会留下 `disposed=true` 并跳过 Pairing.Dispose。代码值得用 finally 保证清理，但当前无法证明给定 Kestrel 配置仅由 token 超时就必然触发异常，因此没有把这一推测列成确定的功能故障。需用短超时及故障注入验证停止、再次启动、再次 Dispose 的状态。
- WSS 收发取消、撤销 Close/Abort 的实际时序以及等待任务是否全部释放，需要 Windows 回环测试。撤销是在已开始发送的帧之后终止；本次未把合理的在途帧并发视为认证绕过。
- 配对的到期拒批、错误 secret 与批准前无 token 有静态实现和已有测试；批准接近到期、重启中断待批准请求、批准后撤销再轮询的执行证据仍缺少。
- 手机端指纹与证书有效期检查不属于本次后端审查范围。现有 runner 的 TLS 回调只检查指纹，不能当作客户端证书有效期策略验证。
- Windows 防火墙、实际局域网设备及 Android 后台可靠性均未验证。

建议先修复 C1 并补生产证书握手，再处理 I1/I2/I3，以真实构建和 runner 结果作为下一次验收依据。上述风险修复前，Task 1 不应标记为已验证完成。
