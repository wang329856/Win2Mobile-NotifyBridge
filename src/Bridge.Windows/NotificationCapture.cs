using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using System.Windows.Threading;
using Windows.ApplicationModel;
using Windows.UI.Notifications;
using Windows.UI.Notifications.Management;
using Win2Mobile.Core;

namespace Win2Mobile.Windows;

internal sealed class NotificationCapture : IDisposable
{
    private readonly Dispatcher _dispatcher;
    private readonly DispatcherTimer _timer;
    private readonly NotificationSnapshotTracker _snapshots = new();
    private UserNotificationListener? _listener;
    private bool _reading;
    private bool _subscribed;
    private bool _disposed;
    private bool _paused;
    private DateTimeOffset _resumeAt = DateTimeOffset.MinValue;
    // Acknowledgement means saved, deliberately filtered, or already present. Failed saves retry.
    public event Func<CapturedNotification, bool>? Notification;
    public event Action<string>? Status;
    public event Action<string, string>? AppDiscovered;
    public bool HasAccess { get; private set; }
    public bool Paused
    {
        get => _paused;
        set
        {
            // A resume boundary prevents a delayed change callback from replaying paused toasts.
            if (_paused && !value) _resumeAt = DateTimeOffset.UtcNow;
            _paused = value;
        }
    }
    public NotificationCapture(Dispatcher dispatcher)
    {
        _dispatcher = dispatcher;
        _timer = new DispatcherTimer(TimeSpan.FromSeconds(15), DispatcherPriority.Background, async (_, _) => await SnapshotAsync(), dispatcher);
        _timer.Stop();
    }
    public static bool HasPackageIdentity
    {
        get { try { return !string.IsNullOrEmpty(Package.Current.Id.Name); } catch { return false; } }
    }
    public async Task InitializeAsync(bool requestPermission)
    {
        _dispatcher.VerifyAccess();
        if (!HasPackageIdentity)
        {
            Status?.Invoke("当前为免安装运行，可连接手机和发送测试；自动采集 Windows 系统通知需要使用安装版并授权。");
            return;
        }
        try
        {
            _listener ??= UserNotificationListener.Current;
            var access = requestPermission ? await _listener.RequestAccessAsync() : _listener.GetAccessStatus();
            HasAccess = access == UserNotificationListenerAccessStatus.Allowed;
            if (access != UserNotificationListenerAccessStatus.Allowed)
            {
                _snapshots.ResetBaseline();
                Status?.Invoke(access == UserNotificationListenerAccessStatus.Denied
                    ? "通知权限已拒绝或撤销。请在 Windows 设置 → 隐私 → 通知中允许 Win2Mobile，随后再次点击授权。"
                    : "尚未获得通知权限。请点击「授权通知访问」，在系统提示中允许。");
                _timer.Start();
                return;
            }
            if (!_subscribed) { _listener.NotificationChanged += OnNotificationChanged; _subscribed = true; }
            await SnapshotAsync();
            _timer.Start();
        }
        catch (Exception ex) { Status?.Invoke($"通知访问失败：{ex.Message}。请确认已安装 MSIX 并允许通知访问。"); }
    }
    private void OnNotificationChanged(UserNotificationListener sender, UserNotificationChangedEventArgs args)
    {
        if (!_disposed) _dispatcher.BeginInvoke(new Action(async () => await SnapshotAsync()));
    }
    private async Task SnapshotAsync()
    {
        if (_disposed || _reading || _listener is null) return;
        _dispatcher.VerifyAccess();
        _reading = true;
        try
        {
            if (_listener.GetAccessStatus() != UserNotificationListenerAccessStatus.Allowed)
            {
                HasAccess = false;
                _snapshots.ResetBaseline();
                Status?.Invoke("通知权限不可用；请在 Windows 设置中允许访问，再点击授权。采集已停止。");
                return;
            }
            if (!_subscribed) { _listener.NotificationChanged += OnNotificationChanged; _subscribed = true; }
            HasAccess = true;
            var notifications = await _listener.GetNotificationsAsync(NotificationKinds.Toast);
            HasAccess = true;
            if (_disposed) return;
            _snapshots.BeginSnapshot(DateTimeOffset.UtcNow);
            int failures = 0;
            foreach (var item in notifications)
            {
                string? key = null;
                try
                {
                // AppUserModelId is the stable OS application identity; display names are never filter keys.
                string appId = item.AppInfo.AppUserModelId;
                if (string.IsNullOrWhiteSpace(appId)) continue;
                string name = item.AppInfo.DisplayInfo.DisplayName;
                key = $"{appId}:{item.Id}:{item.CreationTime.UtcTicks}";
                AppDiscovered?.Invoke(appId, name);
                if (!_snapshots.ShouldCapture(key, item.CreationTime, Paused, _resumeAt)) continue;
                var binding = item.Notification.Visual.GetBinding(KnownNotificationBindings.ToastGeneric);
                if (binding is null) continue;
                var texts = binding.GetTextElements();
                string title = texts.Count > 0 ? texts[0].Text : name;
                var bodyParts = new List<string>();
                for (int i = 1; i < texts.Count; i++) bodyParts.Add(texts[i].Text);
                if (Notification?.Invoke(new(key, appId, name, title, string.Join(Environment.NewLine, bodyParts), item.CreationTime.ToUniversalTime())) != true)
                {
                    _snapshots.Failed(key);
                    failures++;
                }
                }
                catch
                {
                    // One malformed/vanished toast must not block the rest of the snapshot.
                    if (key is not null) _snapshots.Failed(key);
                    failures++;
                }
            }
            _snapshots.CompleteSnapshot();
            Status?.Invoke(failures > 0
                ? $"通知权限已授权；本次有 {failures} 条读取或保存失败，仍在通知中心的内容会继续重试。"
                : Paused ? "通知权限已授权；采集已暂停，新通知不会入库。" : "通知权限已授权；正在采集新的系统 Toast 通知（每 15 秒补偿检查）。");
        }
        catch (Exception ex) { Status?.Invoke($"通知采集暂时失败：{ex.Message}"); }
        finally { _reading = false; }
    }
    public void Dispose()
    {
        _disposed = true;
        _timer.Stop();
        if (_listener is not null && _subscribed) _listener.NotificationChanged -= OnNotificationChanged;
    }
}
