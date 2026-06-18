using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using NotificationService.Services;
using Serilog;
using Serilog.Extensions.Logging;
using System.Text.Json;

namespace NotificationService;

public class MainForm : Form
{
    private NotifyIcon? _trayIcon;
    private readonly NtfyServerService _ntfyServer;
    private readonly INotificationCaptureService _capture;
    private readonly AppSettings _settings;
    private int _messageCount;

    public MainForm()
    {
        // Load settings
        _settings = new AppSettings();
        try
        {
            var configPath = AppContext.BaseDirectory;
            var config = new ConfigurationBuilder()
                .SetBasePath(configPath)
                .AddJsonFile("appsettings.json", optional: true)
                .Build();
            config.GetSection("AppSettings").Bind(_settings);
        }
        catch (Exception ex)
        {
            Log.Warning(ex, "Failed to load config, using defaults");
        }

        // Build services
        var services = new ServiceCollection();
        services.AddLogging(b => b.AddSerilog(Log.Logger, dispose: true));
        services.AddSingleton(Options.Create(_settings));
        services.AddSingleton<INotificationCaptureService, NotificationCaptureService>();
        services.AddSingleton<NtfyServerService>();
        var sp = services.BuildServiceProvider();

        _ntfyServer = sp.GetRequiredService<NtfyServerService>();
        _capture = sp.GetRequiredService<INotificationCaptureService>();

        Log.Information("NotifForward GUI starting...");

        SetupTray();
        _ = StartServicesAsync();
    }

    private async Task StartServicesAsync()
    {
        try
        {
            await _ntfyServer.StartAsync(_settings.NtfyPort);

            _capture.NotificationCaptured += (_, data) =>
            {
                _ntfyServer.PublishFromCode(data.Data.Title, data.Data.Body, data.Data.AppName);
                Interlocked.Increment(ref _messageCount);
                BeginInvoke(() => UpdateTrayText());
            };

            await _capture.StartAsync();
        }
        catch (Exception ex)
        {
            Log.Error(ex, "Failed to start services");
            MessageBox.Show($"Failed to start: {ex.Message}", "Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void SetupTray()
    {
        var menu = new ContextMenuStrip();

        menu.Items.Add(new ToolStripMenuItem("● Service Running") { Enabled = false });
        menu.Items.Add(new ToolStripSeparator());

        menu.Items.Add(new ToolStripMenuItem("Show Window", null, (_, _) =>
        {
            Show(); WindowState = FormWindowState.Normal;
        }));
        menu.Items.Add(new ToolStripMenuItem("Exit", null, (_, _) =>
        {
            _trayIcon?.Dispose();
            _ntfyServer.Stop();
            Application.Exit();
        }));

        // Load shared icon from embedded resource (works in single-file publish too)
        Icon appIcon;
        try
        {
            using var stream = typeof(MainForm).Assembly.GetManifestResourceStream("NotificationService.Resources.app.ico");
            appIcon = stream != null ? new Icon(stream) : SystemIcons.Application;
        }
        catch { appIcon = SystemIcons.Application; }

        _trayIcon = new NotifyIcon
        {
            Text = "NotifForward",
            ContextMenuStrip = menu,
            Icon = appIcon,
            Visible = true
        };

        // Set form icon (taskbar)
        Icon = appIcon;

        // Form setup
        Text = "NotifForward - Windows Notification Forwarder";
        Size = new Size(420, 280);
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;

        var info = new RichTextBox
        {
            Dock = DockStyle.Fill,
            ReadOnly = true,
            Font = new Font("Consolas", 10),
            Text = $"  ╔══════════════════════════════╗\r\n" +
                   $"  ║   NotifForward v2          ║\r\n" +
                   $"  ╠══════════════════════════════╣\r\n" +
                   $"  ║ Server : http://{GetLocalIp()}:{_settings.NtfyPort}\r\n" +
                   $"  ║ Topic  : {_settings.NtfyTopic}\r\n" +
                   $"  ║ Status : Running\r\n" +
                   $"  ║ Blocked: {_settings.BlockedApps.Count} apps\r\n" +
                   $"  ╠══════════════════════════════╣\r\n" +
                   $"  ║ Close window = minimize     ║\r\n" +
                   $"  ║ Right-click tray = menu     ║\r\n" +
                   $"  ╚══════════════════════════════╝\r\n"
        };
        info.BackColor = Color.FromArgb(30, 30, 30);
        info.ForeColor = Color.LightGreen;

        Controls.Add(info);

        FormClosing += (_, e) =>
        {
            if (e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                Hide();
            }
        };
    }

    private void UpdateTrayText()
    {
        if (_trayIcon != null)
            _trayIcon.Text = $"NotifForward\nNotifications: {_messageCount}";
    }

    private static string GetLocalIp()
    {
        try
        {
            var host = System.Net.Dns.GetHostEntry(System.Net.Dns.GetHostName());
            foreach (var ip in host.AddressList)
                if (ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && !ip.ToString().StartsWith("127."))
                    return ip.ToString();
        }
        catch { }
        return "127.0.0.1";
    }
}
