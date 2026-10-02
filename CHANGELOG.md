# 当前版本说明

**[Win2Mobile 3.1.2](https://github.com/wang329856/Win2Mobile-NotifyBridge/releases/tag/v3.1.2)** · 2026-10-02

Windows 文件 / MSIX 版本为 3.1.2.0，Android 版本为 3.1.2。首次使用请直接查看 [安装教程](docs/installation.md)。

本版完善通知内容清理：

- Windows 过期或超量通知清理时处理当前数据库及 WAL 的正文残留；被其他读取阻塞时提示并重试。
- Android 删除、清空、开始新一轮或移除电脑时撤回对应系统消息通知，保留后台接收状态通知。
- Windows 选中消息失效后清空详情、禁用复制，并清理详情文本的撤销记录。

Windows 安装包包含可信时间戳，首次安装仍需信任附带的自签名证书。数据处理和清理边界见 [隐私说明](PRIVACY.md)。
