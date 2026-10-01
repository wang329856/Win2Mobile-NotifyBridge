# Android v3 独立代码审查

审查范围：`src/android`、`scripts/build_android.ps1`，并按 `docs/protocol-v1.md` 与实施计划 Task 3/4 核对跨端协议。Base/HEAD 均为 `253534b2c376fa80279726e72768d1cdfc26abe4`，实际审查对象是工作区未提交文件，不能用空 git diff 代替源码。审查仅写本报告，未改源码、索引、HEAD 或分支，未执行构建。

## 当前判定

- Spec：**本地开发验收通过**。协议与实现静态对齐，26 项 JVM 回归（包括真实 loopback HTTPS/WSS）、lint 和 debug APK 构建通过；Android 设备验收尚未完成。
- Quality：**通过**。提醒遗漏、普通关闭误停用、传输接口缺失、重新配对游标回退及 minSdk 兼容问题已修复，当前无已确认未修复的 Critical/Important。
- Ready to merge：**Yes（本地开发范围）**。未提交、推送或发布；此结论不代表发布签名、手机安装或息屏后台可靠性已验证。

## Strengths

- JSON 字段与 C# 协议一致，HTTPS/WSS 凭据仅放请求头；固定完整证书 DER SHA256，同时检查证书时间，禁止重定向。
- Android Keystore AES-GCM 保存凭据，Room 事件唯一键及事务入库防重复，未知来源、序号间隙和内容冲突会拒绝推进游标。
- 接收服务由单个 coordinator 串行 cancelAndJoin；网络选择包含无外网 Wi-Fi、关闭握手及时回复，后台诊断明确 Doze 与实机验证边界。
- Compose 提供扫码/粘贴、批准等待与取消、搜索/筛选/加载更多、详情、移除及清历史；清历史保留游标，移除本地设备会删除对应历史。

## Issues

### Critical

当前未发现已确认的 Critical 问题。

### Important

1. **已修复：重新配对可覆盖接收线程刚推进的游标**
   - 文件：`src/android/app/src/main/java/com/notifforward/app/MainActivity.kt:85`。
   - 原先读取 `Computer` 后单独完整 Upsert。两次挂起之间旧接收线程可能保存新事件、推进游标，Upsert 却恢复旧快照；若同时清历史，已清理事件会被重新补取。
   - 当前 Activity 调用 `BridgeDatabase.kt:37` 的 `@Transaction savePairing`，在同一事务读取当前游标并保存配对元数据。`BridgeDaoTest.kt:53` 补充旧快照不会覆盖最新游标的回归；FakeDao 不证明 Room 的真实事务锁，仍需后续设备数据库测试。

2. **已修复：ACK 失败吞掉实时系统提醒**
   - 文件：`src/android/app/src/main/java/com/notifforward/app/BridgeService.kt:142`、`Protocol.kt:84`。
   - 原先保存并推进游标后等待 ACK，ACK 失败就不会展示提醒，重连从新游标开始也不会再收到该事件。
   - 当前真实服务调用 `EventDelivery.deliver`，保存后先展示，再 ACK；展示失败不会阻塞保存确认。4 项 EventDelivery 回归全部通过。

3. **已修复：普通电脑停止被当成授权撤销**
   - 文件：`src/android/app/src/main/java/com/notifforward/app/BridgeService.kt:108`、`BridgeTransport.kt:25`。
   - 当前后端普通停止也可能发送关闭码 1008；原 Android 会永久禁用电脑。
   - 当前 1008 等关闭码均进入重连，只有下次认证握手或 HTTP 返回 401/403 才进入需授权状态。真实服务调用已核对，2 项关闭/鉴权分类回归及真实 HTTP 鉴权回归通过。

4. **已修复：缺少远程传输扩展边界**
   - 文件：`src/android/app/src/main/java/com/notifforward/app/BridgeTransport.kt:9`、`BridgeApplication.kt:10`。
   - 当前配对与后台接收均通过 `BridgeTransportFactory` 创建传输；LAN 是首版实现。扩展接口仍使用 OkHttp WebSocket 类型，但已有明确实现替换位置。

5. **已修复：API 30 调用不兼容 minSdk 26**
   - 文件：`src/android/app/src/main/java/com/notifforward/app/BridgeService.kt:56`。
   - 首次 lint 发现 `NetworkRequest.Builder.clearCapabilities()` 需要 API 30，原实现可能使 Android 26–29 在服务启动时失败。
   - 当前逐项移除默认的 NOT_RESTRICTED、TRUSTED、NOT_VPN 能力，使用兼容 minSdk 的 API。最终 lint 已无错误。`LanClient.kt:35` 的 DNS 绑定也改为显式 `Dns.lookup` 实现，最终编译通过并保留所选 Network 的 DNS 与 socket 绑定。

### Minor

1. **已修复：最后一次保存后的 ACK 失败可能长期落后**
   - 文件：`src/android/app/src/main/java/com/notifforward/app/BridgeService.kt:133`。
   - 原先重连后若无新事件就不再 ACK，电脑显示的 acknowledgedSequence 会停留在旧值。
   - 当前握手成功后先确认当前本地游标，随后报告已连接。下一次连接可以补回失败的确认。

2. **已修复：多电脑的常驻通知文字互相覆盖**
   - 文件：`src/android/app/src/main/java/com/notifforward/app/BridgeService.kt:138`、`BridgeService.kt:165`。
   - 原先常驻通知随最后一台事件显示“接收”或“中断”，不能表达整体状态。
   - 当前统一显示“后台接收已开启 · 具体连接见电脑页”，不再把单台设备状态当成全局连接成功；每台状态仍通过电脑页的实际握手结果显示。

## 实际验证证据

- 本审查未执行构建或测试，只读复核源码及根线程产生的最终日志、测试 XML、lint 文件与 APK。根线程最终完整脚本 session 7105 已返回 exit code 0；`.artifacts/android-build.log` 可读尾部为 `BUILD SUCCESSFUL in 44s`，58 个任务中 18 个执行、40 个 up-to-date，并打印分发 APK、测试与 lint 路径。
- `src/android/app/build/test-results/testDebugUnitTest/` 的 5 个 XML 共 **26 项测试通过，failures/errors/skipped 均为 0**：

| 测试套件 | 项数 |
| --- | ---: |
| BridgeDaoTest | 5 |
| EventDeliveryTest | 4 |
| LanClientTest | 6 |
| ProtocolTest | 9 |
| StreamFailuresTest | 2 |

- `LanClientTest` 使用 MockWebServer 与 HeldCertificate 调用真实 LanClient HTTPS/WSS，已验证批准、请求头秘密与凭据、401/403、错误 DER 指纹、HTTP 取消、ACK 回退及 WSS 游标；其余回归验证协议、保存/展示/确认顺序及游标算法。
- 最终 `src/android/app/build/reports/lint-results-debug.xml` 与 `.txt` 均为 **0 errors、21 warnings**。警告包含固定 target/依赖升级、定制证书 TrustManager、备份规则及图标/风格建议。TrustManager 并未跳过验证：完整 DER pin、有效期、错误 pin 的真实 TLS 拒绝已有源码和测试证据；targetSdk 35 与协议指定一致。这些警告不阻断当前开发验收，仍保留原始报告供后续处理。
- 分发文件 `dist/android/Win2Mobile-3.0.0-debug.apk` 已存在，**12,537,743 bytes**。它与 `src/android/app/build/outputs/apk/debug/app-debug.apk` 的 SHA256 相同：`2D213F841BD8F4A9857C14D7EDCC6CCC89FB580B4A66D858DC30F50E7B0D6B98`。
- `scripts/build_android.ps1:31` 在复制 APK 前运行 testDebugUnitTest、lintDebug、assembleDebug 并检查退出码；新增 `GradleInitScript` 是显式可选参数，未设置时不注入缓存/仓库脚本。release 签名未配置时明确失败，当前交付为 debug APK，发布签名尚未验收。

## 实际验证边界

- 纯协议/指纹/游标测试、FakeDao 测试不能证明 Room 生成 DAO 的真实事务、磁盘重启或 Android Keystore 行为。FakeDao 使用真实 accept/savePairing 算法，但不具有 Room 的事务隔离或回滚。
- 根线程确认 ADB 当前无连接设备；本审查未安装 APK，也未进行手机操作。
- Android 停止/重启/切网、多电脑、系统权限、开机、相机扫码、Doze/息屏及厂商省电策略仍需设备验证。缺少设备验证在此作为验证限制，不冒充已确认源码缺陷。
