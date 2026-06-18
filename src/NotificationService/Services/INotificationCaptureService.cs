using NotificationService.Models;

namespace NotificationService.Services;

/// <summary>
/// Service that captures Windows notifications via Win32 event hooks.
/// </summary>
public interface INotificationCaptureService
{
    /// <summary>
    /// Start capturing Windows notifications.
    /// </summary>
    Task StartAsync(CancellationToken cancellationToken = default);

    /// <summary>
    /// Stop capturing notifications.
    /// </summary>
    Task StopAsync(CancellationToken cancellationToken = default);

    /// <summary>
    /// Raised when a notification is captured. The subscriber receives the NotificationData.
    /// </summary>
    event EventHandler<NotificationData>? NotificationCaptured;
}
