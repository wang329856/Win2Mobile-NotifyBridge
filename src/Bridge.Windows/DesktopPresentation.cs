using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using Microsoft.Win32;
namespace Win2Mobile.Windows;
public class ObservablePresentation : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;
    protected bool Set<T>(ref T field, T value, [CallerMemberName] string? name = null) { if (EqualityComparer<T>.Default.Equals(field, value)) return false; field = value; PropertyChanged?.Invoke(this, new(name)); return true; }
}
public sealed class DesktopPresentation : ObservablePresentation
{
    private string _deviceSummary = "尚未连接手机", _queueSummary = "0 条", _captureHeading = "正在准备通知桥";
    public string DeviceSummary { get => _deviceSummary; set => Set(ref _deviceSummary, value); }
    public string QueueSummary { get => _queueSummary; set => Set(ref _queueSummary, value); }
    public string CaptureHeading { get => _captureHeading; set => Set(ref _captureHeading, value); }
    public ICommand Navigate { get; }
    public DesktopPresentation(Action<string> navigate) { Navigate = new UiCommand(p => navigate(p?.ToString() ?? "0")); }
}
public sealed class UiCommand(Action<object?> execute) : ICommand
{
    public bool CanExecute(object? parameter) => true;
    public void Execute(object? parameter) => execute(parameter);
    public event EventHandler? CanExecuteChanged { add { } remove { } }
}
internal static class DesktopTheme
{
    public static void Apply(string mode)
    {
        bool dark = mode == "Dark" || (mode == "System" && (Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize", "AppsUseLightTheme", 1) as int? ?? 1) == 0);
        var colors = dark
            ? new[] { "#12131B", "#1D1F2A", "#282B39", "#F0F0FA", "#B1B3C6", "#36394B", "#BFC2FF", "#333052", "#242144", "#6CDCC9", "#F0C27D", "#FFADB7" }
            : new[] { "#F5F5FA", "#FFFFFF", "#EEEDF8", "#202132", "#65677B", "#E4E3EE", "#635BDF", "#E9E6FF", "#FFFFFF", "#087D72", "#9C5B08", "#BA344A" };
        var keys = new[] { "CanvasBrush", "SurfaceBrush", "SoftBrush", "TextBrush", "MutedBrush", "StrokeBrush", "AccentBrush", "AccentSoftBrush", "OnAccentBrush", "SuccessBrush", "WarningBrush", "DangerBrush" };
        for (int i = 0; i < keys.Length; i++) Application.Current.Resources[keys[i]] = new SolidColorBrush((Color)ColorConverter.ConvertFromString(colors[i]));
        if (SystemParameters.HighContrast)
        {
            foreach (var key in new[] { "CanvasBrush", "SurfaceBrush", "SoftBrush", "AccentSoftBrush" }) Application.Current.Resources[key] = SystemColors.WindowBrush;
            foreach (var key in new[] { "TextBrush", "MutedBrush", "SuccessBrush", "WarningBrush", "DangerBrush", "StrokeBrush" }) Application.Current.Resources[key] = SystemColors.WindowTextBrush;
            Application.Current.Resources["AccentBrush"] = SystemColors.HighlightBrush;
            Application.Current.Resources["OnAccentBrush"] = SystemColors.HighlightTextBrush;
        }
    }
}
