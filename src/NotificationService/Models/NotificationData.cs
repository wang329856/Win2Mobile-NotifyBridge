using System.Text.Json.Serialization;

namespace NotificationService.Models;

/// <summary>
/// Represents a captured Windows notification to be forwarded to clients.
/// </summary>
public class NotificationData
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = "notification";

    [JsonPropertyName("id")]
    public string Id { get; set; } = Guid.NewGuid().ToString("N");

    [JsonPropertyName("data")]
    public NotificationPayload Data { get; set; } = new();

    public static NotificationData Create(string title, string body, string appName)
    {
        return new NotificationData
        {
            Data = new NotificationPayload
            {
                Title = title ?? "",
                Body = body ?? "",
                AppName = appName ?? "Unknown",
                Timestamp = DateTime.UtcNow.ToString("o")
            }
        };
    }
}

public class NotificationPayload
{
    [JsonPropertyName("title")]
    public string Title { get; set; } = "";

    [JsonPropertyName("body")]
    public string Body { get; set; } = "";

    [JsonPropertyName("appName")]
    public string AppName { get; set; } = "Unknown";

    [JsonPropertyName("timestamp")]
    public string Timestamp { get; set; } = "";
}

/// <summary>
/// Heartbeat ping message sent periodically to keep connections alive.
/// </summary>
public class PingMessage
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = "ping";

    [JsonPropertyName("timestamp")]
    public string Timestamp { get; set; } = DateTime.UtcNow.ToString("o");
}

/// <summary>
/// Server info response sent when a client requests server details.
/// </summary>
public class ServerInfoMessage
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = "server_info";

    [JsonPropertyName("machineName")]
    public string MachineName { get; set; } = Environment.MachineName;

    [JsonPropertyName("version")]
    public string Version { get; set; } = "1.0.0";

    [JsonPropertyName("clientCount")]
    public int ClientCount { get; set; }
}
