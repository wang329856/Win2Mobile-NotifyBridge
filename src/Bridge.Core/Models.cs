namespace Win2Mobile.Core;

public record CapturedNotification(string SourceNotificationId, string AppId, string AppName, string Title, string Body, DateTimeOffset OccurredAt);
public record BridgeEvent(long Sequence, string EventId, string SourceDeviceId, string SourceNotificationId, string AppId, string AppName, string Title, string Body, DateTimeOffset OccurredAt);
public record ServerIdentity(string ServerId, string ServerName);
public record PairedDevice(string DeviceId, string DeviceName, DateTimeOffset CreatedAt, long AcknowledgedSequence);
public record DeviceCredential(string DeviceId, string AccessToken);
public sealed class DeviceRevokedEventArgs(string deviceId) : EventArgs { public string DeviceId { get; } = deviceId; }
