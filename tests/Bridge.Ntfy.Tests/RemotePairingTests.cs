using System.Collections.Concurrent;
using System.Net;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Win2Mobile.Core;
using Win2Mobile.Transport.Lan;
using Win2Mobile.Transport.Ntfy;

static class RemotePairingTests
{
    public static async Task RunAsync(string directory)
    {
        using var certificate = CertificateManager.GetOrCreate(Path.Combine(directory, "remote-cert"));
        var builder = WebApplication.CreateSlimBuilder(); builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(o => o.Listen(IPAddress.Loopback, 0, listener => listener.UseHttps(certificate)));
        await using var broker = builder.Build(); broker.UseWebSockets();
        var topics = new ConcurrentDictionary<string, Channel<string>>();
        var published = new ConcurrentDictionary<string, ConcurrentQueue<string>>();
        broker.MapPost("/{topic}", async (string topic, HttpContext context) =>
        {
            if (topic.Length > 64) return Results.NotFound();
            var body = await new StreamReader(context.Request.Body).ReadToEndAsync(context.RequestAborted);
            published.GetOrAdd(topic, _ => new()).Enqueue(body);
            topics.GetOrAdd(topic, _ => Channel.CreateUnbounded<string>()).Writer.TryWrite(body);
            return Results.Ok();
        });
        broker.MapGet("/{topic}/ws", async (string topic, HttpContext context) =>
        {
            if (topic.Length > 64) { context.Response.StatusCode = 404; return; }
            using var socket = await context.WebSockets.AcceptWebSocketAsync();
            using var cancelled = CancellationTokenSource.CreateLinkedTokenSource(context.RequestAborted, broker.Lifetime.ApplicationStopping);
            async Task Frame(string kind, string? message) => await socket.SendAsync(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new
                { id = Guid.NewGuid().ToString("N")[..12], @event = kind, topic, message })), WebSocketMessageType.Text, true, cancelled.Token);
            try
            {
                await Frame("open", null);
                await foreach (var message in topics.GetOrAdd(topic, _ => Channel.CreateUnbounded<string>()).Reader.ReadAllAsync(cancelled.Token))
                    await Frame("message", message);
            }
            catch (Exception e) when (e is OperationCanceledException or WebSocketException) { }
        });
        await broker.StartAsync(); var url = broker.Urls.Single();
        var port = new Uri(url).Port;
        await using var proxy = new LoopbackProxy(port); proxy.Start();
        var remoteUrl = $"https://relay.invalid:{port}";
        using var store = new BridgeStore(Path.Combine(directory, "remote.db"), "Remote PC");
        var registry = new NtfyRegistry(Path.Combine(directory, "remote.dpapi"), store); registry.Configure(true, remoteUrl);
        await using var lan = new LanBridgeHost(store, certificate);
        bool failProvisionOnce = true;
        lan.Pairing.RelayCredentials = id => { if (failProvisionOnce) { failProvisionOnce = false; throw new IOException("Synthetic transient write failure"); } return registry.Provision(id); };
        bool Pin(System.Security.Cryptography.X509Certificates.X509Certificate? cert) => cert is not null && SHA256.HashData(cert.GetRawCertData()).SequenceEqual(SHA256.HashData(certificate.RawData));
        using var phoneHttp = new HttpClient(new HttpClientHandler { ServerCertificateCustomValidationCallback = (_, cert, _, _) => Pin(cert), AllowAutoRedirect = false });
        var proxyHandler = NtfyNetwork.CreateHandler(proxy.Url);
        proxyHandler.ServerCertificateCustomValidationCallback = (_, cert, _, _) => Pin(cert);
        await using (var remote = new NtfyRemotePairing(lan, "127.0.0.1", remoteUrl, proxyHandler,
            () => { var socket = NtfyNetwork.CreateSocket(proxy.Url); socket.Options.RemoteCertificateValidationCallback = (_, cert, _, _) => Pin(cert); return socket; }))
        {
            var qr = JsonSerializer.Deserialize<RemotePairPayload>(remote.Payload, NtfyProtocol.Json)!;
            remote.Start();
            // Malformed cached envelope must not terminate or poison subsequent pairing.
            await phoneHttp.PostAsync(url + "/" + qr.RequestTopic, new StringContent("null"));
            using var phone = ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);
            using var serverPublic = ECDiffieHellman.Create(); serverPublic.ImportSubjectPublicKeyInfo(Convert.FromBase64String(qr.ServerPublicKey), out _);
            var replyKey = phone.DeriveKeyFromHash(serverPublic.PublicKey, HashAlgorithmName.SHA256);
            var id = Guid.NewGuid().ToString();
            var clear = JsonSerializer.Serialize(new RemotePairRequest(id, "Remote phone", Convert.ToBase64String(phone.ExportSubjectPublicKeyInfo())), NtfyProtocol.Json);
            var encrypted = RemotePairCrypto.Seal(clear, Convert.FromBase64String(qr.PairingKey), qr.ServerId, qr.RequestTopic, "request", id);
            await phoneHttp.PostAsync(url + "/" + qr.RequestTopic, new StringContent(encrypted, Encoding.UTF8, "text/plain"));
            await Wait(() => lan.Pairing.PendingRequests.Count == 1);
            var pending = lan.Pairing.PendingRequests.Single();
            if (pending.VerificationCode != RemotePairCrypto.VerificationCode(replyKey)) throw new Exception("Remote SAS differs");
            if (published.ContainsKey(qr.ResponseTopic) || registry.ActiveDevices().Count != 0) throw new Exception("Pending remote pairing exposed authorization");
            lan.Pairing.Approve(pending.RequestId);
            await Wait(() => published.TryGetValue(qr.ResponseTopic, out var messages) && !messages.IsEmpty);
            var reply = published[qr.ResponseTopic].First();
            var result = JsonSerializer.Deserialize<PairingStatus>(RemotePairCrypto.Open(reply, replyKey, qr.ServerId, qr.ResponseTopic, "response"), NtfyProtocol.Json)!;
            if (result.Status != "approved" || result.Ntfy is null || store.Authenticate(result.AccessToken!) != result.DeviceId) throw new Exception("Remote authorization failed");
            try { RemotePairCrypto.Open(reply, Convert.FromBase64String(qr.PairingKey), qr.ServerId, qr.ResponseTopic, "response"); throw new Exception("QR holder read approved phone credentials"); } catch (CryptographicException) { }
            await phoneHttp.PostAsync(url + "/" + qr.RequestTopic, new StringContent(encrypted));
            await Task.Delay(200);
            if (lan.Pairing.PendingRequests.Count != 0 || store.GetDevices().Count != 1) throw new Exception("Replayed remote request created duplicate authorization");
        }
        if (proxy.Targets.Count < 2) throw new Exception("Remote request subscription and approval reply did not both use CONNECT proxy.");
        await broker.StopAsync();
        Console.WriteLine("PASS: real CONNECT proxy HTTPS/WSS remote QR pairing, malformed-frame isolation, ECDH SAS, explicit approval, per-phone encrypted reply and replay deduplication.");
    }
    private static async Task Wait(Func<bool> condition)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(12));
        while (!condition()) await Task.Delay(25, timeout.Token);
    }
}
