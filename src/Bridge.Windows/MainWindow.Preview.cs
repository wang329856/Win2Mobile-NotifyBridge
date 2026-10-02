using System;
using System.IO;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using QRCoder;
using Win2Mobile.Core;
using Win2Mobile.Transport.Lan;
namespace Win2Mobile.Windows;
public partial class MainWindow
{
    private readonly string? _previewDirectory;
    // Explicit developer preview: production views, sample data, no listener, server, credentials or settings writes.
    private async Task RenderDesignPreviewAsync()
    {
        try
        {
            string directory = Path.GetFullPath(_previewDirectory!);
            Directory.CreateDirectory(directory);
            _settings = new DesktopSettings(); DesktopTheme.Apply("Light");
            ThemeChoice.SelectedIndex = 1;
            string server = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
            var now = DateTimeOffset.Now;
            _recent.Add(new(3, "sample-calendar", server, "sample-3", "Calendar", "日历", "下一段灵感，准备出发", "今天 15:00 · 项目交流会。会议前还有 10 分钟，带上你的想法。", now));
            _recent.Add(new(2, "sample-mail", server, "sample-2", "Mail", "邮件", "新消息，已抵达手机", "设计稿已更新。你可以在手机上查看电脑的新通知，无需一直守在桌面前。", now.AddMinutes(-7)));
            _recent.Add(new(1, "sample-bridge", server, "sample-1", "Win2Mobile", "Win2Mobile", "你的私人通知桥已就绪", "同网优先直连，离开 Wi-Fi 后自动选择已授权的跨网络通道。", now.AddMinutes(-14)));
            _apps.Add(new() { AppId = "Calendar", Name = "日历", Enabled = true });
            _apps.Add(new() { AppId = "Mail", Name = "邮件", Enabled = true });
            _apps.Add(new() { AppId = "Teams", Name = "会议与协作", Enabled = false });
            _apps.Add(new() { AppId = "Win2Mobile", Name = "Win2Mobile", Enabled = true });
            DeviceList.ItemsSource = new[] { new PairedDevice("sample-device", "我的 Android 手机", now, 3) };
            _presentation.CaptureHeading = "正在连接你关心的消息";
            CaptureStatus.Text = "● 通知访问已授权 · 正在采集新的系统通知";
            ServiceStatus.Text = "局域网服务已就绪 · 加密传输";
            AuthorizeButton.Visibility = Visibility.Collapsed;
            StartupStatus.Text = "登录后自动启动已启用。";
            NtfyStatus.Text = "跨网络推送已开启 · 通知内容端到端加密";
            ActionStatus.Text = "设计预览 · 示例数据";
            UpdateOverview(0, 1);
            await Snapshot("windows-overview-light.png");
            NavigateTo("1"); await Snapshot("windows-devices-light.png");
            NavigateTo("2"); RecentList.SelectedIndex = 0; await Snapshot("windows-notifications-light.png");
            NavigateTo("3"); await Snapshot("windows-rules-light.png");
            NavigateTo("4"); await Snapshot("windows-settings-light.png");
            NavigateTo("0"); ThemeChoice.SelectedIndex = 2; DesktopTheme.Apply("Dark"); await Snapshot("windows-overview-dark.png");
            NavigateTo("4"); await Snapshot("windows-settings-dark.png");
            NavigateTo("2"); await Snapshot("windows-notifications-dark.png");
            NavigateTo("0");
            ThemeChoice.SelectedIndex = 1; DesktopTheme.Apply("Light"); PairComputerName.Text = "我的 Windows 电脑";
            PendingList.ItemsSource = new[] { new PendingPairing("sample-request", "我的 Android 手机", now.AddSeconds(120), "381926") };
            using (var data = QRCodeGenerator.GenerateQrCode("Win2Mobile design preview: not a pairing credential", QRCodeGenerator.ECCLevel.M))
            using (var qr = new PngByteQRCode(data))
            using (var stream = new MemoryStream(qr.GetGraphic(5)))
            {
                var bitmap = new BitmapImage(); bitmap.BeginInit(); bitmap.CacheOption = BitmapCacheOption.OnLoad; bitmap.StreamSource = stream; bitmap.EndInit(); bitmap.Freeze(); PairingQr.Source = bitmap;
            }
            QrStatus.Text = "二维码有效期还剩 120 秒"; PairingAddress.Text = "https://192.168.1.5:47721";
            PairPanel.Visibility = Visibility.Visible; await Snapshot("windows-pairing-light.png");
            PairPanel.Visibility = Visibility.Collapsed; Width = 800; Height = 640; await Snapshot("windows-overview-compact.png");
            _exiting = true; Application.Current.Shutdown(0);
            async Task Snapshot(string name)
            {
                await Task.Delay(350); UpdateLayout();
                var target = (FrameworkElement)Content;
                var bitmap = new RenderTargetBitmap((int)target.ActualWidth, (int)target.ActualHeight, 96, 96, PixelFormats.Pbgra32);
                bitmap.Render(target); var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
                using var output = File.Create(Path.Combine(directory, name)); encoder.Save(output);
            }
        }
        catch (Exception ex)
        {
            File.WriteAllText(Path.Combine(_previewDirectory!, "windows-preview-error.txt"), ex.ToString());
            _exiting = true; Application.Current.Shutdown(1);
        }
    }
}
