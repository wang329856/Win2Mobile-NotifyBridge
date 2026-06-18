using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using NotificationService.Models;

namespace NotificationService.Services;

/// <summary>
/// Forwards notifications to the local ntfy server via HTTP POST.
/// ntfy URL: http://localhost:{port}/{topic}
/// </summary>
public class NtfyForwarderService
{
    private readonly ILogger<NtfyForwarderService> _logger;
    private readonly AppSettings _settings;
    private readonly HttpClient _http;

    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public NtfyForwarderService(ILogger<NtfyForwarderService> logger, IOptions<AppSettings> settings)
    {
        _logger = logger;
        _settings = settings.Value;
        _http = new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
    }

    /// <summary>
    /// Send a notification to the ntfy server.
    /// </summary>
    public async Task SendAsync(NotificationData data)
    {
        try
        {
            var url = $"http://localhost:{_settings.NtfyPort}/{_settings.NtfyTopic}";

            // Build ntfy message
            var payload = new
            {
                topic = _settings.NtfyTopic,
                title = $"[{data.Data.AppName}] {data.Data.Title}",
                message = data.Data.Body,
                priority = 3,
                tags = new[] { "computer" }
            };

            var json = JsonSerializer.Serialize(payload, JsonOpts);
            var content = new StringContent(json, Encoding.UTF8, "application/json");

            var resp = await _http.PostAsync(url, content);
            resp.EnsureSuccessStatusCode();

            _logger.LogInformation("Forwarded to ntfy: [{App}] {Title}", data.Data.AppName, data.Data.Title);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to forward to ntfy");
        }
    }
}
