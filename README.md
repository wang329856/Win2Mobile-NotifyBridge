# Win2Mobile-NotifyBridge — Windows 通知转发到手机

将 Windows 10/11 的 Toast 通知实时转发到 Android 手机，通过局域网 HTTP 协议传输。

**PC 端**：C# .NET 6.0 托盘程序，捕获 Windows 系统通知，内建 ntfy 兼容 HTTP 服务器。

**手机端**：Flutter App，轮询 ntfy API 接收通知并显示为本地通知。

## 系统架构

```
[Windows PC]                          [Android Phone]
┌──────────────────────┐    HTTP       ┌─────────────────────┐
│ NotificationForwarder│◄──poll/2s────│ NotifForward App    │
│                      │   :8080      │                     │
│ - UserNotification   │              │ - provider/ntfy     │
│   Listener (WinRT)   │              │ - flutter_local_    │
│ - SetWinEventHook    │  [ntfy 协议]  │   notifications     │
│   + UI Automation    │              │ - SharedPreferences │
│ - HttpListener       │              │                     │
│   (ntfy server)      │              │                     │
└──────────────────────┘              └─────────────────────┘
```

## 快速开始

### 环境要求

- **PC**：Windows 10/11，.NET 6.0 SDK（仅开发模式需要）
- **手机**：Android 8.0+
- **网络**：手机和电脑连接同一局域网

### 1. 运行 PC 端

```powershell
# 开发模式（需要 .NET 6.0 SDK）
cd src/NotificationService
dotnet run

# 构建独立单文件 EXE（无需 .NET Runtime）
dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o dist/
.\dist\NotificationForwarder.exe
```

启动后系统托盘出现图标，右键菜单可查看状态或退出。
浏览器打开 `http://<本机IP>:8080` 可查看 Dashboard。

### 2. 构建手机 App

```powershell
cd src/notification_app
flutter pub get
flutter run          # 直接部署到手机

# 或构建 APK
flutter build apk --release
```

### 3. 连接配对

1. 确保手机和电脑在同一局域网
2. 打开手机 App，输入 PC 的 IP 地址和端口（默认 8080）
3. 点击 Test Connection 测试连通性，再点 Connect
4. 连接成功后显示通知列表

### 4. 测试

```powershell
cd src/test_tools
pip install -r requirements.txt

# 发送单条测试通知
python send_test_notification.py --title "Hello" --body "World"

# 批量发送模拟通知
python batch_send.py
```

## 通知协议 (ntfy)

手机 App 轮询 `GET http://<IP>:8080/{topic}/latest` 获取最新通知：

```json
{
  "id": "1",
  "time": 1750348800,
  "title": "[QQ] 新消息",
  "message": "你好，明天的会议...",
  "topic": "windows-notifications"
}
```

标题格式 `[AppName] Title`，手机端自动解析 App 名和标题。

## 通知循环防护

手机 App 收到的通知可能被手机上的转发软件（如 vivo办公套件）回传到 PC，造成死循环。程序内置阻断机制：`BlockedApps` 默认为 `["NotifForward"]`，来自本 App 的回传通知直接丢弃。

## 项目结构

```
├── src/
│   ├── NotificationService/         # C# PC 端服务
│   │   ├── Program.cs               # 入口
│   │   ├── MainForm.cs              # 托盘窗口
│   │   ├── BlacklistForm.cs         # 黑名单管理对话框
│   │   ├── Models/
│   │   │   └── NotificationData.cs  # 数据模型
│   │   ├── Services/
│   │   │   ├── AppSettings.cs       # 配置
│   │   │   ├── INotificationCaptureService.cs
│   │   │   ├── NotificationCaptureService.cs  # 通知捕获
│   │   │   ├── NtfyServerService.cs           # ntfy HTTP 服务器
│   │   │   └── NtfyForwarderService.cs        # 外部 ntfy 转发
│   │   ├── Native/
│   │   │   ├── Win32Api.cs          # P/Invoke
│   │   │   └── UIAutomationHelper.cs # UI Automation
│   │   └── Resources/
│   │       └── app.ico              # 托盘图标
│   ├── notification_app/            # Flutter 手机 App
│   │   └── lib/
│   │       ├── main.dart
│   │       ├── models/notification_data.dart
│   │       ├── providers/notification_provider.dart
│   │       ├── screens/home_screen.dart
│   │       ├── services/ntfy_service.dart
│   │       └── widgets/notification_card.dart
│   └── test_tools/                  # Python 测试工具
├── scripts/                         # 构建脚本
├── dist/                            # 构建产物
└── appsettings.json                 # 运行时配置
```

## 配置 (appsettings.json)

```json
{
  "AppSettings": {
    "NtfyPort": 8080,
    "NtfyTopic": "windows-notifications",
    "DebounceWindowMs": 300,
    "BlockedApps": ["NotifForward"]
  }
}
```

## 技术栈

| 组件 | 技术 | 核心依赖 |
|------|------|----------|
| PC 服务 | C# .NET 6.0 WinForms | Microsoft.Extensions.*, Serilog, Interop.UIAutomationClient |
| 手机 App | Flutter 3.x | provider, http, flutter_local_notifications, shared_preferences |
| 测试工具 | Python 3 | winotify |

## 注意事项

- **Session 0 隔离**：程序以用户会话托盘程序运行（非 Windows 服务），因为 Session 0 无法监听用户 UI 事件
- **防火墙**：首次运行需放行端口 8080。以管理员运行 `netsh http add urlacl url=http://+:%PORT%/ user=Everyone` 可避免权限问题
- **通知捕获**：主路径 `UserNotificationListener` 需 MSIX/sparse package 授予权限，否则自动回退到 `SetWinEventHook` + UI Automation
- **开机自启**：将编译好的 EXE 添加到 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`

## License

MIT
