# 公开发布文档参考核验

日期：2026-10-02。本次参考公开 README 的组织方式，不复制项目宣传文案、性能数据或测试结论。Parallel Web 所需 API key 未配置，使用内置浏览工具及 GitHub 公开 API；以下保留实际采用的来源与核验结果。

## 项目介绍风格

- [LocalSend](https://github.com/localsend/localsend)：一句话定位、导航、截图、平台下载、工作方式与排障入口；借鉴层级与用户 / 开发者信息分流。
- [ntfy](https://github.com/binwiederhier/ntfy)：项目定位、使用与服务说明；作为本项目中转依赖的原始项目。
- [ShareX](https://github.com/ShareX/ShareX)：功能、下载与截图的开源桌面应用介绍结构。
- [GitHub Choose a License / MIT](https://choosealicense.com/licenses/mit/)：许可证参考；项目原 README 已声明 MIT，用户也明确选择 MIT。

## 服务说明的来源

- [ntfy 发布与限制](https://docs.ntfy.sh/publish/#limitations)：存在请求额度、消息大小与缓存限制。新用户文档链接现行规则，不将某个免费额度写成永久承诺。
- [ntfy 安装](https://docs.ntfy.sh/install/) 与 [配置](https://docs.ntfy.sh/config/)：自建服务的部署入口；本项目接入条件另以自己的代码校验。
- [ntfy 隐私](https://docs.ntfy.sh/privacy/)：第三方运营者的数据政策入口。
- [微软 MSIX 证书信任](https://learn.microsoft.com/en-us/windows/msix/package/create-certificate-package-signing)：保留已有安装记录中的 Trusted People 证书信任步骤。

## Release 附件核验

通过公开 API `https://api.github.com/repos/wang329856/Win2Mobile-NotifyBridge/releases/tags/v3.1.1` 核验，正式版非预发布，发布于 2026-10-02。实际附件：

- SHA256SUMS.txt
- Win2Mobile-3.1.1-release.apk
- Win2Mobile-LocalDevelopment.cer
- Win2Mobile-Setup-x64.msix
- Win2Mobile-Setup-x64.zip

README 与教程使用上述已存在的附件名，不使用本机残留的便携 ZIP。

整理期间仓库新增提交 d77b5b3 并发布正式版 v3.1.2。再次通过 releases/latest API 核验，当前最新正式版为 v3.1.2，附件结构相同、Android 文件改为 Win2Mobile-3.1.2-release.apk。新文档下载入口、教程和更新日志已同步到 3.1.2；3.1.1 保留为历史记录。公开仓库 API 与本地 origin/HEAD 确认默认分支为 master，反馈模板的网页文档链接使用 master。

## 本地实现依据

- `DesktopSettings.cs`：跨网默认关闭，默认服务地址为 https://ntfy.sh。
- `NtfyProtocol.cs`：HTTPS 根地址校验、独立 AES-256-GCM 加密分片。
- `NtfyRegistry.cs`：更换已有绑定服务须撤销设备后重新配对。
- `NtfyClient.kt`：匿名 POST / WSS、标准 CA 与主机名校验，无服务账号 / token 配置。
- `MainWindow.xaml.cs`、`BridgeStore.cs`：无授权设备不采集入库，短期队列 24 小时 / 1000 条。
- `BridgeDatabase.kt`、`SecureTokenStore.kt`、AndroidManifest.xml：本地消息、删除标记、Keystore 凭据保护与权限 / 备份设置。
- `docs/releases/v3.1.1.md`：实际发布签名、升级边界、时间戳更新与尚未完成的实机验证。

文档将下载包与未发布源码改进分开，不承诺磁盘不可恢复擦除、手机息屏必达或公共中转 SLA。
