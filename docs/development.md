# 开发、构建与验证

[返回项目介绍](../README.md) · [安装教程](installation.md) · [贡献指南](../CONTRIBUTING.md)

以下命令从仓库根目录的 PowerShell 运行，Android Gradle 命令另注明工作目录。终端路径和签名材料属于开发环境，不是普通用户安装的前提。

## Windows

需要 .NET 10 SDK。可使用系统 SDK，或准备经过微软官方 SHA512 校验的工作区 SDK：

    ./scripts/bootstrap_dotnet.ps1
    ./scripts/build_windows.ps1 -BuildOnly
    ./scripts/build_windows.ps1

默认自包含发布目录为 src/Bridge.Windows/bin/publish/win-x64，用户无需另装 .NET Runtime。

**直接运行 exe 可以检查界面和局域网服务，获取系统通知仍需正确的应用包身份和授权。** 安装包与签名步骤见 [Windows 打包说明](../packaging/windows/README.md)。MSIX 需要受目标机器信任且与 Publisher 匹配的签名；未签名开发包不能直接视为可安装成品。

## Android

需要 JDK 21、Android SDK platforms;android-36 和 build-tools;36.0.0。Android 工程独立于 Flutter：

    ./scripts/build_android.ps1

也可直接验证：

    cd src/android
    ./gradlew.bat testDebugUnitTest lintDebug assembleDebug

Android Studio 直接打开 src/android。本机路径通过未跟踪的 local.properties 或 ANDROID_HOME 配置。debug APK 用于开发验证；release APK 使用固定的独立发布密钥，私钥与凭据不进入仓库。

首次在自己的发布环境创建密钥：`./scripts/New-AndroidSigningKey.ps1`，已有密钥不会被替换。正式构建：`./scripts/build_android.ps1 -Configuration release`。默认从 `%LOCALAPPDATA%/Win2Mobile/Signing/android-release.signing.json` 加载当前用户 DPAPI 保护的配置，也可指定 `-SigningConfiguration`。后续升级必须保留并复用同一发布密钥；调试签名和正式签名不能相互覆盖。

## 从旧版迁移

v3 使用新的配对协议，旧版 IP/topic 配置与订阅不会自动迁移，两端更新后需重新扫码。Android 保留原 applicationId；覆盖安装要求签名与旧 APK 一致。若系统提示签名冲突，请先保留所需旧数据，再由用户卸载旧 App 后安装开发 APK。新版历史使用独立数据库，不导入 Flutter 旧历史。Windows 新包使用独立包身份；请退出旧代理，避免两套程序同时采集。

## 后端集成测试

    ./scripts/test_backend.ps1
    ./scripts/test_windows.ps1
    ./scripts/test_ntfy.ps1

后端 runner 使用临时 SQLite 数据库与真实回环 HTTPS/WSS，验证并发幂等、重启恢复、配对批准/拒绝/过期、鉴权、分页、确认游标、历史实时衔接、重连、撤销与停机取消。桌面状态 runner 验证启动基线、保存失败重试及暂停/权限恢复边界。失败返回非零退出码；这三个 console runner 通过上述脚本执行，dotnet test 不执行它们。

使用上述新版构建、测试和完整 MSIX 打包入口。桌面代理运行在用户会话中。

## 文档与发布维护

发布前核验 README 的版本、Release 附件、系统要求和安装指引；将已经交付的用户变化从 CHANGELOG 的「未发布」移到实际版本。发布记录应区分单测、构建、签名检查、安装与真实设备接收，不以其中一项代替其余结果。

MIT 许可证见 [LICENSE](../LICENSE)。隐私处理方式见 [PRIVACY.md](../PRIVACY.md)。修改数据流向、保留或权限时同步更新隐私说明；协议分别见 [LAN v1](protocol-v1.md) 和 [ntfy v1](protocol-ntfy-v1.md)。

GitHub Actions 生成的 Windows MSIX 为未签名开发包，Android 为 debug APK；它们不等同于正式 Release 安装包。签名私钥、DPAPI 配置、凭据及本地证书材料不应提交到仓库。旧实现可在 Git 提交 `253534b` 查看。
