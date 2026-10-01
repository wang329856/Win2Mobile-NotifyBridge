# ntfy 跨网络协议 v1

定位：用户不租服务器、不开放电脑公网端口、不要求付费账号。Windows 通过 HTTPS 向 ntfy 发布密文，Android 通过标准 CA 校验的 WSS 订阅并解密。公共中转是外部依赖。电脑默认关闭此功能，原 LAN 保持兼容。

## 配对与授权

支持两种首次授权：原有局域网证书绑定扫码，以及完全远程二维码。两种均必须在电脑明确批准。

局域网 GET approved 响应新增可选 `ntfy` 字段：`{serverUrl,topic,key,startSequence}`。每设备独立随机 240 bit topic 和 AES-256 密钥，仅正确 requestSecret 且设备仍获授权时返回。二维码不包含长期通知密钥。电脑使用当前用户 DPAPI 保存整个中转设备注册表，手机以 Keystore 保护 `serverId:ntfy` 凭据，Room 不保存 topic 或密钥。

远程二维码 schema 为 `win2mobile-ntfy-pair`，protocolVersion=1，字段为 `serverUrl,requestTopic,responseTopic,pairingKey,serverPublicKey,serverId,serverName,baseUrl,certificateSha256,expiresAt`。请求、回复 topic 各为 `w2m_` 加 60 个随机小写十六进制字符（总长 64，符合 ntfy topic 长度限制）；pairingKey 是一次性随机 32 byte AES 密钥；serverPublicKey 是临时 P-256 公钥 SPKI DER 的 Base64；有效期 120 秒。二维码通过用户选择的可信渠道交给自己的手机，不能公开分享。

手机产生临时 P-256 密钥对、随机 requestId，将 `{requestId,deviceName,clientPublicKey}` 用 pairingKey 加密后 POST 到 requestTopic。电脑解密并创建原 PairingService 的待批准请求。授权回复使用 `SHA256(ECDH P-256 shared secret)` 作为 AES 密钥，与该手机单独加密；只有二维码密钥的其他手机无法解密已批准设备凭据。六位校验码为 `SHA256(replyKey)` 前四字节的大端无符号整数模 1,000,000，补齐六位；两端显示，电脑核对后批准。

远程请求/回复 AES-GCM envelope：`{requestId,nonce,data}`，nonce 为随机 12 bytes，data 为 ciphertext 后拼接 16 byte tag 的 Base64。AAD：`win2mobile-ntfy-pair-v1|serverId|topic|request或response|requestId`，UTF8 无 BOM。回复明文是 PairingStatus，仅批准后包含设备 token 和独立 ntfy 通知凭据。每个二维码最多创建 8 个请求，重复 requestId 不再创建授权；畸形/未认证消息忽略。过期不接受新请求，已提交请求仍受自身 120 秒批准期限约束。刷新或关闭会取消未完成会话。手机请求不访问电脑 LAN 地址，回复中提供的 LAN 元数据仅用于之后可选的 LAN 补齐。

`startSequence` 为电脑批准该设备时冻结的高水位，LAN 与中转共用授权边界。中转只发送此后的新通知，LAN 也不提供授权前记录。更换服务地址需先撤销绑定旧服务的设备再配对。手机选择 LAN 不停止电脑向该设备的中转发布，需要停止时关闭电脑开关。

## 通知加密与分片

保留 BridgeEvent 全部内容，使用 camelCase JSON UTF8。每片最多 2400 bytes，最多 128 片，字符内部切分只在完成重组后解码。每片独立 AES-256-GCM、随机 12 byte nonce、16 byte tag：

```json
{"v":1,"id":"事件UUID","index":0,"count":2,"nonce":"Base64","data":"ciphertext后拼接tag的Base64"}
```

AAD：`win2mobile-ntfy-v1|serverId|deviceId|topic|eventId|index|count`。身份、路由、片号和数量均认证。每片 JSON 小于等于 4096 bytes，以 `POST /topic`、`Content-Type: text/plain; charset=utf-8` 发布，不转成附件。设置 `Firebase: no`，不发送标题、应用名、链接或明文业务元数据。无需安装 ntfy App，接收解密由本项目 App 完成。

手机先验证长度、片数和 AEAD，再分配组装条目；最多 8 个未完成事件，12 小时后在下一片到来时清理。重复片内容必须相同；重组后验证来源、事件 ID、序号和时间。错密钥、错设备、错 topic、篡改与畸形片不产生记录，也不阻塞后续合法事件。

## 发布、恢复与撤销

每设备独立可取消循环，从本地 SQLite 发件箱按序发布。默认请求间隔 5 秒。中途 HTTP 429 或网络失败保留当前分片进度，避免重复占用配额的开头分片阻塞长事件；程序重启则从尚未完整发布的事件重新开始。整条事件所有分片被中转接受后才持久推进 PublishedSequence。HTTP 429 遵守 Retry-After（缺省 15 分钟），其他错误缺省 30 秒；等待可取消。撤销取消正在发送的请求，其他设备不受影响。

手机走系统默认互联网路由，保持公有 CA 与主机名验证，不复用 LAN 自签名信任策略。新接收会话首次订阅 `/topic/ws`，不索取缓存；同一会话重连订阅 `/topic/ws?since=lastMessageId`，无消息 ID 时使用 since=all 并过滤会话前消息。完整事件先事务保存 Room，再展示，与 relayMessageId/relaySequence 同事务推进。删除/清空标记与会话时间判断先于插入；被过滤的认证事件仍推进接收进度，不能恢复删除内容。根据已认证 occurredAt 与手机会话起点过滤此前事件，电脑与手机应保持系统时间准确。

LAN cursor 仍要求连续，和中转游标独立，经过认证的 hello/checkpoint 可跳过授权前或已清理前缀。缓存缺口后保留本次会话的后续合法事件，用 relayGapUntil 提示可能缺失；可回到 LAN 补收仍在短期发送队列中的消息。LAN 取得已存在或已删除的中转事件只推进游标、不重复显示或响铃。新会话清除旧缺口，不补收以前会话内容。

电脑临时队列上限 24 小时/1000 条，按入库时间和连续序号前缀清理。超过上限的中转待发记录（含尚未完成分片）被跳过，之后的新通知继续发送；不提供长期历史保留或补齐承诺。

LAN 地址可以在手机电脑卡片单独更新，仅修改该 serverId 的 baseUrl；原证书指纹、LAN token、设备身份、远程密钥和两种游标均保留。新地址仍通过原 DER 证书指纹验证与流中的 serverId 校验。远程中转地址独立保存，不随 LAN 地址变更。无效、本机或非 HTTPS 地址不能作为此更新入口的目标。

PublishedSequence 表示中转接收，不表示手机保存。“中转已连接”不表示电脑在线或历史完整。电脑需运行并开启中转，手机后台仍受 Doze/厂商限制。关闭中转等待发送循环退出，再开启继续待发事件。

撤销停止该设备后续发布，其他手机密钥不受影响；已经交给公共缓存的旧密文无法追回。中转 HTTP 403 不能视为电脑撤销的证明。公共服务可见 IP、随机 topic、事件 ID、大小和时间，不能读取通知内容。不提供抗服务拒绝、付费 topic ACL 或长期通知密钥前向保密；topic 泄露仍可能带来垃圾流量，但未通过 AEAD 的内容不会显示。

## 限制与验证

2026-10-01 核对 [官方发布 API](https://docs.ntfy.sh/publish/)：单条 4096 bytes、ntfy.sh 默认免费每日 250 条、默认缓存 12 小时；每手机、每分片和重试均占用额度。实际规则未来可变。订阅与恢复见 [官方订阅 API](https://docs.ntfy.sh/subscribe/api/)。自定义服务支持 HTTPS 根地址与匿名 topic，不配置服务器账号/token。

`tests/fixtures/ntfy-v1.json` 和 `ntfy-pair-v1.json` 是公开虚构数据和测试密钥，验证 C#→Kotlin 加密/ECDH/SAS 互通，绝不能用于真实授权。实机验收见 [清单](ntfy-device-validation.md)。
