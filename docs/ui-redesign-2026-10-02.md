# Win2Mobile 3.1 UI 与交互改版验收

后续 3.1.1 已统一安卓图标、补充通知栏来源和竖屏扫码，并完成两端正式构建；最新产物与验证范围见 [3.1.1 打包记录](releases/v3.1.1.md)。以下保留 3.1.0 的界面改版和实机验证记录。

记录日期：2026-10-02。Windows 保留 WPF，Android 保留 Kotlin / Compose、Android 8 最低版本，网络协议仍为 v1。

## 已实现

- 两端统一蓝紫品牌、青绿正常状态、橙色提醒与红色危险操作，支持系统／浅色／深色主题。桌面有键盘焦点边框、系统高对比配色和动画关闭判断；Compose 使用系统字体、48dp 触控目标和系统动画时长比例。
- 桌面改为概览、设备、通知、规则、设置侧栏；窄窗口收缩导航并纵向排列卡片。概览可直接暂停采集、连接手机、发送测试。授权手机数量不会显示为在线数量。
- 配对面板集中 LAN / 跨网二维码、电脑名称、倒计时、六位校验码与请求卡片；直接批准／拒绝，任意页面提醒待批准请求。到期后仅在面板可见且没有待批准请求时自动更新。
- 通知短期队列使用搜索列表与可复制详情；保留 24 小时／1000 条语义。桌面启动按每页最多 200 条读取完整队列，已针对接口限额补充真实 SQLite 回归。
- 来源规则支持搜索与批量开关；简单开关即时保存，地址与代理校验后保存。外观、登录启动、跨网配置、权限和诊断集中于设置。
- Android 首页直接暂停／继续、空状态扫码；消息按日期分组、自动分页、应用过滤、旧消息阅读时的新消息提示。详情使用可选文字和一键复制的底部面板，宽屏采用侧栏与列表／详情布局。
- 删除提供滑动与无障碍操作入口、短时撤销；消息与删除标记一起恢复，清空、新会话、重新配对或电脑移除使旧撤销失效。接收进度不会回退。
- 页面、查询、过滤、分页数量与滚动位置支持恢复；配对在 ViewModel 中执行，旋转不会主动取消任务。系统通知携带电脑和事件标识定位详情，已删除时明确提示。
- 每台电脑独立接收，支持 AUTO / LAN / RELAY。AUTO 使用最长 3 秒的证书固定与身份、鉴权检查；探测只读，不保存、ACK 或推进游标。中转稳定至少 30 秒、间隔 2 秒连续两次直连检查成功后切回。
- 连接任务替换前取消并等待旧任务结束；网络变化合并，使用中转时每 30 秒重新检查 LAN。LAN 授权拒绝、证书／身份异常停止该电脑接收；中转拒绝作为中转故障。
- 暂停／继续保留列表、凭据与基线；只有确认新会话才清空并重建基线。新增电脑只初始化自身。地址扫码更新必须匹配原电脑身份和证书。

设计参考：[Material 3](https://developer.android.com/develop/ui/compose/designsystems/material3)、[Windows 动效原则](https://learn.microsoft.com/en-us/windows/apps/design/signature-experiences/motion)、[Compose 无障碍默认值](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)。本版使用当前稳定 Material 3 组件与轻量弹性页面过渡。

## 构建与自动验证

| 项目 | 结果与证据 |
|---|---|
| 后端回归 | 通过；HTTPS/WSS、配对、ACK、重连、撤销、SQLite 并发、24 小时／1000 条队列；`.artifacts/ui-backend-tests.log` |
| Windows 回归 | 通过；采集基线、暂停／权限边界、失败重试、真实 SQLite 中 1000 条通知分批读取和顺序／唯一性；`.artifacts/ui-windows-tests.log` |
| ntfy 回归 | 通过；CONNECT 代理、远程配对、ECDH 校验码、独立设备密钥、分片、429 重试、撤销及发布进度；`.artifacts/ui-ntfy-tests.log` |
| Android 单测 | 67 项，0 失败、0 错误、0 跳过；包含协调器、旧任务清理、多电脑独立取消、可信只读探测、撤销与会话边界 |
| Android lint | 0 错误、17 条警告；报告在 `src/android/app/build/reports/lint-results-debug.html`，包括 SDK / 库更新与调试预览相关建议 |
| 构建 | Windows 自包含 x64 发布、MSIX 打包／开发签名验证通过；Android `assembleDebug` 通过 |
| 真实 Room v3→v4 | 在 V2352A / Android 16 上打开真实 v3 SQLite 数据库，通过 Room v4 迁移及 schema 校验；设备、消息、删除标记、凭据元数据、两套游标保留，默认 AUTO；撤销／清空／新会话检查通过 |
| 已安装手机数据 | 升级前后备份核对：原设备、原消息与删除记录保留；两套游标未回退；加密凭据文件逐字节一致。验证期间已有新增接收与用户设置变化，未要求运行中状态保持不变 |
| 界面检查 | WPF 生产页面的浅／深色及 800px 窄窗口截图；Android 生产 Compose 页面在隔离 Room 数据下的实机截图，含 1.5 倍字体与宽屏布局 |

测试命令：`scripts/test_backend.ps1`、`scripts/test_windows.ps1`、`scripts/test_ntfy.ps1`、`scripts/build_windows.ps1`、`scripts/build_android.ps1`。本机 Android 使用已有离线依赖和 `.artifacts/android-local-repository.init.gradle`；这是本机仓库配置，不是发布依赖。

## 截图

截图使用示例消息和无效配对 QR，不包含用户凭据。Windows 通过显式 `--design-preview=<目录>` 渲染真实页面，跳过监听器、服务与配置写入。Android 调试预览使用隔离内存 Room、禁止实际配对和接收；宽屏通过预览内局部密度模拟，不修改手机全局显示设置。

| 页面 | Windows | Android |
|---|---|---|
| 首页浅色 | [概览](images/ui-redesign/windows-overview-light.png) | [通知](images/ui-redesign/android-notifications-light.png) |
| 首页深色 | [概览](images/ui-redesign/windows-overview-dark.png) | [通知](images/ui-redesign/android-notifications-dark.png) |
| 设备 | [设备](images/ui-redesign/windows-devices-light.png) | [电脑](images/ui-redesign/android-computers-light.png) |
| 消息详情 | [通知](images/ui-redesign/windows-notifications-light.png) | [底部详情](images/ui-redesign/android-detail-light.png) |
| 规则 | [来源规则](images/ui-redesign/windows-rules-light.png) | — |
| 设置 | [浅色](images/ui-redesign/windows-settings-light.png)、[深色](images/ui-redesign/windows-settings-dark.png) | [浅色](images/ui-redesign/android-settings-light.png)、[深色](images/ui-redesign/android-settings-dark.png) |
| 配对／空状态 | [配对面板](images/ui-redesign/windows-pairing-light.png) | [首次连接](images/ui-redesign/android-empty-light.png) |
| 自适应 | [窄窗口](images/ui-redesign/windows-overview-compact.png) | [宽屏详情](images/ui-redesign/android-wide-detail.png)、[大字体](images/ui-redesign/android-large-font.png) |

## 交付与实机边界

- Windows 最新包：`dist/windows/local-install/Win2Mobile-3.1.0.2-x64.msix`，签名在本机验证为 Valid；同目录附公钥证书和安装脚本。自包含发布目录：`src/Bridge.Windows/bin/publish/win-x64`。
- Android：`dist/android/Win2Mobile-3.1.0-debug.apk`，开发签名；升级保留应用数据。SHA-256 清单：`dist/SHA256SUMS.txt`。未创建 Git 提交或远程发布。
- 原 Windows 3.1.0.0 实机启动暴露单次队列读取超限，已修复为 200 条分批读取。用户退出旧版后已覆盖安装并启动 3.1.0.1，47721 端口正常监听，HTTPS 健康接口返回协议 v1；证据为 `.artifacts/ui-windows-installed.log` 与 `.artifacts/ui-windows-health.log`。本机自签名健康检查仅此请求跳过证书链验证，不修改系统信任；生产 Android 探测仍校验证书固定和电脑身份。
- 最新 Android 更新被 vivo 安装拦截；最终版滑动删除／撤销的完整实机复测需安装完成后执行。此前隔离页面的暂停／继续按钮验证列表数量保持不变；DAO 撤销与失效边界已通过单测及真实 Room 验证。
- 真实 Wi-Fi／移动数据自动回退与回切、长时间息屏／Doze、配对过程旋转、系统通知实际点击定位、TalkBack 和不同 Windows DPI／键盘操作仍需逐项验收。协调器测试、截图和编译不能替代这些结果。
- 本次未修改系统权限、证书信任、防火墙、手机全局字体或网络设置。备份仅留在被 Git 忽略的 `.artifacts` 中，不随截图和安装包交付。

## 3.1.0.2 图标更新

根据实机界面反馈，将默认窗口图标及左上角箭头改为统一的电脑／手机／通知品牌图标，同步覆盖 EXE、标题栏、任务栏、系统托盘和 MSIX 图标。蓝紫渐变与青绿通知标记保持两端状态配色一致。源生成脚本：`packaging/windows/New-BrandIcon.ps1`；ICO 包含 16、24、32、48、64、128、256px 图层，PNG 与安装包资源来自同一几何图形。

已通过 Windows 自包含构建、签名验证、ICO 加载／256px PNG 图层检查和 EXE 内嵌图标提取；真实 WPF 浅／深色、窄窗口预览退出码为 0，截图已更新。本次仅修改桌面图标资源及引用，Android 安装包不变。用户退出旧实例后已覆盖安装并启动 3.1.0.2，健康接口返回协议 v1；证据为 `.artifacts/ui-brand-installed.log`。
