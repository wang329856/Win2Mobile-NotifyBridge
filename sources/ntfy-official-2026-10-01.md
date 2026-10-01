# ntfy 官方接口核对

日期：2026-10-01。Parallel API 凭据未配置，使用可用 web 工具访问官方文档。

- [发布](https://docs.ntfy.sh/publish/)：POST/PUT 发布随机 topic；单条默认 4096 bytes，超过大小会作为附件；默认缓存 12 小时，Cache:no 会失去断线补发；ntfy.sh 免费默认每日 250 条，存在请求速率限制。
- [订阅](https://docs.ntfy.sh/subscribe/api/)：topic/ws 提供 open/message/keepalive JSON；since 可使用消息 ID、时间或 all。缓存不是永久历史，重复读取整份缓存会增加带宽，客户端应优先按最后消息 ID 增量恢复。
- [官方服务端路由规则](https://github.com/binwiederhier/ntfy/blob/main/server/server.go)：topic 与 WSS 路由仅接受 1–64 个字母、数字、下划线或连字符。2026-10-01 使用官方 raw 源码核对；此前应用生成 68 字符 topic 的错误已修复为总长 64，并实测公共 WSS 返回 open。

以上为实施笔记，非服务可用性或未来额度保证。自动验证使用本地虚构数据，未向公共 topic 发布用户真实通知。
