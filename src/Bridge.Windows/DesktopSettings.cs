using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;

namespace Win2Mobile.Windows;

internal sealed class DesktopSettings
{
    public HashSet<string> BlockedAppIds { get; set; } = new(StringComparer.Ordinal);
    public string? SelectedAddress { get; set; }
    public bool Paused { get; set; }
    public bool NtfyEnabled { get; set; }
    public string NtfyServerUrl { get; set; } = "https://ntfy.sh";
    public string NtfyProxyUrl { get; set; } = "";
    public static string DataDirectory => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Win2Mobile");
    private static string SettingsPath => Path.Combine(DataDirectory, "desktop-settings.json");
    public static DesktopSettings Load()
    {
        Directory.CreateDirectory(DataDirectory);
        if (!File.Exists(SettingsPath)) return new();
        try { return JsonSerializer.Deserialize<DesktopSettings>(File.ReadAllText(SettingsPath)) ?? new(); }
        catch (JsonException) { throw new InvalidDataException("桌面配置已损坏。请备份并修复用户目录中的 desktop-settings.json 后重新启动。"); }
    }
    public void Save()
    {
        Directory.CreateDirectory(DataDirectory);
        var temporary = SettingsPath + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        File.Move(temporary, SettingsPath, true);
    }
}
