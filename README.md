# Win2Mobile NotifyBridge

将 Windows 系统通知转发到已授权的 Android 手机。v3 使用 C# 桌面程序和 Kotlin Android App，支持局域网直连，以及可选的 ntfy 跨网络端到端加密中转，无需租用服务器。跨网络功能为当前源码新增，下面链接的原预发布安装包尚不包含此功能。

当前代码为 v3 重构版本，桌面端与 Android 端已完成本机安装和测试。旧版源码、构建缓存与过时入口已清理；旧实现可从 Git 历史提交 `253534b` 恢复。构建与验证记录见 [开发记录](docs/development-progress.md)。当前安装包仍使用开发签名，正式发布签名尚未配置。

## 下载安装

[v3.0.0 预发布版](https://github.com/wang329856/Win2Mobile-NotifyBridge/releases/tag/v3.0.0-preview.1) 提供 [Windows x64 安装套件](https://github.com/wang329856/Win2Mobile-NotifyBridge/releases/download/v3.0.0-preview.1/Win2Mobile-3.0.0-windows-x64.zip) 和 [Android APK](https://github.com/wang329856/Win2Mobile-NotifyBridge/releases/download/v3.0.0-preview.1/Win2Mobile-3.0.0-debug.apk)。桌面套件附公钥证书和安装说明；首次安装需信任开发证书。完整安装、升级与已知限制见 [发布说明](docs/releases/v3.0.0-preview.1.md)。

## 使用流程

1. 在 Windows 安装带应用包身份的 Win2Mobile MSIX，从开始菜单启动。
2. 点击“授权通知访问”，允许系统通知监听。
3. 在 Android 安装 Win2Mobile，允许通知，在“电脑”页选择“扫码连接电脑”并扫描电脑显示的二维码。
4. 在电脑核对设备名称，批准手机配对请求。
5. 在电脑点击“发送测试通知”，确认手机收到，再使用正常的系统通知转发。

二维码 120 秒有效，绑定电脑证书指纹。配对完成后使用设备凭据和 HTTPS/WSS。历史消息存入两端数据库，网络恢复后按游标补取；手机对事件 ID 去重，重放历史不成批响铃。

电脑窗口提供权限状态、暂停、配对批准/拒绝、设备撤销、短期发送队列、来源过滤与登录启动。手机提供本次接收消息列表、设备管理与后台状态诊断。

手机点击“开始本次接收”会清空旧列表，首次连接只接收之后的新通知；同一会话内断网重连、切换局域网/远程保留已收到的消息并补收断网期间的新消息。删除单条或清空列表会保留去重标记，补发不会恢复已删除的消息。没有已授权手机时电脑不采集入库，不向新设备传递授权前记录；临时发送队列最多保留 24 小时、1000 条，超期或超量自动清理，不提供长期归档。更新记录见 [接收会话与删除修复](docs/receive-session-update-2026-10-01.md)。

## 跨网络推送与完全远程配对

1. 使用当前源码构建并更新两端，在电脑“跨网络推送”页启用 ntfy，应用默认 `https://ntfy.sh`。
   若电脑直连中转失败，可在同页填写已有 HTTP 代理地址（例如本机代理 `http://127.0.0.1:7890`），只影响本应用。两端都需实际能访问所选中转服务。
2. 点击“生成远程配对二维码”。通过可信渠道将二维码或复制的配对信息交给自己的手机，在 120 秒内扫码或粘贴；两端只需分别能访问互联网，无需在同一局域网。
3. 核对手机显示的六位校验码与电脑“跨网络推送”页二维码旁的校验码字段，在同页批准请求；“配对手机”页也可查看待批准请求。
4. 手机切换移动数据，电脑发送测试通知；也可继续使用普通局域网二维码配对，开启中转后重新扫码即可领取通知密钥。

远程与局域网接收共用同一授权，但使用不同连接地址。首次远程配对保存的局域网 IP 可能是虚拟网卡地址或已变更；回到同一局域网后，在手机“电脑”卡片点“更新局域网地址”，填写电脑“配对手机”页当前显示的 HTTPS 地址，然后切换局域网接收。此操作保留原证书校验、授权、远程密钥和本次消息，无需重新配对。电脑扫描地址时优先列出实际网卡，已有手动选择仍会保留。

每部手机使用独立 topic 和 AES-256-GCM 密钥。通知内容在电脑加密、手机解密；远程授权回复通过临时 ECDH 密钥单独加密。免费公共中转可见 IP、topic、大小和时间，但不持有通知密钥。电脑开关默认关闭。无需安装 ntfy App、开放公网端口或另租服务器。

ntfy 免费服务存在限额与缓存时长，长通知会加密分片，每个分片和每部手机分别计入额度。限流后电脑在临时队列期限内重试。缓存过期后可切回 LAN 补收本次会话内仍在队列中的通知，超过电脑队列期限或数量上限的消息不再补发；中转连接不代表电脑在线或手机已保存。手机的 LAN 接收开关不会停止电脑发布，停止中转请关闭电脑开关。电脑撤销设备会停止后续发送，已经缓存的旧密文无法追回。规则与协议见 [ntfy 协议](docs/protocol-ntfy-v1.md)，实机验收见 [跨网络验收清单](docs/ntfy-device-validation.md)。

## 系统要求

- Windows 10 build 19041 或更新版本；推荐仍受支持的 Windows 11。
- Android 8.0（API 26）或更新版本；工程 targetSdk 35、compileSdk 36。
- 局域网中两端互相可达，Windows 私人网络允许 TCP 47721 入站。访客 Wi-Fi 或 AP 隔离可能阻止互访。
- 采集范围是进入 Windows 通知平台的 Toast，应用自绘聊天弹窗和聊天历史不属于首版范围。

Android 接收使用原生前台服务并显示持续状态通知。Doze 和手机厂商后台策略仍可能限制网络，应用提供诊断与设置入口；真实息屏行为需要在目标手机验证。Windows 启动以当前通知中心内容为基线，暂停期间不补发新通知。

## 开发与构建

### Windows

需要 .NET 10 SDK。可使用系统 SDK，或准备经过微软官方 SHA512 校验的工作区 SDK：

    ./scripts/bootstrap_dotnet.ps1
    ./scripts/build_windows.ps1 -BuildOnly
    ./scripts/build_windows.ps1

默认自包含发布目录为 src/Bridge.Windows/bin/publish/win-x64，用户无需另装 .NET Runtime。

**直接运行 exe 可以检查界面和局域网服务，获取系统通知仍需正确的应用包身份和授权。** 安装包与签名步骤见 [Windows 打包说明](packaging/windows/README.md)。MSIX 需要受目标机器信任且与 Publisher 匹配的签名；未签名开发包不能直接视为可安装成品。

### Android

需要 JDK 21、Android SDK platforms;android-36 和 build-tools;36.0.0。Android 工程独立于 Flutter：

    ./scripts/build_android.ps1

也可直接验证：

    cd src/android
    ./gradlew.bat testDebugUnitTest lintDebug assembleDebug

Android Studio 直接打开 src/android。本机路径通过未跟踪的 local.properties 或 ANDROID_HOME 配置。debug APK 用于开发验证；稳定发布需提供固定 release 签名，私钥与凭据不进入仓库。

### 从旧版迁移

v3 使用新的配对协议，旧版 IP/topic 配置与订阅不会自动迁移，两端更新后需重新扫码。Android 保留原 applicationId；覆盖安装要求签名与旧 APK 一致。若系统提示签名冲突，请先保留所需旧数据，再由用户卸载旧 App 后安装开发 APK。新版历史使用独立数据库，不导入 Flutter 旧历史。Windows 新包使用独立包身份；请退出旧代理，避免两套程序同时采集。

### 后端集成测试

    ./scripts/test_backend.ps1
    ./scripts/test_windows.ps1
    ./scripts/test_ntfy.ps1

后端 runner 使用临时 SQLite 数据库与真实回环 HTTPS/WSS，验证并发幂等、重启恢复、配对批准/拒绝/过期、鉴权、分页、确认游标、历史实时衔接、重连、撤销与停机取消。桌面状态 runner 验证启动基线、保存失败重试及暂停/权限恢复边界。失败返回非零退出码；这两个 console runner 通过上述脚本执行，dotnet test 不执行它们。

使用上述新版构建、测试和完整 MSIX 打包入口。桌面代理运行在用户会话中。

## 项目结构

    src/
      Bridge.Core/              # SQLite 发件箱、设备与事件模型
      Bridge.Transport.Lan/     # TLS、配对、认证与事件流
      Bridge.Transport.Ntfy/    # 独立设备密钥、加密分片与远程授权
      Bridge.Windows/           # WPF、托盘与官方通知监听
      android/                  # Kotlin、Compose、Room 与原生接收
    tests/Bridge.IntegrationTests/
    packaging/windows/
    scripts/
    docs/

协议见 [局域网协议 v1](docs/protocol-v1.md)，路线见 [重构评估](docs/refactor-assessment-2026-09-30.md)。
双设备安装、真实 Toast、断网补发和息屏验证见 [实机验收](docs/device-validation.md)。

Windows 数据使用当前用户 LocalApplicationData/Win2Mobile，包含数据库、设置和受 DPAPI 保护的服务私钥。Windows 只存设备 token 哈希，Android 由 Keystore 加密存储凭据；二维码和 token 不应写入日志。

## 后续扩展

当前源码提供 ntfy 加密中转和远程配对。后续可继续扩展后台唤醒、远程保存确认和更多平台；目前不承诺公共服务或 Android 息屏时必达。

## License

MIT
