using System;
using System.Linq;
using System.Threading;
using System.Windows;
using Windows.ApplicationModel;
using Windows.ApplicationModel.Activation;

namespace Win2Mobile.Windows;

public partial class App : Application
{
    private Mutex? _instance;
    protected override void OnStartup(StartupEventArgs e)
    {
        bool startupLaunch = e.Args.Contains("--startup", StringComparer.OrdinalIgnoreCase);
        if (NotificationCapture.HasPackageIdentity)
        {
            try { startupLaunch |= AppInstance.GetActivatedEventArgs()?.Kind == ActivationKind.StartupTask; }
            catch { /* Activation details can be absent for a direct executable launch. */ }
        }
        base.OnStartup(e);
        // Local\ isolates the desktop agent to the current interactive logon session.
        _instance = new Mutex(true, "Local\\Win2Mobile.Desktop.v3", out bool created);
        if (!created)
        {
            MessageBox.Show("Win2Mobile 已在本次登录会话中运行，请从系统托盘打开。", "Win2Mobile");
            Shutdown();
            return;
        }
        var window = new MainWindow(startupLaunch);
        MainWindow = window;
        window.Show();
    }
    protected override void OnExit(ExitEventArgs e)
    {
        _instance?.Dispose();
        base.OnExit(e);
    }
}
