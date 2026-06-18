using System.Collections.Concurrent;
using NotificationService.Models;
using Microsoft.Extensions.Logging;

namespace NotificationService.Services;

/// <summary>
/// Captures Windows notifications via UserNotificationListener (primary)
/// with SetWinEventHook as fallback.
/// </summary>
public class NotificationCaptureService : INotificationCaptureService, IDisposable
{
    private readonly ILogger<NotificationCaptureService> _logger;
    private readonly AppSettings _settings;
    private Thread? _hookThread;
    private CancellationTokenSource? _cts;
    private bool _disposed;

    private string _lastHash = "";
    private DateTime _lastCapture = DateTime.MinValue;
    private readonly ConcurrentDictionary<string, int> _seenClasses = new();

    /// <summary>Cache: process executable path → FileDescription (human-readable app name).</summary>
    private static readonly ConcurrentDictionary<string, string> DisplayNameCache = new(StringComparer.OrdinalIgnoreCase);

    public event EventHandler<NotificationData>? NotificationCaptured;

    public NotificationCaptureService(ILogger<NotificationCaptureService> logger, Microsoft.Extensions.Options.IOptions<AppSettings> settings)
    {
        _logger = logger;
        _settings = settings.Value;
    }

    public Task StartAsync(CancellationToken cancellationToken = default)
    {
        _logger.LogInformation("Starting notification capture...");
        _cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);

        _hookThread = new Thread(() => CaptureThreadProc(_cts.Token))
        {
            Name = "NotifCapture",
            IsBackground = true
        };
        _hookThread.SetApartmentState(ApartmentState.STA);
        _hookThread.Start();

        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken = default)
    {
        _cts?.Cancel();
        _hookThread?.Join(TimeSpan.FromSeconds(3));
        return Task.CompletedTask;
    }

    private async void CaptureThreadProc(CancellationToken token)
    {
        if (await TryUserNotificationListener(token))
            return;

        _logger.LogInformation("UserNotificationListener not available, using SetWinEventHook fallback");
        WinEventLoop(token);
    }

    private async Task<bool> TryUserNotificationListener(CancellationToken token)
    {
        try
        {
            var listener = await Windows.UI.Notifications.Management.UserNotificationListener.Current.RequestAccessAsync();
            _logger.LogInformation("UserNotificationListener access: {Status}", listener);

            if (listener != Windows.UI.Notifications.Management.UserNotificationListenerAccessStatus.Allowed)
                return false;

            _logger.LogInformation("Listening for Windows notifications via UserNotificationListener...");

            var seenIds = new HashSet<string>();

            while (!token.IsCancellationRequested)
            {
                try
                {
                    var notifs = await Windows.UI.Notifications.Management.UserNotificationListener.Current
                        .GetNotificationsAsync(Windows.UI.Notifications.NotificationKinds.Toast);

                    foreach (var notif in notifs)
                    {
                        if (token.IsCancellationRequested) break;

                        var id = notif.Id.ToString();
                        if (!seenIds.Add(id)) continue;

                        // Extract title and body
                        string title = "", body = "";
                        try
                        {
                            var notification = notif.Notification;
                            var visual = notification.Visual;
                            var bindings = visual.Bindings;
                            foreach (var binding in bindings)
                            {
                                var texts = binding.GetTextElements();
                                for (int ti = 0; ti < texts.Count; ti++)
                                {
                                    var text = texts[ti].Text ?? "";
                                    if (ti == 0 && string.IsNullOrEmpty(title))
                                        title = text;
                                    else if (!string.IsNullOrWhiteSpace(text))
                                        body = string.IsNullOrEmpty(body) ? text : $"{body} {text}";
                                }
                            }
                        }
                        catch { continue; }

                        // Get app name and strip [AppName] prefix from title if present
                        string appName = "Windows";
                        try { appName = notif.AppInfo.DisplayInfo.DisplayName; } catch { }
                        var nameMatch = System.Text.RegularExpressions.Regex.Match(title, @"^[\[【](.+?)[\]】]");
                        if (nameMatch.Success)
                        {
                            appName = nameMatch.Groups[1].Value;
                            title = title.Substring(nameMatch.Length).Trim();
                        }

                        if (string.IsNullOrWhiteSpace(title) && string.IsNullOrWhiteSpace(body))
                            continue;

                        _logger.LogInformation("Detected: [{App}] {Title}", appName, title);

                        // Blocked apps check
                        if (_settings.BlockedApps.Any(b => string.Equals(b, appName, StringComparison.OrdinalIgnoreCase)))
                        {
                            _logger.LogInformation("Blocked: [{App}] in blacklist", appName);
                            continue;
                        }

                        // Debounce
                        var hash = $"{title}|{body}".GetHashCode().ToString("X8");
                        var now = DateTime.UtcNow;
                        if (hash == _lastHash && (now - _lastCapture) < TimeSpan.FromMilliseconds(_settings.DebounceWindowMs))
                            continue;
                        _lastHash = hash;
                        _lastCapture = now;

                        var data = NotificationData.Create(title, body, appName);
                        _logger.LogInformation("Captured: [{App}] {Title}", appName, title);
                        NotificationCaptured?.Invoke(this, data);
                    }
                }
                catch (Exception ex) when (!token.IsCancellationRequested)
                {
                    _logger.LogDebug(ex, "Error polling notifications");
                }

                await Task.Delay(500, token);
            }
            return true;
        }
        catch (Exception ex)
        {
            _logger.LogWarning("UserNotificationListener failed: {Msg}", ex.Message);
            return false;
        }
    }

    private void WinEventLoop(CancellationToken token)
    {
        IntPtr hook = IntPtr.Zero;
        try
        {
            hook = Native.Win32Api.SetWinEventHook(
                Native.Win32Api.EVENT_OBJECT_SHOW,
                Native.Win32Api.EVENT_OBJECT_SHOW,
                IntPtr.Zero, WinEventCallback, 0, 0,
                Native.Win32Api.WINEVENT_OUTOFCONTEXT);

            if (hook == IntPtr.Zero) return;
            _logger.LogInformation("SetWinEventHook installed");

            while (!token.IsCancellationRequested)
            {
                var r = Native.Win32Api.GetMessage(out var msg, IntPtr.Zero, 0, 0);
                if (r == 0 || r == -1) break;
                Native.Win32Api.TranslateMessage(ref msg);
                Native.Win32Api.DispatchMessage(ref msg);
            }

            Native.Win32Api.UnhookWinEvent(hook);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "WinEvent hook crashed");
            if (hook != IntPtr.Zero) Native.Win32Api.UnhookWinEvent(hook);
        }
    }

    private void WinEventCallback(IntPtr hWinEventHook, uint eventType, IntPtr hwnd,
        int idObject, int idChild, uint dwEventThread, uint dwmsEventTime)
    {
        try
        {
            if (idObject != 0 || idChild != 0) return;

            var sb = new System.Text.StringBuilder(256);
            Native.Win32Api.GetClassName(hwnd, sb, sb.Capacity);
            var cn = sb.ToString();
            _seenClasses.TryAdd(cn, 1);

            if (!IsNotificationClass(cn)) return;

            string appName = "Unknown";
            try
            {
                Native.Win32Api.GetWindowThreadProcessId(hwnd, out uint pid);
                appName = ResolveDisplayName((int)pid);
            }
            catch { }

            string title = "", body = "";
            try { (title, body) = Native.UIAutomationHelper.ExtractTextFromWindow(hwnd); }
            catch { }

            if (string.IsNullOrWhiteSpace(title) && string.IsNullOrWhiteSpace(body)) return;

            _logger.LogInformation("Detected[Win]: [{App}] {Title}", appName, title);

            if (_settings.BlockedApps.Any(b => string.Equals(b, appName, StringComparison.OrdinalIgnoreCase)))
            {
                _logger.LogInformation("Blocked[Win]: [{App}] in blacklist", appName);
                return;
            }

            var hash = $"{title}|{body}".GetHashCode().ToString("X8");
            var now = DateTime.UtcNow;
            if (hash == _lastHash && (now - _lastCapture) < TimeSpan.FromMilliseconds(_settings.DebounceWindowMs))
                return;
            _lastHash = hash;
            _lastCapture = now;

            var data = NotificationData.Create(title, body, appName);
            _logger.LogInformation("Captured[Win]: [{App}] {Title}", appName, title);
            NotificationCaptured?.Invoke(this, data);
        }
        catch { }
    }

    private static string ResolveDisplayName(int pid)
    {
        try
        {
            using var proc = System.Diagnostics.Process.GetProcessById(pid);
            var procName = proc.ProcessName;

            string? procPath = null;
            try { procPath = proc.MainModule?.FileName; } catch { }

            if (procPath != null && DisplayNameCache.TryGetValue(procPath, out var cached))
                return cached;

            try
            {
                var desc = proc.MainModule?.FileVersionInfo?.FileDescription;
                if (!string.IsNullOrWhiteSpace(desc) && desc.Length > 1)
                {
                    if (procPath != null) DisplayNameCache[procPath] = desc;
                    return desc;
                }
            }
            catch { }

            if (procPath != null) DisplayNameCache[procPath] = procName;
            return procName;
        }
        catch
        {
            return "Unknown";
        }
    }

    private static bool IsNotificationClass(string cn)
    {
        if (string.IsNullOrEmpty(cn)) return false;
        return cn.Contains("CoreWindow", StringComparison.OrdinalIgnoreCase)
            || cn.Contains("Toast", StringComparison.OrdinalIgnoreCase)
            || cn.Contains("Notify", StringComparison.OrdinalIgnoreCase)
            || cn.Equals("ApplicationFrameWindow", StringComparison.OrdinalIgnoreCase)
            || cn.StartsWith("Windows.UI.", StringComparison.OrdinalIgnoreCase);
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _cts?.Cancel();
        _cts?.Dispose();
    }
}
