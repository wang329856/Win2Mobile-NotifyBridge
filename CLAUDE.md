# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

NotifForward captures Windows 10/11 toast notifications and forwards them to a Flutter phone app over the local network. The PC runs an **ntfy-protocol-compatible HTTP server**; the phone app polls it to receive notifications.

## Build & Run Commands

### PC (C# .NET 6.0)

```powershell
# Run in development
cd src/NotificationService
dotnet run

# Build standalone single-file EXE (win-x64)
.\scripts\build_service.ps1

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
.\scripts\build_apk.ps1
# Output: dist/notification_app.apk
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

**Entry**: `Program.cs` → creates `MainForm` (Windows Forms tray app), not a console app or Windows service. Session 0 isolation is WHY it must be a user-session app — Windows services in Session 0 cannot see user UI notifications.

**Notification Capture** (`Services/NotificationCaptureService.cs`):
- **Primary path**: `UserNotificationListener` API (WinRT) — polls every 500ms for new toast notifications. Requires MSIX/sparse package identity (`userNotificationListener` capability), otherwise this silently fails.
- **Fallback path**: `SetWinEventHook` on `EVENT_OBJECT_SHOW` + UI Automation (`Native/UIAutomationHelper.cs`) to scrape text from toast windows. Uses COM `CUIAutomation8`/`CUIAutomation` via `dynamic`.
- Deduplication via content hash + 300ms debounce window.
- App name extraction from `[AppName] Title` or `【AppName】Title` title pattern.
- Blacklist filter: notifications from blocked apps are silently dropped.

**ntfy Server** (`Services/NtfyServerService.cs`):
- Custom `HttpListener`-based HTTP server implementing the ntfy publish-subscribe protocol.
- Routes: `POST /{topic}` (publish), `GET /{topic}/json?poll=1` (long-polling), `GET /{topic}/latest` (latest message), `GET /` (HTML dashboard), `GET /api/status|test|blacklist/*` (management API).
- Clients are notified via in-memory `ConcurrentDictionary` of subscriber queues; supports both long-polling and SSE streaming.
- The Flutter app currently polls `/{topic}/latest` every 2 seconds (not long-polling/SSE).
- Config loaded from `appsettings.json` (port default 8080, topic default `windows-notifications`).

**NtfyForwarderService** (`Services/NtfyForwarderService.cs`):
- A separate HTTP client that POSTs captured notifications to an external ntfy server. **Currently not wired into the main flow** — `MainForm` calls `NtfyServerService.PublishFromCode()` directly instead.

**Key Dependencies**: `Microsoft.Extensions.*` (DI, config, options), `Serilog` (file logging to `logs/`), `Interop.UIAutomationClient` (COM interop).

**Port/Permissions**: `HttpListener` on `http://+:{port}/` requires admin rights or a URL reservation: `netsh http add urlacl url=http://+:{port}/ user=Everyone`.

### Phone App (`src/notification_app/`)

- Flutter app using `provider` for state management.
- `NotificationProvider` manages connection lifecycle, polls ntfy `/{topic}/latest` every 2 seconds via `NtfyService`, and deduplicates by message `id`.
- `NotificationService` wraps `flutter_local_notifications` for Android local notification display.
- `HomeScreen` shows either a connection form (URL + topic input with test button) or the notification list.
- Persists server URL/topic in `SharedPreferences` for auto-reconnect.
- App name extracted from ntfy title `[AppName]` prefix in `NotificationData.fromNtfy()`.

### Test Tools (`src/test_tools/`)

- `send_test_notification.py`: Sends Windows toasts via `winotify` AND POSTs to the ntfy server for end-to-end testing. Supports `--count` and `--interval` for load testing.
- `batch_send.py`: Sends 10 varied simulated notifications (Outlook, Slack, Teams, etc.) with random intervals.

### Key Configuration (`appsettings.json`)

```json
{
  "AppSettings": {
    "NtfyPort": 8080,
    "NtfyTopic": "windows-notifications",
    "DebounceWindowMs": 300,
    "BlockedApps": ["Slack", "vivo办公套件", "vivo服务"]
  }
}
```

The blacklist is mutable at runtime — changes via the tray menu's "Manage Blacklist" dialog or the web dashboard API persist back to `appsettings.json`.

## Architecture Note: README vs Reality

The README.md describes a WebSocket architecture using Fleck and QRCoder (port 8000). The actual codebase has been refactored to use the **ntfy HTTP protocol** instead. There is no Fleck, no QRCoder, no WebSocket, and the default port is 8080. The `src/ntfy/` directory contains only the upstream ntfy project's README (not code). When updating documentation, align with the actual HTTP-based architecture.
