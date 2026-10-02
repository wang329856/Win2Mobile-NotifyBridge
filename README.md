<div align="center">
  <img src="src/Bridge.Windows/Assets/BrandLogo.png" width="96" alt="Win2Mobile 图标" />
  <h1>Win2Mobile</h1>
  <p><strong>通知，随你而行。</strong></p>
  <p>把 Windows 系统通知，安全地送到你的 Android 手机。</p>
  <p>
    <a href="https://github.com/wang329856/Win2Mobile-NotifyBridge/releases/latest"><img src="https://img.shields.io/github/v/release/wang329856/Win2Mobile-NotifyBridge?label=release" alt="最新正式版" /></a>
    <a href="https://github.com/wang329856/Win2Mobile-NotifyBridge/actions/workflows/build.yml"><img src="https://github.com/wang329856/Win2Mobile-NotifyBridge/actions/workflows/build.yml/badge.svg" alt="构建与验证状态" /></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-green.svg" alt="MIT 许可证" /></a>
    <img src="https://img.shields.io/badge/platform-Windows%20%7C%20Android-6750A4.svg" alt="Windows 与 Android" />
  </p>
  <p>
    <a href="#下载与安装">下载与安装</a> ·
    <a href="#第一次连接">第一次连接</a> ·
    <a href="docs/relay.md">跨网使用</a> ·
    <a href="PRIVACY.md">隐私说明</a> ·
    <a href="https://github.com/wang329856/Win2Mobile-NotifyBridge/issues/new/choose">问题反馈</a>
  </p>
</div>

---

离开电脑，也能在手机上查看电脑的新消息、邮件与日程提醒。Win2Mobile 读取 Windows 系统通知，经你批准后转发到 Android 手机。

**在同一局域网内直接连接，不依赖公共中转，也不需要互联网。离开局域网后，可主动开启加密跨网推送；默认使用第三方 `ntfy.sh`，受服务可用性和限额影响，也支持兼容的自建 ntfy。**

## 能做什么

| 功能 | 使用体验 |
| --- | --- |
| 扫码连接 | 手机扫码，电脑核对并批准；只有获授权的手机才能接收 |
| 加密传输 | 局域网使用 HTTPS / WSS；跨网通知在电脑加密、手机解密 |
| 自动切换 | 优先连接可信局域网，无法直连时使用已授权的中转 |
| 暂停与继续 | 手机暂停时保留本次消息和进度，继续后补收仍在电脑队列中的通知 |
| 消息管理 | 搜索、按应用筛选、滑动删除与短时撤销；通知栏显示来源应用 |
| 电脑常驻 | 系统托盘、来源过滤、设备撤销和可选登录启动 |

## 界面预览

<table>
  <tr>
    <th width="72%">Windows</th>
    <th width="28%">Android</th>
  </tr>
  <tr>
    <td valign="top"><img src="docs/images/ui-redesign/windows-overview-light.png" width="800" alt="Windows 概览：连接手机、发送测试通知与最近通知" /></td>
    <td valign="top"><img src="docs/images/ui-redesign/android-notifications-light.png" width="280" alt="Android 消息页：暂停接收、搜索与通知卡片" /></td>
  </tr>
</table>

截图使用示例消息，Windows 图为设计预览。

## 下载与安装

当前最新版为 **[v3.1.2](https://github.com/wang329856/Win2Mobile-NotifyBridge/releases/tag/v3.1.2)**，无需自行编译。

| 平台 | 下载 | 系统要求 |
| --- | --- | --- |
| Windows | **[安装套件 ZIP](https://github.com/wang329856/Win2Mobile-NotifyBridge/releases/download/v3.1.2/Win2Mobile-Setup-x64.zip)** | x64，Windows 10 build 19041 或更新；自带 .NET 运行时 |
| Android | **[安装 APK](https://github.com/wang329856/Win2Mobile-NotifyBridge/releases/download/v3.1.2/Win2Mobile-3.1.2-release.apk)** | Android 8.0 或更新 |

Windows 套件包含 MSIX、公钥证书与安装脚本。首次安装需核验并信任附带的自签名证书，再安装 MSIX，从开始菜单启动。手机打开 APK，按系统提示完成安装。

**详细步骤见 [首次安装教程](docs/installation.md)。** Release 页面上的 Source code 是源码，请下载上表中的安装附件。

## 第一次连接

1. **准备网络**：手机与电脑连接到可互通的同一局域网。
2. **授权电脑**：打开 Win2Mobile，点击「授权通知访问」，允许 Windows 系统提示。
3. **手机扫码**：电脑打开「连接手机」，选择手机可访问的网卡地址；手机点击「扫码连接电脑」，扫描二维码并按需授予相机权限。
4. **电脑批准**：核对设备名称并批准请求；二维码 120 秒有效，过期后刷新。
5. **发送测试**：电脑点击「发送测试通知」，确认手机收到，再让邮件、日程等应用产生一条新的 Windows 系统通知。

电脑需保持运行。局域网连接需要 Windows 私人网络允许 **TCP 47721** 入站；访客 Wi-Fi、AP 隔离或 VPN 可能阻断互访。

## 平时怎么用

- **选择来源**：在电脑「规则」中排除不想转发的应用。
- **暂停手机接收**：点击暂停，消息和进度保留；点击继续后，补收本次会话中仍在电脑队列里的通知。
- **开始新一轮**：清空手机本次消息，从新的接收基线开始。
- **删除消息**：滑动删除，可短时撤销；清空或移除电脑会撤回对应系统消息通知。
- **让电脑常驻**：关闭窗口后可从托盘打开；登录启动可在电脑设置中开启。
- **停用设备**：在电脑设备页撤销手机授权。若要停止全部中转发布，关闭电脑跨网开关。

电脑只转发新的系统 Toast，不读取应用自绘弹窗或聊天记录。电脑暂停采集期间产生的通知不会补发；短期发送队列最多保留 24 小时 / 1000 条。手机消息保留至自行删除或清空。

## 离开局域网后接收

在电脑「设置」→「跨网络推送」中启用中转，再为手机配对领取中转密钥。手机保持「自动」模式，即可在直连不可用时尝试跨网；也可以完全远程配对。

| 方式 | 服务依赖 |
| --- | --- |
| 局域网直连 | 不依赖公共中转，只需两端互相可达 |
| 默认跨网 | 使用 `https://ntfy.sh`；依赖两端互联网、中转可用性、额度和缓存 |
| 自建跨网 | 在电脑填入兼容的 ntfy HTTPS 根地址；由你维护服务和网络 |

跨网通知内容使用独立密钥加密，中转仍可见 IP、topic、消息大小和时间等元数据。长通知分片、多部手机与重试会消耗额度；限流或缓存过期可能造成延迟、缺口。Android 息屏与厂商后台限制也可能影响接收，**「中转已连接」不代表电脑在线或消息已送达**。

开启方法、远程配对与自建要求见 **[跨网使用教程](docs/relay.md)**；公共限额以 [ntfy 官方说明](https://docs.ntfy.sh/publish/#limitations) 为准。

## 遇到问题

先查看 [安装教程中的常见问题](docs/installation.md#常见问题)。仍无法解决时，通过 [反馈模板](https://github.com/wang329856/Win2Mobile-NotifyBridge/issues/new/choose) 提供两端版本、手机型号、连接模式和复现步骤。截图先遮盖通知内容、二维码和凭据。

数据如何保存与删除见 [隐私说明](PRIVACY.md)，当前版本变化见 [版本说明](CHANGELOG.md)。项目使用 [MIT 许可证](LICENSE)。
