using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using System.Diagnostics;
using Microsoft.Data.Sqlite;
using Win2Mobile.Core;
using Win2Mobile.Transport.Lan;

// Dependency-free integration runner: a nonzero exit code means a failed assertion.
if (args is ["--certificate-worker", var dataDirectory, var readyPath, var releasePath, var resultPath])
{
    File.WriteAllText(readyPath, "ready");
    await WaitUntilAsync(() => File.Exists(releasePath), TimeSpan.FromSeconds(20), "Certificate worker release timed out");
    using var workerCertificate = CertificateManager.GetOrCreate(dataDirectory);
    File.WriteAllText(resultPath, Convert.ToHexString(SHA256.HashData(workerCertificate.RawData)));
    return;
}
var directory = Path.Combine(Path.GetTempPath(), "Win2Mobile-tests-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(directory);
try
{
    await RunAsync(directory);
    Console.WriteLine("PASS: storage restart/idempotence/fresh-source cross-store concurrency; pairing approval/denial/expiry Changed; HTTPS authorization/pagination/ack; WSS 450 history + 21 live/reconnect/heartbeat; 32-slot disconnect/revocation/stop/reuse; production DPAPI certificate HTTPS/WSS create/reload and cross-process identity.");
}
finally { SqliteConnection.ClearAllPools(); Directory.Delete(directory, true); }

static void Check(bool condition, string message) { if (!condition) throw new Exception(message); }
static CapturedNotification Notification(int n) => new("source-" + n, "test.app", "测试应用", "标题 " + n, "正文 " + n, DateTimeOffset.UtcNow);

static async Task RunAsync(string directory)
{
    using (var nativeVersion = new SqliteConnection("Data Source=:memory:"))
    {
        nativeVersion.Open();
        var version = Version.Parse(nativeVersion.ServerVersion);
        Check(version >= new Version(3, 50, 2), "Native SQLite must include the CVE-2025-6965 fix.");
        Console.WriteLine("Native SQLite: " + version);
    }
    var database = Path.Combine(directory, "bridge.db"); string serverId; string persistedToken; string persistedDevice; string firstEventId;
    await TestFreshSourceCompetitionAsync(directory);
    TestExpiryNotifications(directory);
    using var certificate = OperatingSystem.IsWindows() ? CertificateManager.GetOrCreate(directory) : CreateTestCertificate();
    var fingerprint = SHA256.HashData(certificate.RawData);
    using (var store = new BridgeStore(database, "Test PC"))
    {
        serverId = store.Identity.ServerId;
        // Many competing writes for each source must produce exactly one committed event.
        Parallel.For(0, 300, n => store.Append(Notification(n % 100 + 1)));
        Check(store.HighWatermark == 100 && store.GetEvents(0).Select(e => e.Sequence).SequenceEqual(Enumerable.Range(1, 100).Select(n => (long)n)), "Concurrent append sequences/idempotence failed");
        Check(store.GetEvents(0).Select(e => e.EventId).Distinct().Count() == 100, "Event IDs must be unique");
        firstEventId = store.GetEvents(0, 1).Single().EventId;
        var fakeClock = new TestClock();
        using (var pairing = new PairingService(store, fakeClock))
        {
            var pending = pairing.CreateRequest(pairing.CurrentCode, "Expired phone");
            Check(pairing.GetStatus(pending.RequestId, pending.RequestSecret)?.Status == "pending", "New request must be pending");
            fakeClock.Advance(TimeSpan.FromSeconds(121)); pairing.Approve(pending.RequestId);
            Check(pairing.GetStatus(pending.RequestId, pending.RequestSecret)?.Status == "expired" && store.GetDevices().Count == 0, "Expired requests cannot be approved");
            bool badCode = false; try { pairing.CreateRequest(pairing.CurrentCode, "Phone"); } catch (UnauthorizedAccessException) { badCode = true; }
            Check(badCode, "Expired pairing code accepted");
        }
        var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start(); var port = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop();
        await using var host = new LanBridgeHost(store, certificate, port);
        await host.StartAsync();
        using var handler = new HttpClientHandler { ServerCertificateCustomValidationCallback = (_, cert, _, _) => PinnedCertificate(cert, fingerprint) };
        using var client = new HttpClient(handler) { BaseAddress = new Uri($"https://localhost:{port}"), Timeout = TimeSpan.FromSeconds(10) };
        using var qr = JsonDocument.Parse(host.GetPairingPayload("localhost"));
        var code = qr.RootElement.GetProperty("pairingCode").GetString()!;
        Check(qr.RootElement.GetProperty("certificateSha256").GetString() == Convert.ToHexString(fingerprint), "QR certificate fingerprint mismatch");
        Check((await client.GetAsync("/v1/events")).StatusCode == HttpStatusCode.Unauthorized, "Missing Bearer token accepted");
        Check((await client.GetAsync("/v1/events?token=fake")).StatusCode == HttpStatusCode.Unauthorized, "URL token accepted");
        var health = await GetJsonAsync(client, "/v1/health");
        Check(health.GetProperty("highWatermark").GetInt64() == 100 && !health.TryGetProperty("events", out _), "Health must expose only status");
        Check((await client.PostAsJsonAsync("/v1/pairing/requests", new { pairingCode = new string('0', 64), deviceName = "Phone" })).StatusCode == HttpStatusCode.Unauthorized, "Wrong pairing code accepted");
        using (var oversized = new StringContent(new string('x', 5000), System.Text.Encoding.UTF8, "application/json"))
            Check((await client.PostAsync("/v1/pairing/requests", oversized)).StatusCode is HttpStatusCode.RequestEntityTooLarge or HttpStatusCode.BadRequest, "Oversized body accepted");
        var result = await client.PostAsJsonAsync("/v1/pairing/requests", new { pairingCode = code, deviceName = "Test Android" });
        Check(result.StatusCode == HttpStatusCode.Accepted, "Pairing must return 202");
        var pendingJson = (await result.Content.ReadFromJsonAsync<JsonElement>());
        var id = pendingJson.GetProperty("requestId").GetString()!; var secret = pendingJson.GetProperty("requestSecret").GetString()!;
        var statusPath = "/v1/pairing/requests/" + id;
        Check((await client.GetAsync(statusPath)).StatusCode == HttpStatusCode.Unauthorized, "Pair polling without secret accepted");
        client.DefaultRequestHeaders.Add("X-Pairing-Secret", new string('0', 64));
        Check((await client.GetAsync(statusPath)).StatusCode == HttpStatusCode.Unauthorized, "Pair polling with wrong secret accepted");
        client.DefaultRequestHeaders.Remove("X-Pairing-Secret"); client.DefaultRequestHeaders.Add("X-Pairing-Secret", secret);
        var pendingStatus = await GetJsonAsync(client, statusPath);
        Check(pendingStatus.GetProperty("status").GetString() == "pending" && !pendingStatus.TryGetProperty("accessToken", out _), "Token leaked before UI approval");
        Check(host.Pairing.PendingRequests.Single().RequestId == id, "UI pending list failed");
        host.Pairing.Approve(id);
        var approved = await GetJsonAsync(client, statusPath);
        persistedToken = approved.GetProperty("accessToken").GetString()!; persistedDevice = approved.GetProperty("deviceId").GetString()!;
        Check(persistedToken.Length == 64 && store.Authenticate(persistedToken) == persistedDevice, "Approved token failed");
        var denied = host.Pairing.CreateRequest(code, "Denied phone"); host.Pairing.Deny(denied.RequestId);
        Check(host.Pairing.GetStatus(denied.RequestId, denied.RequestSecret)?.Status == "denied", "UI denial failed");
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", new string('0', 64));
        Check((await client.GetAsync("/v1/events")).StatusCode == HttpStatusCode.Unauthorized, "Wrong Bearer token accepted");
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", persistedToken);
        using (var unauthorizedSocket = MakeSocket(new string('0', 64), fingerprint))
        {
            bool rejected = false; try { await unauthorizedSocket.ConnectAsync(new Uri($"wss://localhost:{port}/v1/events/stream?after=0"), CancellationToken.None); } catch (WebSocketException) { rejected = true; }
            Check(rejected, "WSS accepted unauthorized credential");
        }
        foreach (var invalid in new[] { "after=-1", "limit=0", "limit=201", "after=9223372036854775808", "after=0&after=1" })
            Check((await client.GetAsync("/v1/events?" + invalid)).StatusCode == HttpStatusCode.BadRequest, "Invalid cursor/limit accepted: " + invalid);
        var page1 = await GetJsonAsync(client, "/v1/events?after=0&limit=40");
        Check(page1.GetProperty("events").GetArrayLength() == 40 && page1.GetProperty("nextCursor").GetInt64() == 40, "Pagination failed");
        var page2 = await GetJsonAsync(client, "/v1/events?after=40&limit=200");
        Check(page2.GetProperty("events").GetArrayLength() == 60 && page2.GetProperty("highWatermark").GetInt64() == 100, "Pagination second page failed");
        Parallel.For(101, 451, i => store.Append(Notification(i)));
        long pageCursor = 0; var pagedSequences = new List<long>();
        do
        {
            var page = await GetJsonAsync(client, $"/v1/events?after={pageCursor}&limit=200");
            pagedSequences.AddRange(page.GetProperty("events").EnumerateArray().Select(e => e.GetProperty("sequence").GetInt64()));
            pageCursor = page.GetProperty("nextCursor").GetInt64();
        } while (pageCursor < 450);
        Check(pagedSequences.SequenceEqual(Enumerable.Range(1, 450).Select(n => (long)n)), "Multi-page HTTP replay lost/reordered events");
        using (var socket = MakeSocket(persistedToken, fingerprint))
        {
            await socket.ConnectAsync(new Uri($"wss://localhost:{port}/v1/events/stream?after=0"), CancellationToken.None);
            var hello = await ReceiveAsync(socket);
            Check(hello.GetProperty("kind").GetString() == "hello" && hello.GetProperty("highWatermark").GetInt64() == 450, "WSS hello failed");
            for (int n = 1; n <= 470; n++)
            {
                if (n == 10) Parallel.For(451, 471, i => store.Append(Notification(i)));
                var frame = await ReceiveAsync(socket);
                Check(frame.GetProperty("kind").GetString() == "event" && frame.GetProperty("event").GetProperty("sequence").GetInt64() == n, "History/live seam lost/reordered a message at " + n);
            }
            // A second append after fully draining replay verifies an established stream wakes.
            store.Append(Notification(471));
            Check((await ReceiveAsync(socket)).GetProperty("event").GetProperty("sequence").GetInt64() == 471, "Idle/live transition lost event");
            using var close = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Reconnect", close.Token);
        }
        var ack = await client.PostAsJsonAsync("/v1/acks", new { sequence = 460 });
        Check(ack.IsSuccessStatusCode, "Ack failed");
        var olderAck = await client.PostAsJsonAsync("/v1/acks", new { sequence = 90 });
        Check((await olderAck.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("acknowledgedSequence").GetInt64() == 460, "Ack moved backwards");
        foreach (var invalid in new long[] { -1, 472 }) Check((await client.PostAsJsonAsync("/v1/acks", new { sequence = invalid })).StatusCode == HttpStatusCode.BadRequest, "Out of range ack accepted");
        using (var reconnect = MakeSocket(persistedToken, fingerprint))
        {
            await reconnect.ConnectAsync(new Uri($"wss://localhost:{port}/v1/events/stream?after=460"), CancellationToken.None);
            await ReceiveAsync(reconnect);
            for (int n = 461; n <= 471; n++) Check((await ReceiveAsync(reconnect)).GetProperty("event").GetProperty("sequence").GetInt64() == n, "Reconnect replay failed");
            var heartbeat = await ReceiveAsync(reconnect, 30);
            Check(heartbeat.GetProperty("kind").GetString() == "heartbeat" && heartbeat.TryGetProperty("serverTime", out _), "Idle WSS heartbeat failed");
            // Stop must cancel a stream waiting for new data rather than wait 25 seconds for a heartbeat.
            using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            await host.StopAsync(stop.Token);
        }
        await host.StartAsync();
        var revoke = store.RegisterDevice("Revoked phone");
        using (var revokedSocket = MakeSocket(revoke.AccessToken, fingerprint))
        {
            await revokedSocket.ConnectAsync(new Uri($"wss://localhost:{port}/v1/events/stream?after=471"), CancellationToken.None); await ReceiveAsync(revokedSocket);
            store.RevokeDevice(revoke.DeviceId);
            bool terminated = false;
            try { var bytes = new byte[1024]; var received = await revokedSocket.ReceiveAsync(bytes.AsMemory(), new CancellationTokenSource(TimeSpan.FromSeconds(5)).Token); terminated = received.MessageType == WebSocketMessageType.Close; }
            catch (WebSocketException) { terminated = true; }
            Check(terminated, "Revocation did not terminate WSS connection");
        }
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", revoke.AccessToken);
        Check((await client.GetAsync("/v1/events")).StatusCode == HttpStatusCode.Unauthorized, "Revoked credential still authenticates");
        Check(store.GetDevices().Single().AcknowledgedSequence == 460, "Persisted ack missing");
        await TestStreamSlotReuseAsync(host, store, persistedToken, fingerprint, port);
        using var inspection = new SqliteConnection("Data Source=" + database); inspection.Open();
        using var query = inspection.CreateCommand(); query.CommandText = "SELECT token_hash FROM devices";
        var storedHash = (string)query.ExecuteScalar()!;
        Check(storedHash != persistedToken && storedHash == Convert.ToHexString(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(persistedToken))), "Raw credential persisted");
        host.Pairing.RefreshCode(); code = host.Pairing.CurrentCode;
        for (int i = 0; i < 8; i++) host.Pairing.CreateRequest(code, "Limit phone " + i);
        bool capped = false; try { host.Pairing.CreateRequest(code, "Over cap"); } catch (InvalidOperationException) { capped = true; }
        Check(capped, "Pending request cap missing");
        host.Pairing.RefreshCode();
        bool stale = false; try { host.Pairing.CreateRequest(code, "Stale QR"); } catch (UnauthorizedAccessException) { stale = true; }
        Check(stale, "Refresh did not invalidate old QR code");
        HttpStatusCode finalPairStatus = 0;
        for (int i = 0; i < 20; i++) finalPairStatus = (await client.PostAsJsonAsync("/v1/pairing/requests", new { pairingCode = new string('0', 64), deviceName = "Rate limit probe" })).StatusCode;
        Check(finalPairStatus == HttpStatusCode.TooManyRequests, "Pairing endpoint IP rate limiter failed");
        await host.StopAsync();
    }
    using (var reopened = new BridgeStore(database, "Renamed PC"))
    {
        Check(reopened.Identity.ServerId == serverId && reopened.HighWatermark == 471, "Restart lost server identity/sequence");
        Check(reopened.GetEvents(0, 1).Single().EventId == firstEventId, "Restart changed a persisted event ID");
        Check(reopened.Authenticate(persistedToken) == persistedDevice && reopened.GetDevices().Single().AcknowledgedSequence == 460, "Restart lost credentials/ack");
        Check(reopened.Append(Notification(1)) is null && reopened.Append(Notification(472))?.Sequence == 472, "Restart lost idempotence/sequence continuity");
    }
    if (OperatingSystem.IsWindows())
    {
        using var reloaded = CertificateManager.GetOrCreate(directory);
        Check(reloaded.HasPrivateKey && reloaded.RawData.SequenceEqual(certificate.RawData), "DPAPI certificate persistence failed");
        await TestCertificateHandshakeAsync(directory, reloaded);
        await TestCertificateProcessesAsync(directory);
    }
}

static async Task TestFreshSourceCompetitionAsync(string directory)
{
    var database = Path.Combine(directory, "competing.db");
    using (var first = new BridgeStore(database, "First writer"))
    using (var second = new BridgeStore(database, "Second writer"))
    using (var barrier = new Barrier(2))
    {
        // Each barrier starts both stores on a source which did not exist before this round.
        int inserted = 0;
        void Write(BridgeStore target)
        {
            for (int n = 1; n <= 100; n++)
            {
                Check(barrier.SignalAndWait(TimeSpan.FromSeconds(10)), "Cross-store barrier timed out");
                if (target.Append(Notification(n)) is not null) Interlocked.Increment(ref inserted);
            }
        }
        await Task.WhenAll(Task.Run(() => Write(first)), Task.Run(() => Write(second)));
        Check(inserted == 100 && first.HighWatermark == 100, "Fresh-source cross-store competition duplicated an event");
        Check(first.GetEvents(0).Select(e => e.SourceNotificationId).Distinct().Count() == 100, "Cross-store source uniqueness failed");
    }
    using var reopened = new BridgeStore(database, "Reopened writer");
    Check(reopened.HighWatermark == 100 && reopened.Append(Notification(100)) is null && reopened.Append(Notification(101))?.Sequence == 101, "Cross-store restart continuity failed");
}

static void TestExpiryNotifications(string directory)
{
    using var store = new BridgeStore(Path.Combine(directory, "expiry.db"), "Expiry test");
    foreach (var access in new[] { "pending", "status", "approve", "deny", "create" })
    {
        var clock = new TestClock();
        using var pairing = new PairingService(store, clock);
        var request = pairing.CreateRequest(pairing.CurrentCode, "Expired " + access);
        int changed = 0;
        pairing.Changed += (_, _) =>
        {
            Interlocked.Increment(ref changed);
            // Reading from another thread must complete: callbacks must run outside the gate.
            Check(Task.Run(() => pairing.PendingRequests.Count).Wait(TimeSpan.FromSeconds(5)), "Changed callback ran under pairing lock");
        };
        clock.Advance(TimeSpan.FromSeconds(121));
        switch (access)
        {
            case "pending": Check(pairing.PendingRequests.Count == 0, "Expired pending request remained visible"); break;
            case "status": Check(pairing.GetStatus(request.RequestId, request.RequestSecret)?.Status == "expired", "Expired status not published"); break;
            case "approve": pairing.Approve(request.RequestId); break;
            case "deny": pairing.Deny(request.RequestId); break;
            case "create":
                bool rejected = false;
                try { pairing.CreateRequest(pairing.CurrentCode, "Another phone"); } catch (UnauthorizedAccessException) { rejected = true; }
                Check(rejected, "Expired code accepted"); break;
        }
        Check(changed == 1, "Expiration Changed was swallowed by " + access);
        clock.FireTimers();
        Check(changed == 1 && store.GetDevices().Count == 0, "Expiration duplicate event or expired approval");
    }
}

static async Task TestStreamSlotReuseAsync(LanBridgeHost host, BridgeStore store, string token, byte[] fingerprint, int port)
{
    var tracked = new List<ClientWebSocket>();
    var revoked = store.RegisterDevice("Batch revoked phone");
    try
    {
        var first = await ConnectBatchAsync(token, fingerprint, port, store.HighWatermark); tracked.AddRange(first);
        using (var extra = MakeSocket(token, fingerprint))
        using (var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5)))
        {
            bool rejected = false;
            try { await extra.ConnectAsync(StreamUri(port, store.HighWatermark), timeout.Token); } catch (WebSocketException) { rejected = true; }
            Check(rejected, "33rd concurrent stream was accepted");
        }
        foreach (var socket in first) { socket.Abort(); socket.Dispose(); }
        var second = await ConnectBatchAsync(revoked.AccessToken, fingerprint, port, store.HighWatermark); tracked.AddRange(second);
        store.RevokeDevice(revoked.DeviceId);
        await Task.WhenAll(second.Select(ExpectStreamEndedAsync));
        foreach (var socket in second) socket.Dispose();
        var third = await ConnectBatchAsync(token, fingerprint, port, store.HighWatermark); tracked.AddRange(third);
        using (var stop = new CancellationTokenSource(TimeSpan.FromSeconds(5))) await host.StopAsync(stop.Token);
        await Task.WhenAll(third.Select(ExpectStreamEndedAsync));
        foreach (var socket in third) socket.Dispose();
        await host.StartAsync();
        var fourth = await ConnectBatchAsync(token, fingerprint, port, store.HighWatermark); tracked.AddRange(fourth);
        using var close = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        await Task.WhenAll(fourth.Select(socket => socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Slot reuse verified", close.Token)));
        // A canceled caller must not destroy the listener while waiting to acquire the lifecycle gate.
        using var canceled = new CancellationTokenSource(); canceled.Cancel();
        bool canceledStop = false;
        try { await host.StopAsync(canceled.Token); } catch (OperationCanceledException) { canceledStop = true; }
        Check(canceledStop, "Pre-canceled StopAsync ignored cancellation");
        using var probe = await ConnectOneAsync(token, fingerprint, port, store.HighWatermark);
    }
    finally { store.RevokeDevice(revoked.DeviceId); foreach (var socket in tracked) socket.Dispose(); }
}

static Uri StreamUri(int port, long after) => new($"wss://localhost:{port}/v1/events/stream?after={after}");
static async Task<ClientWebSocket> ConnectOneAsync(string token, byte[] fingerprint, int port, long after)
{
    // Disconnect cleanup is asynchronous; bounded retry observes eventual slot release without a test-only API.
    var deadline = Stopwatch.StartNew();
    while (true)
    {
        var socket = MakeSocket(token, fingerprint);
        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            await socket.ConnectAsync(StreamUri(port, after), timeout.Token);
            Check((await ReceiveAsync(socket)).GetProperty("kind").GetString() == "hello", "Batch stream hello failed");
            return socket;
        }
        catch (WebSocketException) when (deadline.Elapsed < TimeSpan.FromSeconds(10)) { socket.Dispose(); await Task.Delay(50); }
        catch { socket.Dispose(); throw; }
    }
}
static async Task<List<ClientWebSocket>> ConnectBatchAsync(string token, byte[] fingerprint, int port, long after)
{
    var tasks = Enumerable.Range(0, 32).Select(_ => ConnectOneAsync(token, fingerprint, port, after)).ToArray();
    try { return (await Task.WhenAll(tasks)).ToList(); }
    catch { foreach (var task in tasks) if (task.IsCompletedSuccessfully) task.Result.Dispose(); throw; }
}
static async Task ExpectStreamEndedAsync(ClientWebSocket socket)
{
    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
    try
    {
        var received = await socket.ReceiveAsync(new byte[1024].AsMemory(), timeout.Token);
        Check(received.MessageType == WebSocketMessageType.Close, "Canceled stream sent unexpected payload");
    }
    catch (WebSocketException) { /* Revocation/host cancellation may abort a pending receive. */ }
}

static X509Certificate2 CreateTestCertificate()
{
    using var key = RSA.Create(2048);
    var request = new CertificateRequest("CN=localhost", key, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
    using var generated = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-1), DateTimeOffset.UtcNow.AddDays(1));
    var bytes = generated.Export(X509ContentType.Pfx);
    try { return X509CertificateLoader.LoadPkcs12(bytes, null, OperatingSystem.IsWindows() ? X509KeyStorageFlags.UserKeySet : X509KeyStorageFlags.EphemeralKeySet); }
    finally { CryptographicOperations.ZeroMemory(bytes); }
}
static bool PinnedCertificate(X509Certificate? cert, byte[] fingerprint)
{
    if (cert is null) return false;
    using var parsed = X509CertificateLoader.LoadCertificate(cert.GetRawCertData());
    return DateTime.UtcNow >= parsed.NotBefore.ToUniversalTime() && DateTime.UtcNow < parsed.NotAfter.ToUniversalTime()
        && CryptographicOperations.FixedTimeEquals(SHA256.HashData(parsed.RawData), fingerprint);
}

static async Task TestCertificateHandshakeAsync(string directory, X509Certificate2 certificate)
{
    using var store = new BridgeStore(Path.Combine(directory, "reload-tls.db"), "Reload TLS test");
    var credential = store.RegisterDevice("TLS phone"); var port = FreePort(); var fingerprint = SHA256.HashData(certificate.RawData);
    await using var host = new LanBridgeHost(store, certificate, port); await host.StartAsync();
    using var handler = new HttpClientHandler { ServerCertificateCustomValidationCallback = (_, cert, _, _) => PinnedCertificate(cert, fingerprint) };
    using var client = new HttpClient(handler) { BaseAddress = new Uri($"https://localhost:{port}"), Timeout = TimeSpan.FromSeconds(5) };
    Check((await GetJsonAsync(client, "/v1/health")).GetProperty("status").GetString() == "ready", "Reloaded production certificate HTTPS handshake failed");
    using var socket = await ConnectOneAsync(credential.AccessToken, fingerprint, port, 0);
    await host.StopAsync();
}
static int FreePort()
{ var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start(); var port = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop(); return port; }

static async Task TestCertificateProcessesAsync(string directory)
{
    var dataDirectory = Path.Combine(directory, "certificate-processes"); Directory.CreateDirectory(dataDirectory);
    var release = Path.Combine(directory, "release-workers");
    var processes = new List<Process>(); var outputs = new List<Task<string>>(); var results = new List<string>();
    try
    {
        for (int n = 0; n < 2; n++)
        {
            var ready = Path.Combine(directory, "ready-worker-" + n); var result = Path.Combine(directory, "result-worker-" + n); results.Add(result);
            var executable = Environment.ProcessPath ?? throw new InvalidOperationException("Cannot resolve certificate worker executable.");
            var start = new ProcessStartInfo(executable) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
            if (Path.GetFileNameWithoutExtension(executable).Equals("dotnet", StringComparison.OrdinalIgnoreCase)) start.ArgumentList.Add(typeof(TestClock).Assembly.Location);
            start.ArgumentList.Add("--certificate-worker");
            // Include case/trailing-separator/dot aliases to exercise path normalization across processes.
            start.ArgumentList.Add(n == 0 ? dataDirectory : Path.Combine(dataDirectory.ToUpperInvariant(), ".") + Path.DirectorySeparatorChar);
            start.ArgumentList.Add(ready); start.ArgumentList.Add(release); start.ArgumentList.Add(result);
            var process = Process.Start(start)!; processes.Add(process);
            outputs.Add(process.StandardError.ReadToEndAsync()); outputs.Add(process.StandardOutput.ReadToEndAsync());
        }
        await WaitUntilAsync(() => Enumerable.Range(0, 2).All(n => File.Exists(Path.Combine(directory, "ready-worker-" + n))), TimeSpan.FromSeconds(15), "Certificate workers failed to start");
        File.WriteAllText(release, "go");
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(35));
        await Task.WhenAll(processes.Select(p => p.WaitForExitAsync(timeout.Token)));
        var diagnostics = string.Join(Environment.NewLine, await Task.WhenAll(outputs));
        Check(processes.All(p => p.ExitCode == 0), "Certificate child process failed: " + diagnostics);
        var fingerprints = results.Select(File.ReadAllText).ToArray();
        using var reloaded = CertificateManager.GetOrCreate(dataDirectory);
        Check(fingerprints.Distinct().Count() == 1 && fingerprints[0] == Convert.ToHexString(SHA256.HashData(reloaded.RawData)), "Concurrent processes returned different certificate identities");
    }
    finally { foreach (var process in processes) { if (!process.HasExited) process.Kill(entireProcessTree: true); process.Dispose(); } }
}
static async Task WaitUntilAsync(Func<bool> ready, TimeSpan timeout, string failure)
{ var elapsed = Stopwatch.StartNew(); while (!ready()) { Check(elapsed.Elapsed < timeout, failure); await Task.Delay(20); } }

static async Task<JsonElement> GetJsonAsync(HttpClient client, string path)
{ using var response = await client.GetAsync(path); response.EnsureSuccessStatusCode(); return await response.Content.ReadFromJsonAsync<JsonElement>(); }
static ClientWebSocket MakeSocket(string token, byte[] fingerprint)
{
    var socket = new ClientWebSocket(); socket.Options.SetRequestHeader("Authorization", "Bearer " + token);
    socket.Options.RemoteCertificateValidationCallback = (_, cert, _, _) => PinnedCertificate(cert, fingerprint);
    return socket;
}
static async Task<JsonElement> ReceiveAsync(ClientWebSocket socket, int timeoutSeconds = 10)
{
    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSeconds)); using var data = new MemoryStream(); var buffer = new byte[4096];
    ValueWebSocketReceiveResult result;
    do { result = await socket.ReceiveAsync(buffer.AsMemory(), timeout.Token); Check(result.MessageType == WebSocketMessageType.Text, "Expected text frame"); data.Write(buffer, 0, result.Count); Check(data.Length < 131072, "Oversized frame"); } while (!result.EndOfMessage);
    using var json = JsonDocument.Parse(data.ToArray()); return json.RootElement.Clone();
}
sealed class TestClock : TimeProvider
{
    private long ticks = DateTimeOffset.UtcNow.UtcTicks;
    private readonly List<TestTimer> timers = [];
    public override DateTimeOffset GetUtcNow() => new(Interlocked.Read(ref ticks), TimeSpan.Zero);
    public void Advance(TimeSpan time) => Interlocked.Add(ref ticks, time.Ticks);
    public override ITimer CreateTimer(TimerCallback callback, object? state, TimeSpan dueTime, TimeSpan period)
    { var timer = new TestTimer(callback, state); timers.Add(timer); return timer; }
    public void FireTimers() { foreach (var timer in timers) timer.Fire(); }
    private sealed class TestTimer(TimerCallback callback, object? state) : ITimer
    {
        private bool disposed;
        public bool Change(TimeSpan dueTime, TimeSpan period) => !disposed;
        public void Fire() { if (!disposed) callback(state); }
        public void Dispose() => disposed = true;
        public ValueTask DisposeAsync() { Dispose(); return ValueTask.CompletedTask; }
    }
}
