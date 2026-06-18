# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Win2Mobile-NotifyBridge captures Windows 10/11 toast notifications and forwards them to a Flutter phone app over the local network. The PC runs an **ntfy-protocol-compatible HTTP server**; the phone app polls it to receive notifications.

## Build & Run Commands

### PC (C# .NET 6.0)

```powershell
# Run in development
cd src/NotificationService
dotnet run

# Build standalone single-file EXE (win-x64, self-contained)
dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o dist/

# Build MSIX package (enables UserNotificationListener API access)
.\scripts\package_msix.ps1

# Register as sparse package without full MSIX install (admin)
.\scripts\register_sparse_package.ps1
```

### Phone App (Flutter)

```powershell
cd src/notification_app
flutter pub get
flutter run                    # Deploy to connected device

# Build APK
flutter build apk --release
```

### Test Tools

```powershell
cd src/test_tools
pip install -r requirements.txt
python send_test_notification.py --title "Hello" --body "Test"
python batch_send.py
```

## High-Level Architecture

### PC Service (`src/NotificationService/`)

**Entry**: `Program.cs` → creates `MainForm` (Windows Forms tray app), not a Windows service. Session 0 isolation is WHY it must be a user-session app.

**Notification Capture** (`Services/NotificationCaptureService.cs`):
- **Primary path**: `UserNotificationListener` API (WinRT) — polls every 500ms for new toast notifications. Requires MSIX/sparse package identity (`userNotificationListener` capability).
- **Fallback path**: `SetWinEventHook` on `EVENT_OBJECT_SHOW` + UI Automation (`Native/UIAutomationHelper.cs`) to scrape text from toast windows. Uses COM `CUIAutomation8`/`CUIAutomation` via `dynamic`. Displays app name from `FileVersionInfo.FileDescription` (cached).
- Deduplication via content hash + debounce window (default 300ms).
- App name extraction from `[AppName] Title` or `【AppName】Title` title pattern.
- Blocked apps check: notifications from apps in `BlockedApps` are silently dropped.

**ntfy Server** (`Services/NtfyServerService.cs`):
- Custom `HttpListener`-based HTTP server implementing the ntfy publish-subscribe protocol.
- Routes: `POST /{topic}` (publish), `GET /{topic}/json?poll=1` (long-polling), `GET /{topic}/latest` (latest message), `GET /` (HTML dashboard), `GET /api/status|test|blacklist/*` (management API).
- The Flutter app polls `/{topic}/latest` every 2 seconds (not long-polling/SSE).
- Config loaded from `appsettings.json` (port default 8080, topic default `windows-notifications`).

**NtfyForwarderService** (`Services/NtfyForwarderService.cs`):
- A separate HTTP client that POSTs captured notifications to an external ntfy server. **Not wired into the main flow** — `MainForm` calls `NtfyServerService.PublishFromCode()` directly.

**Notification Loop Prevention**:
- `BlockedApps` defaults to `["NotifForward"]` to prevent the phone app's own notifications from being re-captured when relayed back by phone-to-PC mirroring apps.
- Check happens in both `NotificationCaptureService` and `NtfyServerService.PublishFromCode`.

**Key Dependencies**: `Microsoft.Extensions.*` (DI, config), `Serilog` (file logging to `logs/`), `Interop.UIAutomationClient` (COM interop).

**Port/Permissions**: `HttpListener` on `http://+:{port}/` requires admin rights or a URL reservation: `netsh http add urlacl url=http://+:{port}/ user=Everyone`.

### Phone App (`src/notification_app/`)

- Flutter app using `provider` for state management.
- `NotificationProvider` manages connection lifecycle, polls ntfy `/{topic}/latest` every 2 seconds via `NtfyService`, deduplicates by message `id`.
- `NotificationService` wraps `flutter_local_notifications` for Android local notification display.
- `HomeScreen` shows connection form (URL + topic input with test button) or notification list.
- Persists server URL/topic in `SharedPreferences` for auto-reconnect.
- App name extracted from ntfy title `[AppName]` prefix in `NotificationData.fromNtfy()`.

### Test Tools (`src/test_tools/`)

- `send_test_notification.py`: Sends Windows toasts via `winotify` AND POSTs to the ntfy server for end-to-end testing. Supports `--count` and `--interval`.
- `batch_send.py`: Sends 10 varied simulated notifications (Outlook, Slack, Teams, etc.) with random intervals.

### Key Configuration (`appsettings.json`)

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

The config is optional at startup (program uses defaults if missing). Changes via the tray menu's "Manage Blacklist" dialog or the web dashboard API persist back to `appsettings.json`.

### Tray Icon

The PC app icon is embedded as a resource (`Resources/app.ico`), converted from the Flutter app's `ic_launcher.png` for visual consistency across platforms.
