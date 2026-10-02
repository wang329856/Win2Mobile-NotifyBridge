# 安全与隐私审查修复（3.1.2）

对应 2026-10-02 审查确认的三项 P2 内容残留问题。保留 LAN/ntfy 协议 v1、Room v4、电脑身份、设备授权和两套接收游标。

## 修复

1. **Windows 数据库残留**：每个 Store 连接在删除前启用 `secure_delete=ON`；过期及 1000 条上限淘汰提交后执行 `wal_checkpoint(TRUNCATE)`，检查返回的 busy 字段。外部读取阻塞时保留待清理状态，下一次清理重试；桌面服务状态显示等待原因，checkpoint 本身不等待锁，避免阻塞 UI。禁用连接池，关闭 Store 时释放实际连接。首次打开旧数据库执行 VACUUM 和 WAL 截断，成功后记录清理版本；失败不会标记迁移成功。启动也重试 WAL 清理，覆盖中断后重开场景。
2. **Android 系统通知副本**：单条删除按相同 serverId:eventId 标签撤回，清空和新会话只撤回消息通知，移除电脑和重新配对只撤回该电脑的消息通知。前台状态通知 ID 1 保留。全应用共享的 Mutex 串行协调保存/显示与删除/清空，网络 ACK 在锁外执行；删除一旦开始，数据库修改和通知撤回一起完成，即使 ViewModel 此时被取消。启动时撤回没有对应本地消息的旧版本残留通知。撤销只恢复本地消息及删除标记，不重新弹出或发声。
3. **Windows 详情残留**：选中项失效时恢复占位文字并禁用复制。详情文本框禁用撤销，避免旧正文藏在文本撤销记录中。过期和超量淘汰都走相同清除逻辑。

SQLite 机制依据：[安全删除](https://www.sqlite.org/pragma.html#pragma_secure_delete)、[WAL checkpoint 返回值与阻塞语义](https://www.sqlite.org/pragma.html#pragma_wal_checkpoint)。Android 撤回依据：[NotificationManager.cancel](https://developer.android.com/reference/android/app/NotificationManager#cancel(java.lang.String,%20int))。

## 验证

- `scripts/test_backend.ps1`：通过。新增真实 SQLite 文件测试覆盖过期、容量淘汰、旧残留整理、busy checkpoint 重试，并检查 DB/WAL 字节及授权/游标保持。
- `scripts/test_windows.ps1`：通过。实际 STA WPF ListBox/TextBox/Button 验证过期及容量淘汰后正文、复制和撤销状态同步清除；原通知基线、暂停恢复和分页测试通过。
- `scripts/test_ntfy.ps1`：通过。配对、加密、代理、撤销、限额恢复和设备隔离回归通过。
- Android Release：79 项单测，0 失败／错误／跳过；Lint 0 错误、17 条警告；构建及原发布签名验证通过。新增 9 项协调器测试覆盖删除与保存交错、ACK 不阻塞删除、清空、电脑隔离、静默撤销、取消、旧通知整理及凭据删除保存失败；原 DAO 去重与撤销测试通过。
- Windows 自包含发布和 MSIX 打包通过；原证书签名及 DigiCert 可信时间戳验证通过，0 警告、0 错误。

本次没有安装新补丁到真实手机，也没有把协调器的模拟通知接口测试称为 NotificationManager 或锁屏实机验证。

独立只读复核提出的凭据持久化失败路径已修复：Room 移除与系统通知撤回先完成，再清理凭据；清理失败反馈给用户，并提示在电脑端撤销原授权。对应失败测试通过。

## 边界

数据库测试只检查当前 DB/WAL 的实际字节，不宣称擦除文件系统旧快照、备份或 SSD 底层残留。外部连接持有旧读取事务时，WAL 截断必须等读取结束；应用显示待清理状态并重试。保留中的通知仍按原设计明文存于本地数据库。

Android 单测覆盖串行协调器与通知撤回接口，不能替代真实手机系统通知栏、锁屏和 ROM 的验证；其他应用已经读取或保存的通知副本无法追回。审查中的匿名健康元数据、白名单模式和供应链固定等加固建议未被列为已确认漏洞，此次没有改变相关产品语义。

双端补丁安装包使用原有发布签名。Windows 包继续加入可信时间戳；首次在新电脑安装仍须信任现有自签名证书。已安装的旧正式版本需要安装 3.1.2，才会执行这些修复。
