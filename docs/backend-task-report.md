# 后端 Task 1 实现与验证

## 实现

- `src/Bridge.Core`：.NET 10，Microsoft.Data.Sqlite 10.0.12，当前实际原生 SQLite 3.53.3。SQLite WAL + FULL 同步；单实例连接串行访问，跨实例竞争通过唯一约束去重。服务器 UUID、消息 UUID/自增序号、设备、token SHA256 和确认游标持久化；同一来源通知 ID 重放不新建事件，确认值拒绝越界并单调推进。
- `src/Bridge.Transport.Lan`：ASP.NET Core FrameworkReference，Kestrel 仅 HTTPS/WSS，协议 v1 所有字段使用 camelCase。匿名 health 不返回通知或设备；事件与确认只接受 Bearer 请求头。
- 配对码和请求 secret/token 均为 256 bit 随机值。配对码及请求 120 秒到期；secret 在内存仅存哈希；UI 明确批准后生成 token。批准结果 token 仅在临时请求内存保留，设备持久记录只存 token 哈希。批准/拒绝/到期/刷新均支持 Changed 和 PendingRequests；刷新立即作废旧二维码。
- 8 个待批准请求、128 个暂存请求上限；按远端 IP 配对创建每分钟 12 次、轮询每分钟 180 次；请求体最大 4096 字节，头数量/大小和连接数量有上限。
- WebSocket 在读取高水位与历史前订阅唤醒信号；有界信号只做唤醒，SQLite 为消息事实来源。历史和实时统一按持久序号补取，25 秒心跳；撤销即时取消现有连接，关闭主机取消等待中的流；单主机最多 32 条流。
- `CertificateManager.GetOrCreate`：Windows 当前用户 DPAPI 加密持久 PFX；UserKeySet 导入为 Schannel 可用的临时密钥容器，证书 Dispose 后释放，不使用 PersistKeySet。规范化路径的跨进程 mutex 防止初始化竞争改变固定指纹；新建和重载均以真实 HTTPS/WSS 握手验证。证书到期后重建（客户端需要重新配对）。完整 DER SHA256 写二维码。

## 验证入口

`tests/Bridge.IntegrationTests` 是无额外测试框架依赖的集成 console runner；失败抛异常、非零退出。

```powershell
& ./.tools/dotnet/dotnet.exe run --project tests/Bridge.IntegrationTests/Bridge.IntegrationTests.csproj -c Release
```

覆盖全新来源的跨实例同步竞争、UUID/序号/设备凭据/确认游标重启；secret 校验、批准前无 token、拒绝、配对码和请求到期、Changed 通知；HTTPS 鉴权、分页与参数限制、确认越界/单调；WSS 鉴权、450 条历史与 21 条实时、游标重连、心跳、32 流并发断开/撤销/停机后名额复用；生产 DPAPI 证书新建/重载握手及独立进程的同指纹初始化。

## 当前执行证据

2026-09-30：工作区 SDK10.0.401 执行 scripts/test_backend.ps1 退出码0；.artifacts/backend-tests.log 为 Native SQLite: 3.53.3 与 PASS。新增原生版本断言要求 >=3.50.2，警告 CS8602 已修复。Windows 自包含发布与 MSIX schema 打包另有通过日志。最新复审见 backend-final-review.md；原 backend-review-report.md 保留为修复前发现记录。

## 范围与限制

公共接口保持 `docs/protocol-v1.md` 原约定，附加 API 只用于模块间内部消费或测试。全部历史目前保留，没有后台清理或消息截断。局域网实际设备、Windows 防火墙与 Android 熄屏后台接收属于跨模块/实机验证，不能由本地回环 HTTPS 测试替代。未改旧版目录、Windows/Android模块、根构建文件或脚本，未提交/推送。

依赖版本来自 [NuGet 官方 Microsoft.Data.Sqlite 10.0.12 页面](https://www.nuget.org/packages/Microsoft.Data.Sqlite/10.0.12)。历史实时窗口测试包含并发追加和跨页补发，但没有注入可控的服务端首批读取同步钩子；该确定性窗口仍属于验证边界。
