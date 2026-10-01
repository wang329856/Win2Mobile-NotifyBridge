using System;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography.X509Certificates;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Windows.ApplicationModel;
using QRCoder;
using Win2Mobile.Core;
using Win2Mobile.Transport.Lan;
using Forms = System.Windows.Forms;

namespace Win2Mobile.Windows;

public sealed class AppFilter
{
    public required string AppId { get; init; }
    public required string Name { get; init; }
    public bool Enabled { get; set; }
}

public partial class MainWindow : Window
{
    private readonly ObservableCollection<BridgeEvent> _recent = new();
    private readonly ObservableCollection<AppFilter> _apps = new();
    private readonly bool _startupLaunch;
    private DesktopSettings? _settings;
    private BridgeStore? _store;
    private LanBridgeHost? _host;
    private X509Certificate2? _certificate;
    private NotificationCapture? _capture;
    private Forms.NotifyIcon? _tray;
    private DispatcherTimer? _refreshTimer;
    private bool _serviceReady;
    private bool _exiting;
    private bool _initialized;
    private DateTimeOffset _qrExpires;
    public MainWindow(bool startupLaunch)
    {
        InitializeComponent();
        _startupLaunch = startupLaunch;
        RecentList.ItemsSource = _recent;
        AppList.ItemsSource = _apps;
    }
    private async void WindowLoaded(object sender, RoutedEventArgs e)
    {
        if (_initialized) return;
        _initialized = true;
        CreateTray();
        if (_startupLaunch) Hide();
        try
        {
            _settings = DesktopSettings.Load();
            DataPath.Text = "用户数据目录：" + DesktopSettings.DataDirectory;
            _store = new BridgeStore(Path.Combine(DesktopSettings.DataDirectory, "bridge.db"), Environment.MachineName);
            foreach (var item in _store.GetEvents(Math.Max(0, _store.HighWatermark - 100), 100).Reverse()) _recent.Add(item);
            foreach (var item in _recent) DiscoverApp(item.AppId, item.AppName);
            foreach (var id in _settings.BlockedAppIds) DiscoverApp(id, id);
            _capture = new NotificationCapture(Dispatcher) { Paused = _settings.Paused };
            _capture.Status += status => CaptureStatus.Text = status;
            _capture.AppDiscovered += DiscoverApp;
            _capture.Notification += OnCaptured;
            UpdatePause();
            await _capture.InitializeAsync(false);
            _certificate = CertificateManager.GetOrCreate(DesktopSettings.DataDirectory);
            _host = new LanBridgeHost(_store, _certificate);
            _host.Pairing.Changed += PairingChanged;
            await _host.StartAsync();
            _serviceReady = true;
            ServiceStatus.Text = "局域网 HTTPS / WSS 服务已启动 · 端口 47721 · " + _store.Identity.ServerName;
            RescanAddresses();
            RefreshPairing();
            await RefreshStartupAsync();
            _refreshTimer = new DispatcherTimer(TimeSpan.FromSeconds(2), DispatcherPriority.Background, (_, _) => RefreshLists(), Dispatcher);
            RefreshLists();
        }
        catch (Exception ex)
        {
            ServiceStatus.Text = "初始化失败：" + ex.Message;
            ActionStatus.Text = "请解决上述问题后退出并重新启动。";
        }
    }
    private void CreateTray()
    {
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("打开 Win2Mobile", null, (_, _) => Dispatcher.Invoke(ShowWindow));
        menu.Items.Add("暂停 / 恢复采集", null, (_, _) => Dispatcher.Invoke(TogglePause));
        menu.Items.Add("退出", null, async (_, _) => await Dispatcher.InvokeAsync(ExitAsync).Task.Unwrap());
        _tray = new Forms.NotifyIcon { Text = "Win2Mobile · 局域网通知桥", Icon = System.Drawing.SystemIcons.Information, ContextMenuStrip = menu, Visible = true };
        _tray.DoubleClick += (_, _) => Dispatcher.Invoke(ShowWindow);
    }
    private void ShowWindow() { Show(); WindowState = WindowState.Normal; Activate(); }
    private void WindowClosing(object? sender, CancelEventArgs e)
    {
        if (_exiting) return;
        e.Cancel = true;
        Hide();
    }
    private async Task ExitAsync()
    {
        if (_exiting) return;
        _exiting = true;
        _refreshTimer?.Stop();
        _capture?.Dispose();
        try
        {
            if (_host is not null) { _host.Pairing.Changed -= PairingChanged; await _host.DisposeAsync(); }
        }
        catch (Exception ex) { MessageBox.Show("停止局域网服务时发生错误：" + ex.Message, "Win2Mobile"); }
        finally
        {
            _certificate?.Dispose();
            _store?.Dispose();
            _tray?.Dispose();
            System.Windows.Application.Current.Shutdown();
        }
    }
    private bool OnCaptured(CapturedNotification notification)
    {
        if (_store is null || _settings is null) return false;
        if (_settings.BlockedAppIds.Contains(notification.AppId)) return true;
        return AppendAndDisplay(notification);
    }
    private bool AppendAndDisplay(CapturedNotification notification)
    {
        if (_store is null) return false;
        try
        {
            var stored = _store.Append(notification);
            if (stored is null) return true;
            _recent.Insert(0, stored);
            while (_recent.Count > 100) _recent.RemoveAt(_recent.Count - 1);
            ActionStatus.Text = $"已保存第 {stored.Sequence} 条通知：{stored.AppName}";
            return true;
        }
        catch (Exception ex) { ActionStatus.Text = "通知保存失败：" + ex.Message; return false; }
    }
    private void SendTestClick(object sender, RoutedEventArgs e)
    {
        if (!_serviceReady || _store is null) { ActionStatus.Text = "局域网服务尚未就绪，请检查上方状态。"; return; }
        if (_store.GetDevices().Count == 0) { ActionStatus.Text = "请先在手机扫码，并在电脑批准配对请求。"; return; }
        AppendAndDisplay(new("test:" + Guid.NewGuid().ToString("N"), "Win2Mobile.Test", "Win2Mobile", "连接测试", "如果你在手机收到这条通知，电脑与手机的通知传输已经连通。", DateTimeOffset.UtcNow));
    }
    private void DiscoverApp(string id, string name)
    {
        if (_settings is null || _apps.Any(x => x.AppId == id)) return;
        _apps.Add(new AppFilter { AppId = id, Name = name, Enabled = !_settings.BlockedAppIds.Contains(id) });
    }
    private void FilterChanged(object sender, RoutedEventArgs e)
    {
        if (_settings is null || sender is not CheckBox box || box.DataContext is not AppFilter app) return;
        if (box.IsChecked == true) _settings.BlockedAppIds.Remove(app.AppId); else _settings.BlockedAppIds.Add(app.AppId);
        SaveSettings();
    }
    private void SaveSettings()
    {
        try { _settings?.Save(); }
        catch (Exception ex) { ActionStatus.Text = "设置保存失败：" + ex.Message; }
    }
    private async void AuthorizeClick(object sender, RoutedEventArgs e)
    {
        // WPF event handlers resume on the real UI dispatcher, as required by RequestAccessAsync.
        if (_capture is not null) await _capture.InitializeAsync(true);
    }
    private void PauseClick(object sender, RoutedEventArgs e) => TogglePause();
    private void TogglePause()
    {
        if (_settings is null || _capture is null) return;
        _settings.Paused = !_settings.Paused;
        _capture.Paused = _settings.Paused;
        SaveSettings();
        UpdatePause();
    }
    private void UpdatePause()
    {
        PauseButton.Content = _settings?.Paused == true ? "恢复采集" : "暂停采集";
        ActionStatus.Text = _settings?.Paused == true ? "采集已暂停；暂停期间的新通知不会补发。" : "采集已恢复；通知权限状态见上方。";
    }
    private void PrivacyClick(object sender, RoutedEventArgs e) => Open("ms-settings:privacy-notifications");
    private void OpenDataClick(object sender, RoutedEventArgs e) => Open(DesktopSettings.DataDirectory);
    private void Open(string target)
    {
        try { Process.Start(new ProcessStartInfo(target) { UseShellExecute = true }); }
        catch (Exception ex) { ActionStatus.Text = "无法打开：" + ex.Message; }
    }
    private void RescanClick(object sender, RoutedEventArgs e) => RescanAddresses();
    private void RescanAddresses()
    {
        var addresses = NetworkInterface.GetAllNetworkInterfaces()
            .Where(x => x.OperationalStatus == OperationalStatus.Up && x.NetworkInterfaceType != NetworkInterfaceType.Loopback)
            .SelectMany(x => x.GetIPProperties().UnicastAddresses)
            .Select(x => x.Address)
            .Where(x => x.AddressFamily == AddressFamily.InterNetwork && !x.ToString().StartsWith("169.254.", StringComparison.Ordinal))
            .Select(x => x.ToString()).Distinct().OrderBy(x => x).ToArray();
        Addresses.ItemsSource = addresses;
        Addresses.SelectedItem = addresses.Contains(_settings?.SelectedAddress) ? _settings!.SelectedAddress : addresses.FirstOrDefault();
        if (addresses.Length == 0) { PairingQr.Source = null; QrStatus.Text = "没有可用局域网 IPv4 地址，请连接网络后重新扫描。"; }
    }
    private void AddressChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_settings is not null && Addresses.SelectedItem is string host) { _settings.SelectedAddress = host; SaveSettings(); }
        RefreshPairing();
    }
    private void RefreshQrClick(object sender, RoutedEventArgs e) => RefreshPairing();
    private void RefreshPairing()
    {
        if (!_serviceReady || _host is null || Addresses.SelectedItem is not string address) return;
        try
        {
            _host.Pairing.RefreshCode();
            using var data = QRCodeGenerator.GenerateQrCode(_host.GetPairingPayload(address), QRCodeGenerator.ECCLevel.M);
            using var qr = new PngByteQRCode(data);
            using var stream = new MemoryStream(qr.GetGraphic(5));
            var bitmap = new BitmapImage();
            bitmap.BeginInit(); bitmap.CacheOption = BitmapCacheOption.OnLoad; bitmap.StreamSource = stream; bitmap.EndInit(); bitmap.Freeze();
            PairingQr.Source = bitmap;
            PairingAddress.Text = $"https://{address}:47721";
            _qrExpires = DateTimeOffset.UtcNow.AddSeconds(120);
            QrStatus.Text = "二维码已刷新，请使用 Android 应用扫码。";
        }
        catch (Exception ex) { PairingQr.Source = null; QrStatus.Text = "二维码生成失败：" + ex.Message; }
    }
    private void PairingChanged(object? sender, EventArgs e)
    {
        if (!_exiting) Dispatcher.BeginInvoke(new Action(RefreshLists));
    }
    private void RefreshLists()
    {
        if (_exiting || _host is null || _store is null) return;
        try
        {
            string? pendingId = (PendingList.SelectedItem as PendingPairing)?.RequestId;
            string? deviceId = (DeviceList.SelectedItem as PairedDevice)?.DeviceId;
            var pending = _host.Pairing.PendingRequests.ToArray();
            var devices = _store.GetDevices();
            PendingList.ItemsSource = pending;
            PendingList.SelectedItem = pending.FirstOrDefault(x => x.RequestId == pendingId);
            DeviceList.ItemsSource = devices;
            DeviceList.SelectedItem = devices.FirstOrDefault(x => x.DeviceId == deviceId);
            if (PairingQr.Source is not null)
            {
                int remaining = (int)Math.Max(0, (_qrExpires - DateTimeOffset.UtcNow).TotalSeconds);
                QrStatus.Text = remaining > 0 ? $"二维码有效期还剩 {remaining} 秒" : "二维码已过期，请点击刷新。";
                if (remaining == 0) PairingQr.Source = null;
            }
        }
        catch (Exception ex) { ActionStatus.Text = "设备状态刷新失败：" + ex.Message; }
    }
    private void ApproveClick(object sender, RoutedEventArgs e) => RespondToPairing(true);
    private void DenyClick(object sender, RoutedEventArgs e) => RespondToPairing(false);
    private void RespondToPairing(bool approve)
    {
        if (_host is null || PendingList.SelectedItem is not PendingPairing request) { ActionStatus.Text = "请先选择待批准请求。"; return; }
        try
        {
            if (request.ExpiresAt <= DateTimeOffset.UtcNow || !_host.Pairing.PendingRequests.Any(x => x.RequestId == request.RequestId))
            {
                ActionStatus.Text = "此请求已过期或已处理，请让手机重新扫码。";
                RefreshLists();
                return;
            }
            if (approve) _host.Pairing.Approve(request.RequestId); else _host.Pairing.Deny(request.RequestId);
            ActionStatus.Text = approve ? "已提交批准，设备状态见列表：" + request.DeviceName : "已提交拒绝：" + request.DeviceName;
            RefreshLists();
        }
        catch (Exception ex) { ActionStatus.Text = "请求处理失败（可能已过期）：" + ex.Message; }
    }
    private void RevokeClick(object sender, RoutedEventArgs e)
    {
        if (_store is null || DeviceList.SelectedItem is not PairedDevice device) { ActionStatus.Text = "请先选择设备。"; return; }
        try { _store.RevokeDevice(device.DeviceId); ActionStatus.Text = "已撤销设备：" + device.DeviceName; RefreshLists(); }
        catch (Exception ex) { ActionStatus.Text = "撤销失败：" + ex.Message; }
    }
    private void RecentChanged(object sender, SelectionChangedEventArgs e)
    {
        if (RecentList.SelectedItem is BridgeEvent item)
            NotificationDetails.Text = $"{item.AppName}\n应用 ID：{item.AppId}\n本地时间：{item.OccurredAt.ToLocalTime():yyyy-MM-dd HH:mm:ss}\n{item.Title}\n\n{item.Body}";
    }
    private async Task RefreshStartupAsync()
    {
        if (!NotificationCapture.HasPackageIdentity) { StartupCheck.IsEnabled = false; StartupStatus.Text = "开机启动需要先安装 MSIX。"; return; }
        try
        {
            var task = await StartupTask.GetAsync("Win2MobileStartup");
            StartupCheck.IsChecked = task.State == StartupTaskState.Enabled || task.State == StartupTaskState.EnabledByPolicy;
            StartupCheck.IsEnabled = task.State != StartupTaskState.DisabledByPolicy && task.State != StartupTaskState.EnabledByPolicy;
            StartupStatus.Text = task.State switch
            {
                StartupTaskState.Enabled => "登录后自动启动已启用。",
                StartupTaskState.Disabled => "登录后自动启动已关闭。",
                StartupTaskState.DisabledByUser => "已被用户在任务管理器禁用，请在系统启动应用设置中恢复。",
                StartupTaskState.EnabledByPolicy => "系统策略要求登录后自动启动。",
                StartupTaskState.DisabledByPolicy => "系统策略禁止登录后自动启动。",
                _ => "无法识别当前启动任务状态。"
            };
        }
        catch (Exception ex) { StartupCheck.IsEnabled = false; StartupStatus.Text = "无法访问启动任务：" + ex.Message; }
    }
    private async void StartupClick(object sender, RoutedEventArgs e)
    {
        try
        {
            var task = await StartupTask.GetAsync("Win2MobileStartup");
            if (StartupCheck.IsChecked == true) await task.RequestEnableAsync(); else task.Disable();
            await RefreshStartupAsync();
        }
        catch (Exception ex) { StartupStatus.Text = "启动设置失败：" + ex.Message; }
    }
}
