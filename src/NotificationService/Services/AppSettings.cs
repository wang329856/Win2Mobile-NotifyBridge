namespace NotificationService.Services;

public class AppSettings
{
    public const string SectionName = "AppSettings";

    /// <summary>Port for the ntfy server (default: 8080).</summary>
    public int NtfyPort { get; set; } = 8080;

    /// <summary>ntfy topic name.</summary>
    public string NtfyTopic { get; set; } = "windows-notifications";

    /// <summary>Debounce window in milliseconds (default: 300).</summary>
    public int DebounceWindowMs { get; set; } = 300;

    /// <summary>Blocked app names (case-insensitive). Notifications from these apps are silently dropped.
    /// "NotifForward" is blocked by default to prevent phone↔PC notification loops.</summary>
    public List<string> BlockedApps { get; set; } = new() { "NotifForward" };
}
