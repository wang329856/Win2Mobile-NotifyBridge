using System;
using System.ComponentModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Input;
using Microsoft.Win32;
using Win2Mobile.Core;
using Win2Mobile.Transport.Lan;
namespace Win2Mobile.Windows;
public partial class MainWindow
{
    private DesktopPresentation _presentation = null!;
    private ICollectionView _notificationView = null!, _appView = null!;
    private bool _filterBatch, _ntfyApplying, _remoteMode;
    private Button? _pressedButton;
    private void InitializePresentation()
    {
        _presentation = new DesktopPresentation(NavigateTo);
        DataContext = _presentation;
        _notificationView = CollectionViewSource.GetDefaultView(_recent);
        RecentList.ItemsSource = _notificationView;
        _appView = CollectionViewSource.GetDefaultView(_apps);
        AppList.ItemsSource = _appView;
        NavigateTo("0");
        SystemEvents.UserPreferenceChanged += SystemThemeChanged;
    }
    private void RestorePresentation()
    {
        DesktopTheme.Apply(_settings?.ThemeMode ?? "System");
        foreach (ComboBoxItem item in ThemeChoice.Items) if ((string)item.Tag == _settings?.ThemeMode) ThemeChoice.SelectedItem = item;
    }
    private void SystemThemeChanged(object sender, UserPreferenceChangedEventArgs e) => Dispatcher.BeginInvoke(() => DesktopTheme.Apply(_settings?.ThemeMode ?? "System"));
    private void NavigateTo(string value)
    {
        if (!int.TryParse(value, out int index) || index is < 0 or > 4) return;
        FrameworkElement[] pages = { OverviewPage, DevicesPage, NoticesPage, RulesPage, SettingsPage };
        Button[] buttons = { NavOverview, NavDevices, NavNotices, NavRules, NavSettings };
        for (int i = 0; i < pages.Length; i++) { pages[i].Visibility = i == index ? Visibility.Visible : Visibility.Collapsed; buttons[i].SetResourceReference(Control.BackgroundProperty, i == index ? "AccentSoftBrush" : "SurfaceBrush"); }
        PageTitle.Text = new[] { "让通知，跟上你的节奏", "你的设备", "电脑上的新消息", "只转发你需要的", "按你的习惯运行" }[index];
        PageSubtitle.Text = new[] { "电脑上的重要消息，随时在手机查看。", "私人设备，明确授权。", "短期队列，帮助断网后恢复本次接收。", "选择通知来源，减少手机上的干扰。", "外观、后台与网络，一处管理。" }[index];
        if (SystemParameters.ClientAreaAnimation && !SystemParameters.HighContrast) PageHost.BeginAnimation(OpacityProperty, new DoubleAnimation(0.35, 1, TimeSpan.FromMilliseconds(220)));
    }
    private void LayoutChanged(object sender, SizeChangedEventArgs e)
    {
        if (SidebarColumn is null || NotificationSplit is null) return;
        bool narrow = ActualWidth < 1000;
        SidebarColumn.Width = new GridLength(narrow ? 76 : 200);
        foreach (var item in new FrameworkElement[] { BrandName, BrandHint, SidebarHint, LabelOverview, LabelDevices, LabelNotices, LabelRules, LabelSettings }) item.Visibility = narrow ? Visibility.Collapsed : Visibility.Visible;
        Grid.SetColumn(QueueSummaryCard, narrow ? 0 : 1); Grid.SetRow(QueueSummaryCard, narrow ? 1 : 0);
        QueueSummaryCard.Margin = narrow ? new Thickness(0, 12, 0, 0) : new Thickness(9, 0, 0, 0);
        DevicesSummaryCard.Margin = narrow ? new Thickness(0) : new Thickness(0, 0, 9, 0);
        Grid.SetColumnSpan(DevicesSummaryCard, narrow ? 2 : 1); Grid.SetColumnSpan(QueueSummaryCard, narrow ? 2 : 1);
        Grid.SetColumn(DetailCard, narrow ? 0 : 1); Grid.SetRow(DetailCard, narrow ? 1 : 0);
        Grid.SetColumnSpan(DetailCard, narrow ? 2 : 1);
        DetailColumn.Width = new GridLength(narrow ? 0 : 320);
        DetailRow.Height = new GridLength(narrow ? 210 : 0);
    }
    private void UpdateCaptureStatus(string status)
    {
        CaptureStatus.Text = status;
        _presentation.CaptureHeading = !NotificationCapture.HasPackageIdentity ? "便携运行，随时连接手机" : _settings?.Paused == true ? "采集已暂停，随时继续" : _capture?.HasAccess == true ? "正在连接你关心的消息" : "先授权，再开始同步";
        AuthorizeButton.Visibility = _capture?.HasAccess == true ? Visibility.Collapsed : Visibility.Visible;
    }
    private void UpdateOverview(int pending, int devices)
    {
        _presentation.DeviceSummary = devices == 0 ? "尚未连接手机" : devices + " 部已授权手机";
        _presentation.QueueSummary = _recent.Count + " 条通知";
        PendingBanner.Visibility = pending > 0 ? Visibility.Visible : Visibility.Collapsed;
        PendingBannerText.Text = pending + " 个配对请求 · 点击核验并批准";
        OverviewEmpty.Visibility = _recent.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        OverviewRecent.ItemsSource = _recent.Take(4).ToArray();
    }
    private void OpenPairClick(object sender, RoutedEventArgs e)
    {
        PairPanel.Visibility = Visibility.Visible;
        if (!_remoteMode && (_qrExpires <= DateTimeOffset.UtcNow || PairingQr.Source is null) && _host?.Pairing.PendingRequests.Count == 0) RefreshPairing();
        ClosePairButtonFocus();
    }
    private void ClosePairButtonFocus() => LanModeButton.Focus();
    private void ClosePairClick(object sender, RoutedEventArgs e) { PairPanel.Visibility = Visibility.Collapsed; NavDevices.Focus(); }
    private async void LanModeClick(object sender, RoutedEventArgs e)
    {
        if (_host?.Pairing.PendingRequests.Count > 0) { ActionStatus.Text = "请先处理当前配对请求，再切换连接方式。"; return; }
        if (_remotePairing is not null) { await _remotePairing.DisposeAsync(); _remotePairing = null; _remotePayload = null; }
        _remoteMode = false; LanPairSection.Visibility = Visibility.Visible; RemotePairSection.Visibility = Visibility.Collapsed;
        LanModeButton.SetResourceReference(BackgroundProperty, "AccentBrush"); LanModeButton.SetResourceReference(ForegroundProperty, "OnAccentBrush");
        RemoteModeButton.SetResourceReference(BackgroundProperty, "SoftBrush"); RemoteModeButton.SetResourceReference(ForegroundProperty, "TextBrush"); RefreshPairing();
    }
    private void RemoteModeClick(object sender, RoutedEventArgs e)
    {
        if (_host?.Pairing.PendingRequests.Count > 0) { ActionStatus.Text = "请先处理当前配对请求，再切换连接方式。"; return; }
        _remoteMode = true; LanPairSection.Visibility = Visibility.Collapsed; RemotePairSection.Visibility = Visibility.Visible;
        RemoteModeButton.SetResourceReference(BackgroundProperty, "AccentBrush"); RemoteModeButton.SetResourceReference(ForegroundProperty, "OnAccentBrush");
        LanModeButton.SetResourceReference(BackgroundProperty, "SoftBrush"); LanModeButton.SetResourceReference(ForegroundProperty, "TextBrush");
    }
    private void ApproveRowClick(object sender, RoutedEventArgs e) => RespondToPairing(true, (sender as FrameworkElement)?.DataContext as PendingPairing);
    private void DenyRowClick(object sender, RoutedEventArgs e) => RespondToPairing(false, (sender as FrameworkElement)?.DataContext as PendingPairing);
    private void RevokeRowClick(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is not PairedDevice device) return;
        if (MessageBox.Show("撤销设备 " + device.DeviceName + " 的授权？后续通知将不再发送，重新连接需要再次批准。", "撤销授权", MessageBoxButton.OKCancel, MessageBoxImage.Warning) != MessageBoxResult.OK) return;
        DeviceList.SelectedItem = device; RevokeClick(sender, e);
    }
    private void NoticeSearchChanged(object sender, TextChangedEventArgs e)
    {
        if (_notificationView is null) return;
        string query = NoticeSearch.Text.Trim();
        _notificationView.Filter = item => item is BridgeEvent n && (n.AppName + "\n" + n.Title + "\n" + n.Body).Contains(query, StringComparison.OrdinalIgnoreCase);
    }
    private void RuleSearchChanged(object sender, TextChangedEventArgs e)
    {
        if (_appView is null) return;
        string query = RuleSearch.Text.Trim(); _appView.Filter = item => item is AppFilter f && (f.Name + f.AppId).Contains(query, StringComparison.OrdinalIgnoreCase);
    }
    private void BulkFilterClick(object sender, RoutedEventArgs e)
    {
        if (_settings is null) return;
        bool enabled = (sender as Button)?.Tag?.ToString() == "True";
        _filterBatch = true;
        try { foreach (var app in _apps) { app.Enabled = enabled; if (enabled) _settings.BlockedAppIds.Remove(app.AppId); else _settings.BlockedAppIds.Add(app.AppId); } }
        finally { _filterBatch = false; }
        SaveSettings(); ActionStatus.Text = enabled ? "已允许全部通知来源。" : "已关闭全部已知来源；新出现的来源默认允许。";
    }
    private void ThemeChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_settings is null || ThemeChoice.SelectedItem is not ComboBoxItem item) return;
        _settings.ThemeMode = item.Tag.ToString()!; DesktopTheme.Apply(_settings.ThemeMode); SaveSettings();
    }
    private async void NtfyToggleClick(object sender, RoutedEventArgs e) => await ApplyNtfyAsync();
    private void OverviewRecentChanged(object sender, SelectionChangedEventArgs e)
    {
        if (OverviewRecent.SelectedItem is not BridgeEvent item) return;
        NavigateTo("2"); RecentList.SelectedItem = item;
    }
    private void CopyNoticeClick(object sender, RoutedEventArgs e)
    {
        if (RecentList.SelectedItem is not BridgeEvent) return;
        try { Clipboard.SetText(NotificationDetails.Text); ActionStatus.Text = "通知内容已复制。"; } catch { ActionStatus.Text = "剪贴板暂时不可用，请重试。"; }
    }
    private void WindowKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape && PairPanel.Visibility == Visibility.Visible) { ClosePairClick(sender, new RoutedEventArgs()); e.Handled = true; }
        if (e.Key == Key.F && Keyboard.Modifiers == ModifierKeys.Control && PairPanel.Visibility != Visibility.Visible) { NavigateTo("2"); NoticeSearch.Focus(); e.Handled = true; }
    }
    private void ButtonPressFeedback(object sender, MouseButtonEventArgs e)
    {
        DependencyObject? origin = e.OriginalSource as DependencyObject;
        while (origin is not null && origin is not Button) origin = origin is Visual ? VisualTreeHelper.GetParent(origin) : null;
        _pressedButton = origin as Button;
        AnimateButton(_pressedButton, 0.97, 100);
    }
    private void ButtonReleaseFeedback(object sender, MouseButtonEventArgs e)
    {
        AnimateButton(_pressedButton, 1, 200); _pressedButton = null;
    }
    private void AnimateButton(DependencyObject? origin, double scale, int milliseconds)
    {
        if (!SystemParameters.ClientAreaAnimation || SystemParameters.HighContrast) return;
        while (origin is not null && origin is not Button) origin = origin is Visual ? VisualTreeHelper.GetParent(origin) : null;
        if (origin is not Button button) return;
        if (button.RenderTransform is not ScaleTransform) { button.RenderTransform = new ScaleTransform(1, 1); button.RenderTransformOrigin = new Point(0.5, 0.5); }
        var transform = (ScaleTransform)button.RenderTransform;
        transform.BeginAnimation(ScaleTransform.ScaleXProperty, new DoubleAnimation(scale, TimeSpan.FromMilliseconds(milliseconds)));
        transform.BeginAnimation(ScaleTransform.ScaleYProperty, new DoubleAnimation(scale, TimeSpan.FromMilliseconds(milliseconds)));
    }
}
