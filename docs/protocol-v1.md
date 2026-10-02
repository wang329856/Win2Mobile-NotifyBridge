# 局域网协议 v1（实现约束）

所有 JSON 使用 camelCase。默认端口 47721；服务使用 HTTPS/WSS。认证凭据只放入 Authorization: Bearer 请求头，禁止 URL token。时间为 UTC ISO 8601。Android minSdk 26、targetSdk 35、compileSdk 36。Windows .NET 10、系统目标 Windows 10 build 19041+。Android applicationId 为 com.notifforward.app；应用版本以当前构建配置为准。

## 二维码

内容为 JSON：`{"schema":"win2mobile-pair","protocolVersion":1,"baseUrl":"https://192.168.1.2:47721","certificateSha256":"证书 DER 字节的 SHA256 大写十六进制","pairingCode":"256 bit 随机值","serverId":"稳定 UUID","serverName":"电脑名称"}`。配对码 120 秒有效，由电脑刷新。指纹是完整证书的哈希，不是 SPKI。客户端严格验证这个指纹和证书有效期；在此条件下处理本地自签名证书及地址变化。

## 配对

POST /v1/pairing/requests，body `{pairingCode,deviceName}`。成功 202：`{requestId,requestSecret,status:"pending",expiresAt}`。

GET /v1/pairing/requests/{requestId}，头 `X-Pairing-Secret` 为首次响应秘密。200：`{status:"pending"|"approved"|"denied"|"expired",deviceId?,accessToken?,serverId,serverName,startSequence,ntfy?}`。批准时冻结 startSequence 为当前电脑序号，LAN 和中转均不向此设备提供更早记录。手机每秒查询，最长 120 秒；电脑明确批准才能取得 token。requestSecret 和 token 不写日志。

## 健康与事件

GET /v1/health，无认证：`{protocolVersion:1,serverId,serverName,status:"ready",highWatermark}`，不公开通知正文或设备列表。

GET /v1/events?after=0&limit=200，Bearer 认证：`{events:[BridgeEvent],startCursor,nextCursor,highWatermark}`。limit 1..200；after 非负。实际 startCursor 不低于此设备的授权起点及已清理队列前缀。游标为每台电脑持久递增序号，队列全部清理也不重置。

GET /v1/events/stream?after=0，Bearer 认证，WebSocket。新会话首次连接增加 `live=true` 跳过已存在通知；同一会话重连不使用该参数。首帧 `{kind:"hello",protocolVersion:1,serverId,highWatermark,startCursor}`。之后每条 `{kind:"event",event:BridgeEvent}`，25 秒心跳 `{kind:"heartbeat",serverTime}`。临时队列清理追上当前连接时发送 `{kind:"checkpoint",highWatermark,startCursor}`，手机验证其不倒退、不超过高水位后跳过过期前缀。补收与实时消息使用相同有序流；撤销设备后终止连接。手机保存成功后 POST /v1/acks，body `{sequence}`，200 `{acknowledgedSequence}`。确认值必须非负，不超过服务器高水位，单调推进。

BridgeEvent：`{sequence:1,eventId:"UUID",sourceDeviceId:"服务器 ID",sourceNotificationId:"来源 ID",appId:"稳定应用 ID",appName:"显示名",title:"标题",body:"正文",occurredAt:"2026-09-30T00:00:00Z"}`。同一个 eventId 重放不产生第二条手机记录，已删除记录也不会重新插入。保存确认与用户阅读不同。本次会话内断网补收保存但不成批响铃；首帧高水位以内视为重放。手机手动开始会话清空消息内容并保留进度；单条删除保留 eventId 标记，清空以最大已接收序号阻止此前记录再次显示。

Windows 使用按入库时间最多 24 小时、最多 1000 条的临时发送队列，清理连续前缀而不重置 SQLite 自增序号。没有授权设备时不采集入库；新设备授权前通知无法通过其 token 读取。此队列用于短暂断网和发送重试，不是永久历史。

## 桌面和后端之间的 C# 接口

命名空间 Win2Mobile.Core：

```csharp
public record CapturedNotification(string SourceNotificationId, string AppId, string AppName, string Title, string Body, DateTimeOffset OccurredAt);
public record BridgeEvent(long Sequence, string EventId, string SourceDeviceId, string SourceNotificationId, string AppId, string AppName, string Title, string Body, DateTimeOffset OccurredAt);
public record ServerIdentity(string ServerId, string ServerName);
public record PairedDevice(string DeviceId, string DeviceName, DateTimeOffset CreatedAt, long AcknowledgedSequence);
// BridgeStore(string databasePath, string serverName), IDisposable
// Identity; HighWatermark; Append(CapturedNotification)->BridgeEvent?
// GetEvents(long after, int limit=200)->IReadOnlyList<BridgeEvent>
// GetDevices()->IReadOnlyList<PairedDevice>; RevokeDevice(string deviceId)
```

命名空间 Win2Mobile.Transport.Lan：

```csharp
public record PendingPairing(string RequestId, string DeviceName, DateTimeOffset ExpiresAt);
// LanBridgeHost(BridgeStore store, X509Certificate2 certificate, int port=47721), IAsyncDisposable
// Pairing.PendingRequests; Pairing.Changed EventHandler; Pairing.Approve(string requestId); Pairing.Deny(string requestId)
// StartAsync(CancellationToken token=default); StopAsync(CancellationToken token=default)
// GetPairingPayload(string host)->string JSON; Pairing.RefreshCode()->void
// CertificateManager.GetOrCreate(string dataDirectory)->X509Certificate2
```

桌面 dataDirectory 为用户 LocalApplicationData/Win2Mobile；应用把 BridgeStore 存为 bridge.db。接收过滤在入库前进行。桌面诊断状态不可用固定 Running 文本；权限请求必须在真实 WPF UI 线程执行。仅平台 Toast 属于首版采集范围。

## 信任与运行

已配对电脑的 token 使用 Android Keystore 加密保存；通知存 Room。Windows 持久存 token 哈希、配对设备与确认游标。电脑生成的证书持久保存在用户目录并保护私钥。电脑窗口批准/拒绝请求，支持设备撤销。二维码配对只创建临时请求；服务对配对请求限流并限制待批准数量。

后台接收采用 Android connectedDevice 前台服务及相应权限，常驻状态通知、网络回调、退避重连、正常开机恢复。前台服务不自动豁免 Doze；界面说明后台状态和电池设置，需手机实测后才能宣称息屏可靠。
