# Win2Mobile v3 后端最终独立复审

审查日期：2026-09-30。范围为实际工作区的 `src/Bridge.Core`、`src/Bridge.Transport.Lan`、`tests/Bridge.IntegrationTests`、`scripts/test_backend.ps1`，以及协议和验收入口。Base/HEAD 均为 `253534b2c376fa80279726e72768d1cdfc26abe4`；实现尚未提交，不能用空的 commit diff 代表审查内容。依据 `docs/protocol-v1.md` 与实施计划 Task 1，按 requesting-code-review 的 reviewer 模板分别判断 spec 和 quality。

本次只读实现与已有执行日志，没有再次执行整套测试、修改源码或 git 状态；唯一新增文件为本报告。执行退出码由主线程提供，日志内容与当前源码由本审查独立核对。

## 两个结论

- **Spec：后端本地验收通过。** 持久事件、来源幂等、服务器身份、设备凭据哈希、单调确认、明确批准配对、HTTPS/WSS 头部认证、历史与实时有序流、重连和撤销均符合协议。生产证书新建与重载握手、跨进程身份竞争，以及 console runner 的默认入口已经补齐。结论限定为后端和 Windows 回环验收。
- **Quality：通过，未发现尚待修复的 Critical 或 Important 实现问题。** 当前实现可继续集成。交付前应更新旧任务报告的 Minor 事实口径；真实局域网、手机后台和安装授权仍属于后续验证。

## Strengths

- `BridgeStore.cs:25-40,58-82,86-98,126-133`：WAL/FULL、持久 server UUID、唯一来源约束、自增序号与参数化 SQL构成清晰的持久边界。跨 store 先读后插入的竞争由 SQLite 唯一约束裁决，冲突后重新确认来源确实存在，其他约束错误继续抛出。事件通知在存储锁外发出；确认 SQL 使用 `MAX`，拒绝超过高水位与负数。
- `CertificateManager.cs:17-57,60-61`：目录大小写、尾分隔符和点路径归一化后建立 Global 命名 mutex；获得锁后才读/检查/生成/替换证书。超时明确失败，abandoned mutex 在取得所有权后继续受保护操作，finally 释放。DPAPI 文件与随机临时文件原子替换保留身份；新建和重载均用 `UserKeySet`。明文 PFX 的托管及非托管临时缓冲均清零，返回证书由调用者管理寿命。
- `LanBridgeHost.cs:133-187`：先订阅再验证设备/读取历史，唤醒通道只承载信号，事实来源始终是数据库游标。每次按序读最多 200 条，满页继续读；信号合并不丢记录。撤销取消等待，每条发送前再次查活动状态；正常在途帧的竞态不属于认证绕过。接收观察任务与取消源统一清理，解除订阅、释放 socket 和名额。
- `LanBridgeHost.cs:207-232`：生命周期 semaphore 串行化开始、停止和销毁；停止先清空 application，即使 Stop 失败仍 Dispose；销毁即使停机失败仍销毁 Pairing。预取消等待不会清空运行中的 application。
- `PairingService.cs:36-47,57-114,116-124`：状态变更在锁内收集，在锁外发布 Changed。到期与错误配对码/不存在请求的早返回路径保留变更通知；事件消费者可读取状态而不被配对锁阻塞。到期清理请求 token 不会误撤销已经配对的持久设备。

关于平台行为，Microsoft 文档确认 `UserKeySet` 将私钥导入当前用户存储，`PersistKeySet` 决定是否保留导入密钥；本实现没有采用 PersistKeySet。文档也确认 Global 命名对象支持跨登录会话，额外 SeCreateGlobalPrivilege 要求针对 file mapping/symbolic link，而不是本实现的 mutex。这些官方说明与本机握手/子进程证据相符：[X509KeyStorageFlags](https://learn.microsoft.com/en-us/dotnet/api/system.security.cryptography.x509certificates.x509keystorageflags)、[Kernel object namespaces](https://learn.microsoft.com/en-us/windows/win32/termserv/kernel-object-namespaces)。

## Issues

### Critical（必须修复）

无新增或未关闭发现。

### Important（应修复）

无新增或未关闭实现发现。关键验收缺口已获得当前运行证据；进一步确定性竞态测试建议列为下面的边界，不据此推断存在消息丢失。

### Minor（交付口径）

**M2：旧后端任务报告仍描述修复前代码和验证状态。**

主线程后续修复记录：已更新backend-task-report.md为10.0.12/UserKeySet/450+21/实际PASS，并在原backend-review-report.md顶部说明历史与最终复审的关系。本条原始文档问题已处理。

- **位置：** `docs/backend-task-report.md:5,10,20,24,28,30`；旧首次审查结论保存在 `docs/backend-review-report.md:9-10`。
- **触发/问题：** 阅读旧任务报告时仍得到 Microsoft.Data.Sqlite 10.0.0、EphemeralKeySet、100+20 条流覆盖、尚待 SDK 和未改脚本的描述；当前是 10.0.12、UserKeySet、450+21 条和已通过的执行入口。旧首次审查也仍显示阻断结论。
- **影响：** 读者可能误判生产 TLS、依赖修补与验证状态，交付证据相互矛盾；不影响当前代码运行。
- **修复：** 更新任务报告为实际版本/证书模式/覆盖和执行结果；保留首次审查历史并注明已被本次复审取代，避免抹掉原发现。

## 原发现关闭状态

| 原 ID | 状态 | 当前源码与证据 |
| --- | --- | --- |
| C1：EphemeralKeySet 导致 Windows TLS 阻断 | 关闭 | `CertificateManager.cs:60-61` 新建/重载统一 UserKeySet；runner `Program.cs:47,67-70,196-199,351-361` 对生产证书执行真实 HTTPS/WSS，日志 PASS。 |
| I1：跨进程创建证书覆盖身份 | 关闭 | `CertificateManager.cs:17-57` 的跨进程 Global mutex 覆盖读取到返回；`Program.cs:365-395` 两个独立子进程同步竞争空目录，路径别名归一化后返回同指纹并与重载一致，日志 PASS。 |
| I2：竞争和流名额覆盖不真实 | 关闭原阻断，保留测试边界 | `Program.cs:203-225` Barrier 同时竞争全新 source；`116-143` 多页历史与实时；`261-295` 32 条流同时断开、撤销、停机后重新占满全部名额，日志 PASS。同步起跑不是强制双方 SELECT 都先完成，收到第 10 帧后追加也不是服务端读取窗口注入；详见边界。 |
| I3：dotnet test 不会执行 console runner | 关闭 | `scripts/test_backend.ps1:12-13` 明确 run 并检查退出码；计划 Task 1、README 与 `.github/workflows/build.yml:16-18` 使用此入口，明确 dotnet test 不执行 runner。 |
| M1：状态读取吞掉到期 Changed | 关闭 | `PairingService.cs:36-114` 在锁外发通知，早返回/异常路径使用 finally；`Program.cs:228-258` 对 pending/status/approve/deny/create 五条路径验证仅一次 Changed，以及回调跨线程读取不会死锁。 |
| 原附注：Stop/Dispose 异常残留状态 | 关闭静态清理风险 | `LanBridgeHost.cs:217-232` 使用 finally 清理、先清空 application；32 流正常停止/再启和预取消调用已验证。内部 Stop/Dispose 故障注入仍未执行。 |

## 执行证据与边界

- `.artifacts/backend-tests.log` 当前内容为 `Native SQLite: 3.53.3` 和完整 PASS；主线程确认 `scripts/test_backend.ps1` 退出 0。`Program.cs:37-42` 校验运行时原生 SQLite 至少 3.50.2。当前 Core 引用 Microsoft.Data.Sqlite 10.0.12，两端 project.assets.json 解析 SQLitePCLRaw.lib.e_sqlite3 2.1.12。
- `.artifacts/windows-publish.log` 显示 .NET SDK 10.0.401 和 Windows x64 自包含发布成功，当前日志未出现漏洞警告。旧 `.artifacts/windows-build.log` 保留修补依赖前的 2.1.11 NU1903，不能当作当前依赖状态；主线程确认最新 publish 退出 0。
- 当前竞态测试证明真实 fresh-source 并发行为、分页、最终有序流和全部名额复用，未确定性控制 server 的订阅/首批查询窗口或双方重复预查询窗口。静态检查未发现这两处存在遗漏路径；可在以后引入恰当同步点增强回归定位能力，不能宣称本 runner 已穷举竞态时序。
- 子进程证书测试覆盖首次创建和路径别名；没有同时竞争已到期证书、跨登录会话、mutex abandoned/超时，或导入密钥容器崩溃清理测试。到期分支共享同一锁；不把未测分支当作已存在代码缺陷。调用者必须先停止/销毁主机，再销毁其证书及 store。
- EventsChanged/DeviceRevoked 是同 store 实例的进程内信号。其他 store 写入同数据库时，既有流仍会在下一次最多约 25 秒的心跳循环读到消息并检查撤销；没有跨进程即时唤醒保证。当前桌面使用单 store，跨 store 验证目的为 SQLite 持久幂等并发。
- 未验证 Windows 防火墙、实际 Wi-Fi 链路、Android TLS/Room/后台息屏、MSIX 安装与真实通知权限。回环测试和 publish 成功不能代表这些跨模块/实机验收。

## Assessment

**Ready to merge? Yes，限本次后端代码范围；交付时同步更新 Minor 文档口径。**

实现与协议一致，原生产 TLS 和跨进程身份风险已经修复，并有对应真实运行证据；未发现新的高优先级故障。后续集成验收应保持上述平台与时序边界的准确表达。
