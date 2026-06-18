using System.Collections.Concurrent;
using System.Net;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using NotificationService.Models;

namespace NotificationService.Services;

/// <summary>
/// Minimal ntfy-protocol compatible HTTP server.
/// Supports:
///   POST /{topic}       - Publish a notification
///   GET  /{topic}/json  - Subscribe via long-polling (with ?poll=1)
/// </summary>
public class NtfyServerService : IDisposable
{
    private readonly ILogger<NtfyServerService> _logger;
    private readonly AppSettings _settings;
    private HttpListener? _listener;
    private bool _disposed;

    private readonly ConcurrentDictionary<string, ConcurrentQueue<PendingSubscriber>> _subscribers = new();
    private readonly ConcurrentDictionary<string, string> _latestMessages = new();
    private readonly List<(string time, string title, string message, string topic)> _history = new();
    private int _messageCount;
    private int _messageCounter;

    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public NtfyServerService(ILogger<NtfyServerService> logger, IOptions<AppSettings> settings)
    {
        _logger = logger;
        _settings = settings.Value;
    }

    public Task StartAsync(int port)
    {
        _listener = new HttpListener();
        _listener.Prefixes.Add($"http://+:{port}/");

        try
        {
            _listener.Start();
            _logger.LogInformation("ntfy server listening on port {Port} (all interfaces)", port);
            _ = ProcessRequests();
        }
        catch (HttpListenerException ex) when (ex.ErrorCode == 5)
        {
            _listener.Close();
            _logger.LogError("ntfy server needs admin rights. Run once as admin: netsh http add urlacl url=http://+:{Port}/ user=Everyone", port);
            throw;
        }

        return Task.CompletedTask;
    }

    private async Task ProcessRequests()
    {
        while (_listener != null && _listener.IsListening)
        {
            try
            {
                var ctx = await _listener.GetContextAsync();
                _ = Task.Run(() => HandleRequest(ctx));
            }
            catch (HttpListenerException) { break; }
            catch (ObjectDisposedException) { break; }
        }
    }

    private async Task HandleRequest(HttpListenerContext ctx)
    {
        try
        {
            var path = ctx.Request.Url!.AbsolutePath.TrimStart('/');
            var method = ctx.Request.HttpMethod;

            ctx.Response.Headers.Add("Access-Control-Allow-Origin", "*");
            ctx.Response.Headers.Add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");

            if (method == "OPTIONS")
            {
                ctx.Response.StatusCode = 204;
                ctx.Response.Close();
                return;
            }

            if (method == "POST" && !string.IsNullOrEmpty(path))
            {
                await HandlePublish(ctx, path);
            }
            else if (method == "GET" && path.EndsWith("/latest"))
            {
                var topic = path.Substring(0, path.Length - 7);
                await HandleLatest(ctx, topic);
            }
            else if (method == "GET" && path.EndsWith("/json"))
            {
                var topic = path.Substring(0, path.Length - 5);
                await HandleSubscribe(ctx, topic);
            }
            else if (path == "")
            {
                await ServeDashboard(ctx);
            }
            else if (path.StartsWith("api/"))
            {
                await ServeApi(ctx, path.Substring(4));
            }
            else
            {
                ctx.Response.StatusCode = 404;
                ctx.Response.Close();
            }
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Error handling request");
            try { ctx.Response.StatusCode = 500; ctx.Response.Close(); } catch { }
        }
    }

    private async Task HandlePublish(HttpListenerContext ctx, string topic)
    {
        using var reader = new StreamReader(ctx.Request.InputStream, Encoding.UTF8);
        var body = await reader.ReadToEndAsync();

        var title = "";
        var message = "";

        try
        {
            using var doc = JsonDocument.Parse(body);
            var root = doc.RootElement;
            title = root.TryGetProperty("title", out var t) ? t.GetString() ?? "" : "";
            message = root.TryGetProperty("message", out var m) ? m.GetString() ?? "" : "";
        }
        catch { message = body; }

        var id = Interlocked.Increment(ref _messageCounter).ToString();
        var timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds();

        // Check blacklist
        var appName = ExtractAppName(title);
        if (_settings.BlockedApps.Any(b => string.Equals(b, appName, StringComparison.OrdinalIgnoreCase)))
        {
            _logger.LogInformation("Blocked POST: [{App}]", appName);
            ctx.Response.StatusCode = 200;
            var blockedResp = Encoding.UTF8.GetBytes("{\"status\":\"blocked\"}");
            ctx.Response.ContentType = "application/json";
            ctx.Response.ContentLength64 = blockedResp.Length;
            await ctx.Response.OutputStream.WriteAsync(blockedResp);
            ctx.Response.Close();
            return;
        }

        var fullMsg = JsonSerializer.Serialize(new
        {
            id,
            time = timestamp,
            title,
            message,
            topic,
        }, JsonOpts);

        _latestMessages[topic] = fullMsg;

        lock (_history)
        {
            _history.Insert(0, (DateTime.Now.ToString("HH:mm:ss"), title, message, topic));
            if (_history.Count > 50) _history.RemoveAt(_history.Count - 1);
        }
        Interlocked.Increment(ref _messageCount);

        NotifySubscribers(topic, fullMsg);

        var respBuf = Encoding.UTF8.GetBytes(fullMsg);
        ctx.Response.ContentType = "application/json; charset=utf-8";
        ctx.Response.ContentLength64 = respBuf.Length;
        await ctx.Response.OutputStream.WriteAsync(respBuf);
        ctx.Response.Close();

        _logger.LogInformation("Published to {Topic}: {Title}", topic, title);
    }

    private async Task HandleSubscribe(HttpListenerContext ctx, string topic)
    {
        var isPolling = ctx.Request.QueryString["poll"] == "1";

        if (isPolling)
        {
            var tcs = new TaskCompletionSource<string>();
            var cts = new CancellationTokenSource(TimeSpan.FromSeconds(120));

            var subscriber = new PendingSubscriber(tcs);
            var queue = _subscribers.GetOrAdd(topic, _ => new ConcurrentQueue<PendingSubscriber>());
            queue.Enqueue(subscriber);

            cts.Token.Register(() => tcs.TrySetResult("[]"));

            try
            {
                var result = await tcs.Task;
                var buf = Encoding.UTF8.GetBytes(result);
                ctx.Response.ContentType = "application/json";
                ctx.Response.ContentLength64 = buf.Length;
                await ctx.Response.OutputStream.WriteAsync(buf);
            }
            catch { }
        }
        else
        {
            ctx.Response.ContentType = "text/event-stream";
            ctx.Response.Headers.Add("Cache-Control", "no-cache");
            var output = ctx.Response.OutputStream;

            var subscriber = new SseSubscriber(output);
            var queue = _subscribers.GetOrAdd(topic, _ => new ConcurrentQueue<PendingSubscriber>());
            queue.Enqueue(subscriber);

            try { await Task.Delay(Timeout.Infinite); }
            catch { }
        }

        ctx.Response.Close();
    }

    private async Task HandleLatest(HttpListenerContext ctx, string topic)
    {
        if (_latestMessages.TryGetValue(topic, out var msg))
        {
            var buf = Encoding.UTF8.GetBytes(msg);
            ctx.Response.ContentType = "application/json; charset=utf-8";
            ctx.Response.ContentLength64 = buf.Length;
            await ctx.Response.OutputStream.WriteAsync(buf);
        }
        else
        {
            var buf = Encoding.UTF8.GetBytes("{}");
            ctx.Response.ContentType = "application/json; charset=utf-8";
            ctx.Response.ContentLength64 = buf.Length;
            await ctx.Response.OutputStream.WriteAsync(buf);
        }
        ctx.Response.Close();
    }

    private async Task ServeDashboard(HttpListenerContext ctx)
    {
        var html = GetDashboardHtml();
        var buf = Encoding.UTF8.GetBytes(html);
        ctx.Response.ContentType = "text/html; charset=utf-8";
        ctx.Response.ContentLength64 = buf.Length;
        await ctx.Response.OutputStream.WriteAsync(buf);
        ctx.Response.Close();
    }

    private async Task ServeApi(HttpListenerContext ctx, string action)
    {
        ctx.Response.ContentType = "application/json; charset=utf-8";
        string json;

        if (action == "status")
        {
            List<object> history;
            lock (_history) { history = _history.Select(h => new { h.time, h.title, h.message, h.topic }).ToList<object>(); }
            json = JsonSerializer.Serialize(new
            {
                port = _settings.NtfyPort,
                topic = _settings.NtfyTopic,
                messageCount = _messageCount,
                blockedApps = _settings.BlockedApps,
                messages = history
            }, JsonOpts);
        }
        else if (action.StartsWith("blacklist/add?name="))
        {
            var name = Uri.UnescapeDataString(action.Substring(19));
            if (!_settings.BlockedApps.Contains(name, StringComparer.OrdinalIgnoreCase))
            {
                _settings.BlockedApps.Add(name);
                SaveSettings();
            }
            json = JsonSerializer.Serialize(new { ok = true, blockedApps = _settings.BlockedApps });
        }
        else if (action.StartsWith("blacklist/remove?name="))
        {
            var name = Uri.UnescapeDataString(action.Substring(22));
            _settings.BlockedApps.RemoveAll(b => b.Equals(name, StringComparison.OrdinalIgnoreCase));
            SaveSettings();
            json = JsonSerializer.Serialize(new { ok = true, blockedApps = _settings.BlockedApps });
        }
        else if (action == "test")
        {
            PublishFromCode("Test Notification", "This is a test from Web Dashboard", "NotifForward");
            json = "{\"ok\":true}";
        }
        else
        {
            json = "{\"error\":\"unknown action\"}";
        }

        var buf = Encoding.UTF8.GetBytes(json);
        ctx.Response.ContentLength64 = buf.Length;
        await ctx.Response.OutputStream.WriteAsync(buf);
        ctx.Response.Close();
    }

    private void SaveSettings()
    {
        try
        {
            var path = Path.Combine(AppContext.BaseDirectory, "appsettings.json");
            var json = JsonSerializer.Serialize(new { AppSettings = _settings }, new JsonSerializerOptions { WriteIndented = true });
            File.WriteAllText(path, json);
            _logger.LogDebug("Settings saved to {Path}", path);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to save settings");
        }
    }

    private string GetDashboardHtml() => @"<!DOCTYPE html>
<html lang=""zh-CN"">
<head>
<meta charset=""UTF-8""><meta name=""viewport"" content=""width=device-width,initial-scale=1"">
<title>NotifForward Dashboard</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;background:#0d1117;color:#c9d1d9;min-height:100vh}
.header{background:#161b22;border-bottom:1px solid #30363d;padding:12px 24px;display:flex;align-items:center;justify-content:space-between}
.header h1{font-size:18px;color:#58a6ff}
.header .dot{width:10px;height:10px;background:#3fb950;border-radius:50%;display:inline-block;margin-right:8px}
.main{max-width:900px;margin:0 auto;padding:24px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:16px;margin-bottom:24px}
.card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:16px}
.card .label{font-size:11px;color:#8b949e;text-transform:uppercase;margin-bottom:4px}
.card .value{font-size:22px;font-weight:600;color:#f0f6fc}
.section{margin-bottom:24px}
.section h2{font-size:14px;color:#8b949e;margin-bottom:12px;text-transform:uppercase;letter-spacing:1px}
.blocklist{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px}
.tag{background:#1f2937;border:1px solid #374151;border-radius:16px;padding:4px 12px;font-size:13px;display:flex;align-items:center;gap:8px}
.tag button{background:none;border:none;color:#f85149;cursor:pointer;font-size:16px;line-height:1}
.add-form{display:flex;gap:8px}
.add-form input{flex:1;background:#0d1117;border:1px solid #30363d;border-radius:8px;padding:8px 12px;color:#c9d1d9;font-size:13px;outline:none}
.add-form input:focus{border-color:#58a6ff}
.btn{background:#238636;color:#fff;border:none;border-radius:8px;padding:8px 16px;cursor:pointer;font-size:13px;white-space:nowrap}
.btn:hover{background:#2ea043}
.btn-red{background:#da3633}
.btn-red:hover{background:#f85149}
.msg-list{max-height:400px;overflow-y:auto}
.msg-item{display:grid;grid-template-columns:60px 1fr;gap:12px;padding:10px 0;border-bottom:1px solid #21262d;font-size:13px}
.msg-item .time{color:#8b949e}
.msg-item .content{overflow:hidden}
.msg-item .content .t{font-weight:500;color:#f0f6fc}
.msg-item .content .b{color:#8b949e;margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.empty{text-align:center;color:#484f58;padding:40px 0}
.refresh{font-size:11px;color:#484f58;text-align:center;margin-top:8px}
</style>
</head>
<body>
<div class=""header"">
  <div><span class=""dot""></span><span id=""srv"">loading...</span></div>
  <h1>NotifForward Dashboard</h1>
  <button class=""btn"" onclick=""testNotify()"">Send Test</button>
</div>
<div class=""main"">
  <div class=""grid"">
    <div class=""card""><div class=""label"">Messages Forwarded</div><div class=""value"" id=""count"">-</div></div>
    <div class=""card""><div class=""label"">Server Port</div><div class=""value"" id=""port"">-</div></div>
    <div class=""card""><div class=""label"">Topic</div><div class=""value"" id=""topic"" style=""font-size:14px"">-</div></div>
    <div class=""card""><div class=""label"">Blocked Apps</div><div class=""value"" id=""blockedCount"">0</div></div>
  </div>
  <div class=""section"">
    <h2>Blacklist</h2>
    <div class=""blocklist"" id=""blocklist""></div>
    <div class=""add-form"">
      <input id=""blockInput"" placeholder=""App name to block"" onkeydown=""if(event.key=='Enter')addBlock()"">
      <button class=""btn"" onclick=""addBlock()"">Block</button>
    </div>
  </div>
  <div class=""section"">
    <h2>Recent Notifications</h2>
    <div class=""msg-list"" id=""messages""><div class=""empty"">No notifications yet</div></div>
  </div>
  <div class=""refresh"">Auto-refresh every 3s</div>
</div>
<script>
async function load(){try{let r=await fetch('/api/status');let d=await r.json();
document.getElementById('count').textContent=d.messageCount;
document.getElementById('port').textContent=d.port;
document.getElementById('topic').textContent=d.topic;
document.getElementById('blockedCount').textContent=d.blockedApps.length;
document.getElementById('srv').textContent='Server running on port '+d.port;
let bl=document.getElementById('blocklist');bl.innerHTML='';
d.blockedApps.forEach(a=>{let t=document.createElement('span');t.className='tag';
t.innerHTML=a+' <button onclick=""removeBlock(\''+escapeHtml(a)+'\')"" title=""Remove"">×</button>';bl.appendChild(t)});
let ml=document.getElementById('messages');
if(d.messages.length){ml.innerHTML='';
d.messages.forEach(m=>{let i=document.createElement('div');i.className='msg-item';
i.innerHTML='<div class=""time"">'+m.time+'</div><div class=""content""><div class=""t"">'+escapeHtml(m.title)+'</div><div class=""b"">'+escapeHtml(m.message)+'</div></div>';
ml.appendChild(i)})}else{ml.innerHTML='<div class=""empty"">No notifications yet</div>'}}catch(e){document.getElementById('srv').textContent='Connection lost'}
setTimeout(load,3000)}
async function addBlock(){let n=document.getElementById('blockInput').value.trim();if(!n)return;
await fetch('/api/blacklist/add?name='+encodeURIComponent(n));document.getElementById('blockInput').value='';load()}
async function removeBlock(n){await fetch('/api/blacklist/remove?name='+encodeURIComponent(n));load()}
async function testNotify(){await fetch('/api/test');load()}
function escapeHtml(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}
load();
</script>
</body></html>";

    private void NotifySubscribers(string topic, string jsonData)
    {
        if (!_subscribers.TryGetValue(topic, out var queue)) return;

        var subs = new List<PendingSubscriber>();
        while (queue.TryDequeue(out var sub))
            subs.Add(sub);

        foreach (var sub in subs)
        {
            try
            {
                sub.Notify(jsonData);
            }
            catch { }
        }
    }

    public void Stop()
    {
        try { _listener?.Stop(); } catch { }
        try { _listener?.Close(); } catch { }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        Stop();
    }

    private static string ExtractAppName(string title)
    {
        var match = System.Text.RegularExpressions.Regex.Match(title, @"^[\[【](.+?)[\]】]\s*");
        return match.Success ? match.Groups[1].Value : "";
    }

    /// <summary>
    /// Publish a notification from internal code (captured Windows notification).
    /// Only blocked-app-name check is applied here; the capture service already does its own check.
    /// </summary>
    public void PublishFromCode(string title, string message, string appName = "Windows")
    {
        if (_settings.BlockedApps.Any(b => string.Equals(b, appName, StringComparison.OrdinalIgnoreCase)))
        {
            _logger.LogInformation("Blocked from code: [{App}]", appName);
            return;
        }

        var id = Interlocked.Increment(ref _messageCounter).ToString();
        var timestamp = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var topic = _settings.NtfyTopic;
        var displayTitle = $"[{appName}] {title}";

        var fullMsg = JsonSerializer.Serialize(new
        {
            id,
            time = timestamp,
            title = displayTitle,
            message,
            topic,
        }, JsonOpts);

        _latestMessages[topic] = fullMsg;

        lock (_history)
        {
            _history.Insert(0, (DateTime.Now.ToString("HH:mm:ss"), displayTitle, message, topic));
            if (_history.Count > 50) _history.RemoveAt(_history.Count - 1);
        }
        Interlocked.Increment(ref _messageCount);

        NotifySubscribers(topic, fullMsg);

        _logger.LogInformation("Captured and published: [{App}] {Title}", appName, title);
    }

    private class PendingSubscriber
    {
        private readonly TaskCompletionSource<string>? _tcs;
        private readonly Stream? _sseStream;

        public PendingSubscriber(TaskCompletionSource<string> tcs) { _tcs = tcs; }
        public PendingSubscriber(Stream stream) { _sseStream = stream; }

        public void Notify(string jsonData)
        {
            _tcs?.TrySetResult($"[{jsonData}]");
            if (_sseStream != null)
            {
                var sse = $"data: {jsonData}\n\n";
                var buf = Encoding.UTF8.GetBytes(sse);
                _sseStream.Write(buf, 0, buf.Length);
                _sseStream.Flush();
            }
        }
    }

    private class SseSubscriber : PendingSubscriber
    {
        public SseSubscriber(Stream stream) : base(stream) { }
    }
}
