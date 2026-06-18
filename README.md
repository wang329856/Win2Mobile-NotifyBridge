# NotifForward — Windows Notification Forwarder

将 Windows 通知实时转发到手机，支持局域网自动发现和二维码扫码配对。

**PC 端**：C# .NET 控制台程序，捕获 Windows 系统通知，通过 WebSocket 广播给所有已连接设备。

**手机端**：Flutter 跨平台 App，接收通知并显示为手机本地通知。

## 系统架构

```
[Windows PC]                          [Phone]
┌──────────────────────┐    JSON       ┌──────────────────┐
│ NotificationForwarder│◄──WebSocket──►│ NotifForward App │
│                      │   ws://IP:    │                  │
│ - WinEventHook       │    8000       │ - web_socket_    │
│ - UI Automation      │              │   channel         │
│ - Fleck WS Server    │              │ - flutter_local_  │
│ - QR Code Server     │              │   notifications   │
│   (http://IP:8001)   │              │                  │
└──────────────────────┘              └──────────────────┘
```

## 快速开始

### 环境要求

- **PC**: Windows 10/11, .NET 6.0 SDK
- **手机**: Android 8.0+, 或 iOS 14+
- **开发环境**: Flutter 3.x SDK (仅构建手机 App 时需要)
- **网络**: 手机和电脑连接同一局域网

### 1. 构建并运行 PC 端服务

```powershell
# 方式一：直接用 dotnet 运行（开发模式）
cd src/NotificationService
dotnet run

# 方式二：构建独立 EXE
.\scripts\build_service.ps1
.\dist\NotificationService\NotificationForwarder.exe
```

服务启动后：
- WebSocket 服务器监听 `ws://0.0.0.0:8000`
- QR 配对页面可以在浏览器中打开 `http://<PC的IP>:8001`
- 控制台会打印本机所有 IP 地址

### 2. 构建并安装手机 App

```powershell
# 构建 Android APK
.\scripts\build_apk.ps1

# APK 输出位置
.\dist\notification_app.apk
```

将 APK 传输到手机安装，或通过 `flutter run` 直接部署。

### 3. 连接配对

1. 确保手机和电脑在同一局域网
2. 打开手机 App，选择以下任一方式连接：
   - **手动输入**：输入 PC 的 IP 地址和端口（默认 8000）
   - **扫描 LAN**：自动扫描局域网中运行的服务
   - **二维码**：在 PC 浏览器打开 `http://<PC-IP>:8001`，用第三方扫码工具扫描
3. 连接成功后，手机 App 会显示 "Connected" 状态

### 4. 测试通知转发

```powershell
# 安装 Python 依赖
pip install winotify

# 发送测试通知
python src/test_tools/send_test_notification.py --title "Hello" --body "Test from Python"

# 批量发送多种通知
python src/test_tools/batch_send.py
```

## 项目结构

```
├── src/
│   ├── NotificationService/        # C# .NET 通知捕获与转发服务
│   │   ├── Program.cs              # 入口，依赖注入配置
│   │   ├── Worker.cs               # 后台服务协调器
│   │   ├── Models/
│   │   │   └── NotificationData.cs # 通知数据模型和 WebSocket 协议
│   │   ├── Services/
│   │   │   ├── AppSettings.cs      # 配置选项
│   │   │   ├── WebSocketServerService.cs  # Fleck WebSocket 服务器
│   │   │   ├── NotificationCaptureService.cs  # Win32 钩子 + UI Automation
│   │   │   └── QrCodeService.cs    # 二维码配对 HTTP 服务器
│   │   └── Native/
│   │       ├── Win32Api.cs         # P/Invoke 声明
│   │       └── UIAutomationHelper.cs  # UI Automation 文本提取
│   ├── notification_app/           # Flutter 手机 App
│   │   └── lib/
│   │       ├── main.dart
│   │       ├── models/
│   │       ├── providers/          # 状态管理 (Connection, Notification)
│   │       ├── screens/            # 页面 (Splash, Connection, Home, Settings)
│   │       ├── services/           # WebSocket, Notification, Discovery
│   │       └── widgets/            # UI 组件
│   └── test_tools/                 # Python 测试工具
├── scripts/                        # 构建脚本
└── dist/                           # 构建产物 (EXE, APK)
```

## 技术栈

| 组件 | 技术 | 关键依赖 |
|------|------|----------|
| PC 后端 | C# .NET 6.0 | Fleck, Interop.UIAutomationClient, Serilog, QRCoder |
| 手机 App | Flutter 3.x | provider, web_socket_channel, flutter_local_notifications |
| 测试工具 | Python 3 | winotify |

## WebSocket 协议

### 通知消息 (Server → Client)

```json
{
  "type": "notification",
  "id": "a1b2c3d4...",
  "data": {
    "title": "新邮件",
    "body": "你收到了一封来自 Alice 的邮件",
    "appName": "Microsoft Outlook",
    "timestamp": "2026-06-18T14:30:00Z"
  }
}
```

### 心跳 (Server → Client)

```json
{
  "type": "ping",
  "timestamp": "2026-06-18T14:30:00Z"
}
```

## 注意事项

- **Session 0 隔离**：本程序以控制台应用模式运行（非 Windows 服务），因为 Windows 服务运行在 Session 0 无法直接监听用户会话中的 UI 事件。如需开机自启，可添加到注册表 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`。
- **防火墙**：首次运行需要允许 Windows 防火墙放行端口 8000 和 8001。
- **通知捕获**：目前支持 Windows 10/11 的原生 Toast 通知。部分 Win32 应用的自定义弹窗可能无法捕获。
- **二维码服务**：端口 8001 的 HTTP 服务器可能需要管理员权限。如启动失败，以管理员身份运行或添加 URL 保留：`netsh http add urlacl url=http://+:%PORT%/ user=Everyone`

## License

MIT
