# Win2Mobile NotifyBridge

将 Windows 系统通知转发到同一局域网内的 Android 手机。v3 使用 C# 桌面程序和 Kotlin Android App，以扫码配对、设备授权、加密连接和断线补发为核心。

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

电脑窗口提供权限状态、暂停、配对批准/拒绝、设备撤销、近期通知详情、来源过滤与登录启动。手机提供通知历史、设备管理与后台状态诊断。

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

后端 runner 使用临时 SQLite 数据库与真实回环 HTTPS/WSS，验证并发幂等、重启恢复、配对批准/拒绝/过期、鉴权、分页、确认游标、历史实时衔接、重连、撤销与停机取消。桌面状态 runner 验证启动基线、保存失败重试及暂停/权限恢复边界。失败返回非零退出码；这两个 console runner 通过上述脚本执行，dotnet test 不执行它们。

使用上述新版构建、测试和完整 MSIX 打包入口。桌面代理运行在用户会话中。

## 项目结构

    src/
      Bridge.Core/              # SQLite 发件箱、设备与事件模型
      Bridge.Transport.Lan/     # TLS、配对、认证与事件流
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

采集与传输已分离，局域网稳定后可增加真实 ntfy 发布适配器或云中继；当前开发版不提供公网中继或推送服务。

## License

MIT
