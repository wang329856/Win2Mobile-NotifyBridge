using System.Globalization;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading.Channels;
using System.Threading.RateLimiting;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Win2Mobile.Core;

namespace Win2Mobile.Transport.Lan;

public sealed class LanBridgeHost : IAsyncDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web) { DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull };
    private readonly BridgeStore store;
    private readonly X509Certificate2 certificate;
    private readonly int port;
    private readonly SemaphoreSlim lifecycle = new(1, 1);
    private readonly SemaphoreSlim streamSlots = new(32, 32);
    private WebApplication? application;
    private bool disposed;
    public PairingService Pairing { get; }
    public LanBridgeHost(BridgeStore store, X509Certificate2 certificate, int port = 47721)
    {
        if (port is < 1 or > 65535) throw new ArgumentOutOfRangeException(nameof(port));
        if (!certificate.HasPrivateKey) throw new ArgumentException("TLS certificate requires a private key.", nameof(certificate));
        this.store = store; this.certificate = certificate; this.port = port; Pairing = new PairingService(store);
    }
    public string GetPairingPayload(string host)
    {
        if (!Uri.TryCreate($"https://{host}:{port}", UriKind.Absolute, out var address) || address.Scheme != "https" || address.UserInfo.Length != 0 || address.AbsolutePath != "/" || address.Query.Length != 0 || address.Fragment.Length != 0 || address.Port != port) throw new ArgumentException("Supply a host name or IP address only.", nameof(host));
        return JsonSerializer.Serialize(new { schema = "win2mobile-pair", protocolVersion = 1, baseUrl = address.GetLeftPart(UriPartial.Authority), certificateSha256 = Convert.ToHexString(SHA256.HashData(certificate.RawData)), pairingCode = Pairing.CurrentCode, serverId = store.Identity.ServerId, serverName = store.Identity.ServerName }, JsonOptions);
    }
    public async Task StartAsync(CancellationToken token = default)
    {
        await lifecycle.WaitAsync(token);
        try
        {
            ObjectDisposedException.ThrowIf(disposed, this); if (application is not null) return;
            var builder = WebApplication.CreateSlimBuilder(new WebApplicationOptions { Args = [], ApplicationName = typeof(LanBridgeHost).Assembly.FullName });
            builder.Logging.ClearProviders();
            builder.WebHost.ConfigureKestrel(options =>
            {
                options.Limits.MaxRequestBodySize = 4096;
                options.Limits.MaxRequestHeaderCount = 32;
                options.Limits.MaxRequestHeadersTotalSize = 8192;
                options.Limits.RequestHeadersTimeout = TimeSpan.FromSeconds(15);
                options.Limits.MaxConcurrentConnections = 128;
                options.Limits.MaxConcurrentUpgradedConnections = 32;
                options.ListenAnyIP(port, listen => listen.UseHttps(certificate));
            });
            builder.Services.ConfigureHttpJsonOptions(options => options.SerializerOptions.DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull);
            builder.Services.AddRateLimiter(options =>
            {
                options.RejectionStatusCode = 429;
                options.AddPolicy("pair-create", context => RateLimitPartition.GetFixedWindowLimiter(context.Connection.RemoteIpAddress?.ToString() ?? "unknown", _ => new FixedWindowRateLimiterOptions { PermitLimit = 12, Window = TimeSpan.FromMinutes(1), QueueLimit = 0 }));
                options.AddPolicy("pair-poll", context => RateLimitPartition.GetFixedWindowLimiter(context.Connection.RemoteIpAddress?.ToString() ?? "unknown", _ => new FixedWindowRateLimiterOptions { PermitLimit = 180, Window = TimeSpan.FromMinutes(1), QueueLimit = 0 }));
            });
            var app = builder.Build();
            app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(25) });
            app.UseRateLimiter();
            app.Use(async (context, next) =>
            {
                try { await next(context); }
                catch (JsonException) when (!context.Response.HasStarted) { context.Response.StatusCode = 400; }
                catch (BadHttpRequestException e) when (!context.Response.HasStarted) { context.Response.StatusCode = e.StatusCode; }
            });
            app.MapGet("/v1/health", () => Results.Json(new { protocolVersion = 1, serverId = store.Identity.ServerId, serverName = store.Identity.ServerName, status = "ready", highWatermark = store.HighWatermark }, JsonOptions));
            app.MapPost("/v1/pairing/requests", async (HttpContext context) =>
            {
                var body = await context.Request.ReadFromJsonAsync<PairBody>(context.RequestAborted);
                if (body?.PairingCode is null || body.DeviceName is null) return Results.BadRequest();
                try { return Results.Json(Pairing.CreateRequest(body.PairingCode, body.DeviceName), JsonOptions, statusCode: 202); }
                catch (ArgumentException) { return Results.BadRequest(); }
                catch (UnauthorizedAccessException) { return Results.Unauthorized(); }
                catch (InvalidOperationException) { return Results.StatusCode(429); }
            }).RequireRateLimiting("pair-create");
            app.MapGet("/v1/pairing/requests/{requestId}", (string requestId, HttpContext context) =>
            {
                var secret = context.Request.Headers["X-Pairing-Secret"].ToString();
                var result = Pairing.GetStatus(requestId, secret);
                return result is null ? Results.Unauthorized() : Results.Json(result, JsonOptions);
            }).RequireRateLimiting("pair-poll");
            app.MapGet("/v1/events", (HttpContext context) =>
            {
                var deviceId = Authenticate(context); if (deviceId is null) return Results.Unauthorized();
                if (!Cursor(context, out var after) || !Limit(context, out var limit)) return Results.BadRequest();
                after = Math.Max(after, Math.Max(store.GetStartSequence(deviceId), store.QueueFloor));
                // Snapshot watermark first. Entries appended later belong to the next page/stream.
                var highWatermark = store.HighWatermark;
                var events = store.GetEvents(after, limit).Where(e => e.Sequence <= highWatermark).ToArray();
                return Results.Json(new { events, startCursor = after, nextCursor = events.Length == 0 ? after : events[^1].Sequence, highWatermark }, JsonOptions);
            });
            app.MapPost("/v1/acks", async (HttpContext context) =>
            {
                var deviceId = Authenticate(context); if (deviceId is null) return Results.Unauthorized();
                var body = await context.Request.ReadFromJsonAsync<AckBody>(context.RequestAborted);
                if (body?.Sequence is null) return Results.BadRequest();
                try { return Results.Json(new { acknowledgedSequence = store.Acknowledge(deviceId, body.Sequence.Value) }, JsonOptions); }
                catch (ArgumentOutOfRangeException) { return Results.BadRequest(); }
                catch (UnauthorizedAccessException) { return Results.Unauthorized(); }
            });
            app.MapGet("/v1/events/stream", StreamAsync);
            try { await app.StartAsync(token); application = app; }
            catch { await app.DisposeAsync(); throw; }
        }
        finally { lifecycle.Release(); }
    }
    private string? Authenticate(HttpContext context)
    {
        var authorization = context.Request.Headers.Authorization.ToString();
        return authorization.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) ? store.Authenticate(authorization[7..]) : null;
    }
    private static bool Cursor(HttpContext context, out long after)
    {
        var query = context.Request.Query["after"]; after = 0;
        return query.Count == 0 || (query.Count == 1 && long.TryParse(query[0], NumberStyles.None, CultureInfo.InvariantCulture, out after) && after >= 0);
    }
    private static bool Limit(HttpContext context, out int limit)
    {
        var query = context.Request.Query["limit"]; limit = 200;
        return query.Count == 0 || (query.Count == 1 && int.TryParse(query[0], NumberStyles.None, CultureInfo.InvariantCulture, out limit) && limit is >= 1 and <= 200);
    }
    private async Task StreamAsync(HttpContext context)
    {
        var deviceId = Authenticate(context);
        if (deviceId is null) { context.Response.StatusCode = 401; return; }
        if (!Cursor(context, out var after) || !context.WebSockets.IsWebSocketRequest) { context.Response.StatusCode = 400; return; }
        var liveValues = context.Request.Query["live"];
        if (liveValues.Count > 1 || (liveValues.Count == 1 && liveValues[0] is not ("true" or "false"))) { context.Response.StatusCode = 400; return; }
        var live = liveValues.Count == 1 && liveValues[0] == "true";
        if (!await streamSlots.WaitAsync(0, context.RequestAborted)) { context.Response.StatusCode = 429; return; }
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(context.RequestAborted, context.RequestServices.GetRequiredService<IHostApplicationLifetime>().ApplicationStopping);
        var signal = Channel.CreateBounded<bool>(new BoundedChannelOptions(1) { FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true });
        void OnEvents(object? sender, EventArgs e) => signal.Writer.TryWrite(true);
        void OnRevoke(object? sender, DeviceRevokedEventArgs e)
        {
            if (e.DeviceId != deviceId) return;
            // An event invocation can already have captured this handler while cleanup unsubscribes it.
            try { lifetime.Cancel(); } catch (ObjectDisposedException) { }
        }
        store.EventsChanged += OnEvents; store.DeviceRevoked += OnRevoke;
        WebSocket? socket = null; Task? receive = null;
        try
        {
            // Subscribe before checking activity or reading the watermark to close both races.
            if (!store.IsDeviceActive(deviceId)) { context.Response.StatusCode = 401; return; }
            socket = await context.WebSockets.AcceptWebSocketAsync();
            receive = ObservePeerAsync(socket, lifetime);
            var highWatermark = store.HighWatermark;
            after = Math.Max(after, Math.Max(store.GetStartSequence(deviceId), store.QueueFloor));
            if (live) after = Math.Max(after, highWatermark);
            await SendAsync(socket, new { kind = "hello", protocolVersion = 1, serverId = store.Identity.ServerId, highWatermark, startCursor = after }, lifetime.Token);
            var heartbeatAt = DateTimeOffset.UtcNow.AddSeconds(25);
            while (!lifetime.IsCancellationRequested)
            {
                if (!store.IsDeviceActive(deviceId)) break;
                var floor = store.QueueFloor;
                if (after < floor)
                {
                    after = floor;
                    await SendAsync(socket, new { kind = "checkpoint", highWatermark = store.HighWatermark, startCursor = after }, lifetime.Token);
                }
                var events = store.GetEvents(after);
                foreach (var item in events)
                {
                    lifetime.Token.ThrowIfCancellationRequested();
                    if (!store.IsDeviceActive(deviceId)) return;
                    await SendAsync(socket, new { kind = "event", @event = item }, lifetime.Token); after = item.Sequence;
                }
                if (DateTimeOffset.UtcNow >= heartbeatAt)
                { await SendAsync(socket, new { kind = "heartbeat", serverTime = DateTimeOffset.UtcNow }, lifetime.Token); heartbeatAt = DateTimeOffset.UtcNow.AddSeconds(25); }
                if (events.Count == 200) continue;
                using var wait = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);
                var changed = signal.Reader.ReadAsync(wait.Token).AsTask();
                var timer = Task.Delay(TimeSpan.FromMilliseconds(Math.Max(1, (heartbeatAt - DateTimeOffset.UtcNow).TotalMilliseconds)), wait.Token);
                await Task.WhenAny(changed, timer); wait.Cancel();
                try { await changed; } catch (OperationCanceledException) { }
                try { await timer; } catch (OperationCanceledException) { }
            }
        }
        catch (OperationCanceledException) { }
        catch (WebSocketException) { }
        finally
        {
            store.EventsChanged -= OnEvents; store.DeviceRevoked -= OnRevoke; lifetime.Cancel();
            if (receive is not null) { try { await receive; } catch (OperationCanceledException) { } catch (WebSocketException) { } }
            if (socket is not null)
            {
                if (socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
                { using var close = new CancellationTokenSource(TimeSpan.FromSeconds(2)); try { await socket.CloseOutputAsync(WebSocketCloseStatus.PolicyViolation, "Connection ended", close.Token); } catch (Exception e) when (e is OperationCanceledException or WebSocketException) { } }
                socket.Dispose();
            }
            streamSlots.Release();
        }
    }
    private static async Task ObservePeerAsync(WebSocket socket, CancellationTokenSource lifetime)
    {
        var buffer = new byte[1024];
        try
        {
            while (!lifetime.IsCancellationRequested)
            {
                var result = await socket.ReceiveAsync(buffer.AsMemory(), lifetime.Token);
                // This is a server-only stream; client payloads are rejected and cannot allocate unbounded messages.
                if (result.MessageType == WebSocketMessageType.Close || result.Count > 0) break;
            }
        }
        finally { lifetime.Cancel(); }
    }
    private static Task SendAsync(WebSocket socket, object value, CancellationToken token)
        => socket.SendAsync(JsonSerializer.SerializeToUtf8Bytes(value, JsonOptions).AsMemory(), WebSocketMessageType.Text, true, token).AsTask();
    public async Task StopAsync(CancellationToken token = default)
    {
        await lifecycle.WaitAsync(token);
        try { await StopApplicationAsync(token); }
        finally { lifecycle.Release(); }
    }
    public async ValueTask DisposeAsync()
    {
        await lifecycle.WaitAsync();
        try
        {
            if (disposed) return; disposed = true;
            try { await StopApplicationAsync(CancellationToken.None); }
            finally { Pairing.Dispose(); }
        }
        finally { lifecycle.Release(); }
    }
    // lifecycle serializes callers; clear the field even if shutdown fails so StartAsync
    // cannot mistake a stopped/faulted application for a running listener.
    private async Task StopApplicationAsync(CancellationToken token)
    {
        var app = application; application = null;
        if (app is null) return;
        try { await app.StopAsync(token); }
        finally { await app.DisposeAsync(); }
    }
    private sealed record PairBody(string? PairingCode, string? DeviceName);
    private sealed record AckBody(long? Sequence);
}
