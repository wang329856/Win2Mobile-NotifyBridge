# Win2Mobile

将 Windows 系统通知转发到已授权的 Android 手机。使用 C# 桌面程序和 Kotlin Android App，支持局域网直连，以及可选的 ntfy 跨网络端到端加密中转，无需租用服务器。

当前源码为 3.1.2，提供两端新版界面、统一品牌图标、竖屏扫码、通知栏应用来源、手机暂停／继续、滑动删除撤销及可信局域网优先的自动连接。改版截图与验证范围见 [UI 改版验收记录](docs/ui-redesign-2026-10-02.md)，正式构建及签名说明见 [3.1.2 打包记录](docs/releases/v3.1.2.md)。旧实现可从 Git 历史提交 `253534b` 恢复。Android 已配置独立 release 签名；Windows 沿用现有本地签名证书，首次在其他电脑安装需自行核验并信任证书。

Windows 仅提供具有系统通知采集权限的安装版，显示名称统一为 `Win2Mobile`。安装套件自带 .NET 运行时，无需另行安装 .NET。

3.1.2 修复数据库过期正文、Android 通知栏已删除消息和 Windows 失效详情的残留问题；修复方式及验证边界见 [隐私修复记录](docs/security-fixes-2026-10-02.md)。

## 下载安装

[v3.1.2 正式版](https://github.com/wang329856/Win2Mobile-NotifyBridge/releases/tag/v3.1.2) 提供 [Windows 安装套件](https://github.com/wang329856/Win2Mobile-NotifyBridge/releases/download/v3.1.2/Win2Mobile-Setup-x64.zip)、[Windows MSIX](https://github.com/wang329856/Win2Mobile-NotifyBridge/releases/download/v3.1.2/Win2Mobile-Setup-x64.msix) 和 [Android 正式 APK](https://github.com/wang329856/Win2Mobile-NotifyBridge/releases/download/v3.1.2/Win2Mobile-3.1.2-release.apk)。Windows 套件附公钥证书和安装说明，新电脑须核验并信任现有自签名证书。Android 正式签名无法覆盖旧 debug APK，卸载前应保留所需旧数据。完整安装、升级与验证范围见 [发布说明](docs/releases/v3.1.2.md)。

## 使用流程

1. 在 Windows 安装带应用包身份的 Win2Mobile MSIX，从开始菜单启动。
2. 点击“授权通知访问”，允许系统通知监听。
3. 在 Android 安装 Win2Mobile，在通知首页点击“扫码连接电脑”，扫描桌面“连接手机”面板的二维码。
4. 在电脑核对设备名称，批准手机配对请求。
5. 在电脑点击“发送测试通知”，确认手机收到，再使用正常的系统通知转发。

二维码 120 秒有效，绑定电脑证书指纹。配对完成后使用设备凭据和 HTTPS/WSS。历史消息存入两端数据库，网络恢复后按游标补取；手机对事件 ID 去重，重放历史不成批响铃。

电脑窗口提供权限状态、暂停、配对批准/拒绝、设备撤销、短期发送队列、来源过滤与登录启动。手机提供本次接收消息列表、设备管理与后台状态诊断。

手机首页一次点击即可暂停或继续：保留本次消息、凭据与进度，恢复后补收仍在电脑队列中的通知。只有明确确认“开始新一轮接收”才清空列表并重建基线，首次连接只接收之后的新通知。新增电脑只初始化它自身，其他电脑的消息保留。滑动删除可短时撤销；清空、新会话或移除电脑后，旧撤销操作失效。删除标记与接收进度防止重连后已删内容重新出现。没有已授权手机时电脑不采集入库，不向新设备传递授权前记录；短期发送队列最多保留 24 小时、1000 条，不提供长期归档。

## 跨网络推送与完全远程配对

1. 使用当前源码构建并更新两端，在电脑“设置”的“跨网络推送”中启用 ntfy，应用默认 `https://ntfy.sh`。
   若电脑直连中转失败，可在同页填写已有 HTTP 代理地址（例如本机代理 `http://127.0.0.1:7890`），只影响本应用。两端都需实际能访问所选中转服务。
2. 打开“连接手机”，选择“跨网络连接”，点击“启用跨网络并生成二维码”。通过可信渠道将二维码或复制的配对信息交给自己的手机，在 120 秒内扫码或粘贴。
3. 核对两端六位校验码，在电脑请求卡片直接点击批准；任意桌面页面都会显示待批准提醒。
4. 手机切换移动数据，电脑发送测试通知；也可继续使用普通局域网二维码配对，开启中转后重新扫码即可领取通知密钥。

每台电脑默认“自动”：有 Wi-Fi／以太网时最多用 3 秒检查已保存的可信地址，不可达则使用已有中转授权。中转稳定至少 30 秒且两次局域网检查成功后自动切回；可在电脑卡片详情选择“仅局域网”或“仅跨网络”。授权拒绝、证书与身份异常会明确停止该电脑的接收，要求核验。地址变化时可扫码更新或手动编辑，保留授权、密钥、消息与进度；扫码必须匹配原电脑身份和证书。

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

Android Studio 直接打开 src/android。本机路径通过未跟踪的 local.properties 或 ANDROID_HOME 配置。debug APK 用于开发验证；release APK 使用固定的独立发布密钥，私钥与凭据不进入仓库。

首次在自己的发布环境创建密钥：`./scripts/New-AndroidSigningKey.ps1`，已有密钥不会被替换。正式构建：`./scripts/build_android.ps1 -Configuration release`。默认从 `%LOCALAPPDATA%/Win2Mobile/Signing/android-release.signing.json` 加载当前用户 DPAPI 保护的配置，也可指定 `-SigningConfiguration`。后续升级必须保留并复用同一发布密钥；调试签名和正式签名不能相互覆盖。

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
