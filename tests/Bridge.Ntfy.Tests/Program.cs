using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Win2Mobile.Core;
using Win2Mobile.Transport.Lan;
using Win2Mobile.Transport.Ntfy;

static void Check(bool condition, string message) { if (!condition) throw new Exception(message); }
static byte[] Decrypt(string wire, string serverId, string deviceId, NtfyCredentials credentials)
{
    var part = JsonSerializer.Deserialize<NtfyFragment>(wire, NtfyProtocol.Json)!;
    var data = Convert.FromBase64String(part.Data); var clear = new byte[data.Length - 16];
    using var aes = new AesGcm(Convert.FromBase64String(credentials.Key), 16);
    aes.Decrypt(Convert.FromBase64String(part.Nonce), data.AsSpan(0, clear.Length), data.AsSpan(clear.Length), clear,
        NtfyProtocol.AssociatedData(serverId, deviceId, credentials.Topic, part.Id, part.Index, part.Count));
    return clear;
}
var serverId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
var deviceId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
var credentials = new NtfyCredentials("https://ntfy.sh", "w2m_" + new string('a', 60), Convert.ToBase64String(Enumerable.Range(0, 32).Select(i => (byte)i).ToArray()), 0);
try { NtfyProtocol.Validate(credentials with { Topic = "w2m_" + new string('a', 64) }); throw new Exception("ntfy topic limit was not enforced."); } catch (ArgumentException) { }
var sample = new BridgeEvent(1, "cccccccc-cccc-cccc-cccc-cccccccccccc", serverId, "synthetic-source", "test.app", "测试应用", "保密标题 🔒", string.Concat(Enumerable.Repeat("跨网络通知🙂", 240)), DateTimeOffset.Parse("2026-10-01T00:00:00Z"));
var fragments = NtfyProtocol.Encrypt(sample, deviceId, credentials);
using var pairServer = ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);
using var pairPhone = ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);
var responseKey = RemotePairCrypto.ResponseKey(pairServer, Convert.ToBase64String(pairPhone.ExportSubjectPublicKeyInfo()));
var phoneDerived = pairPhone.DeriveKeyFromHash(pairServer.PublicKey, HashAlgorithmName.SHA256);
Check(responseKey.SequenceEqual(phoneDerived), "ECDH derivation differs");
var pairingRequest = RemotePairCrypto.Seal("{\"deviceName\":\"Synthetic\"}", Convert.FromBase64String(credentials.Key), serverId, credentials.Topic, "request", deviceId);
var pairingResponse = RemotePairCrypto.Seal("{\"status\":\"approved\"}", responseKey, serverId, credentials.Topic, "response", deviceId);
Check(RemotePairCrypto.Open(pairingResponse, phoneDerived, serverId, credentials.Topic, "response") == "{\"status\":\"approved\"}", "ECDH response failed");
try { RemotePairCrypto.Open(pairingResponse, Convert.FromBase64String(credentials.Key), serverId, credentials.Topic, "response"); throw new Exception("QR secret decrypted phone response"); } catch (CryptographicException) { }
Check(fragments.Count > 1 && fragments.All(f => Encoding.UTF8.GetByteCount(f) <= 4096 && !f.Contains(sample.Title)), "Fragments leak plaintext or exceed message limit");
var restored = fragments.SelectMany(f => Decrypt(f, serverId, deviceId, credentials)).ToArray();
Check(JsonSerializer.Deserialize<BridgeEvent>(restored, NtfyProtocol.Json) == sample, "Multi-fragment roundtrip failed");
try { Decrypt(fragments[0], serverId, Guid.NewGuid().ToString(), credentials); throw new Exception("Wrong device accepted"); } catch (CryptographicException) { }
var modified = JsonSerializer.Deserialize<NtfyFragment>(fragments[0], NtfyProtocol.Json)! with { Index = 1 };
try { Decrypt(JsonSerializer.Serialize(modified, NtfyProtocol.Json), serverId, deviceId, credentials); throw new Exception("Changed index accepted"); } catch (CryptographicException) { }
if (args is ["--write-fixture", var fixturePath])
{
    Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(fixturePath))!);
    File.WriteAllText(fixturePath, JsonSerializer.Serialize(new { serverId, deviceId, credentials, bridgeEvent = sample, fragments }, new JsonSerializerOptions(NtfyProtocol.Json) { WriteIndented = true }));
    File.WriteAllText(Path.Combine(Path.GetDirectoryName(Path.GetFullPath(fixturePath))!, "ntfy-pair-v1.json"), JsonSerializer.Serialize(new {
        serverId, requestId = deviceId, topic = credentials.Topic, pairingKey = credentials.Key, request = pairingRequest,
        serverPublicKey = Convert.ToBase64String(pairServer.ExportSubjectPublicKeyInfo()),
        clientPrivateKey = Convert.ToBase64String(pairPhone.ExportPkcs8PrivateKey()),
        responseKey = Convert.ToBase64String(responseKey), response = pairingResponse, verificationCode = RemotePairCrypto.VerificationCode(responseKey)
    }, new JsonSerializerOptions(NtfyProtocol.Json) { WriteIndented = true }));
    Console.WriteLine("Wrote synthetic C# interoperability fixture."); return;
}
using (var fixture = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "ntfy-v1.json"))))
{
    var root = fixture.RootElement;
    var fixtureCredentials = root.GetProperty("credentials").Deserialize<NtfyCredentials>(NtfyProtocol.Json)!;
    var bytes = root.GetProperty("fragments").EnumerateArray().SelectMany(f => Decrypt(f.GetString()!, root.GetProperty("serverId").GetString()!, root.GetProperty("deviceId").GetString()!, fixtureCredentials)).ToArray();
    Check(JsonSerializer.Deserialize<BridgeEvent>(bytes, NtfyProtocol.Json) == root.GetProperty("bridgeEvent").Deserialize<BridgeEvent>(NtfyProtocol.Json), "Checked-in vector failed");
}
var directory = Path.Combine(Path.GetTempPath(), "Win2Mobile-ntfy-tests-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(directory);
try
{
    using var store = new BridgeStore(Path.Combine(directory, "bridge.db"), "Synthetic PC");
    store.Append(new("synthetic-before-pair", "app", "应用", "授权前", "不应交给新设备", DateTimeOffset.UtcNow));
    var path = Path.Combine(directory, "relay.dpapi"); var registry = new NtfyRegistry(path, store);
    registry.Configure(true, "https://ntfy.sh");
    using var pairing = new PairingService(store) { RelayCredentials = registry.Provision };
    var pending = pairing.CreateRequest(pairing.CurrentCode, "Synthetic phone");
    Check(pairing.GetStatus(pending.RequestId, pending.RequestSecret)?.Ntfy is null, "Pending request disclosed key");
    Check(pairing.GetStatus(pending.RequestId, new string('0', 64)) is null, "Wrong secret accepted");
    pairing.Approve(pending.RequestId);
    var approved = pairing.GetStatus(pending.RequestId, pending.RequestSecret)!;
    Check(approved.Ntfy is not null, "Approved pairing lacks relay credentials");
    Check(approved.StartSequence == 1 && approved.Ntfy!.StartSequence == 1, "LAN and relay pairing baselines differ or include pre-pair events");
    Check(!Encoding.UTF8.GetString(File.ReadAllBytes(path)).Contains(approved.Ntfy!.Key), "Key persisted in plaintext");
    var restart = new NtfyRegistry(path, store); restart.Configure(true, "https://ntfy.sh");
    Check(restart.Provision(approved.DeviceId!) == approved.Ntfy, "DPAPI credentials did not survive restart");
    var second = store.RegisterDevice("Other phone"); var secondCredentials = restart.Provision(second.DeviceId)!;
    Check(secondCredentials.Topic != approved.Ntfy.Topic && secondCredentials.Key != approved.Ntfy.Key, "Devices share credentials");
    var legacyPath = Path.Combine(directory, "legacy.dpapi");
    var legacy = new RelayDevice(second.DeviceId, secondCredentials with { Topic = "w2m_" + new string('a', 64) }, secondCredentials.StartSequence);
    File.WriteAllBytes(legacyPath, CertificateManager.Protect(JsonSerializer.SerializeToUtf8Bytes(new[] { legacy }, NtfyProtocol.Json), false));
    var repaired = new NtfyRegistry(legacyPath, store); repaired.Configure(true, "https://ntfy.sh");
    Check(repaired.RemovedLegacyTopics && repaired.ActiveDevices().Count == 0 && store.IsDeviceActive(second.DeviceId), "Legacy relay repair removed LAN authorization or retained invalid topic");
    Check(repaired.Provision(second.DeviceId)!.Topic.Length == 64, "Replacement topic exceeds ntfy limit");
    using var handler = new FakeRelay(approved.Ntfy.Topic);
    await using (var publisher = new NtfyPublisher(store, restart, handler, TimeSpan.Zero))
    {
        publisher.Start();
        var bridgeEvent = store.Append(new("synthetic-live", "app", "应用", "秘密标题", new string('中', 5000), DateTimeOffset.UtcNow))!;
        await WaitAsync(() => restart.ActiveDevices().Single(d => d.DeviceId == second.DeviceId).PublishedSequence == bridgeEvent.Sequence);
        await WaitAsync(() => handler.Throttled);
        Check(restart.ActiveDevices().Single(d => d.DeviceId == approved.DeviceId).PublishedSequence == approved.Ntfy.StartSequence, "429 advanced checkpoint");
        Check(handler.Bodies.All(b => !b.Contains("秘密标题")), "HTTP body contains plaintext");
        store.RevokeDevice(approved.DeviceId!);
        Check(pairing.GetStatus(pending.RequestId, pending.RequestSecret)?.Ntfy is null, "Revoked request disclosed key");
        restart.RemoveRevoked();
        Check(!restart.IsActive(approved.DeviceId!), "Revoked device still publishing");
        registry.Configure(false, "https://ntfy.sh");
        Check(registry.Provision(second.DeviceId) is null, "Disabled relay exposed credentials");
    }
    var reload = new NtfyRegistry(path, store); reload.Configure(true, "https://ntfy.sh");
    Check(reload.ActiveDevices().Single().PublishedSequence == secondCredentials.StartSequence + 1, "Checkpoint did not survive restart");
    var stalled = store.RegisterDevice("Stalled phone"); var stalledCredentials = reload.Provision(stalled.DeviceId)!;
    using var stalledHandler = new FakeRelay(stalledCredentials.Topic, block: true);
    await using (var publisher = new NtfyPublisher(store, reload, stalledHandler, TimeSpan.Zero))
    {
        publisher.Start();
        var next = store.Append(new("synthetic-next", "app", "应用", "Title", "Body", DateTimeOffset.UtcNow))!;
        await WaitAsync(() => stalledHandler.Started);
        await WaitAsync(() => reload.ActiveDevices().Single(d => d.DeviceId == second.DeviceId).PublishedSequence == next.Sequence);
        Check(!stalledHandler.Cancelled, "Stalled request unexpectedly finished");
        store.RevokeDevice(stalled.DeviceId);
        await WaitAsync(() => stalledHandler.Cancelled);
    }
    await RemotePairingTests.RunAsync(directory);
    var quotaPhone = store.RegisterDevice("Partial quota phone"); var quotaCredentials = reload.Provision(quotaPhone.DeviceId)!;
    using var quotaHandler = new PartialQuotaRelay(quotaCredentials.Topic);
    await using (var publisher = new NtfyPublisher(store, reload, quotaHandler, TimeSpan.Zero))
    {
        publisher.Start();
        var event3 = store.Append(new("partial-quota", "app", "App", "Title", new string('中', 2000), DateTimeOffset.UtcNow))!;
        await WaitAsync(() => reload.ActiveDevices().Single(d => d.DeviceId == quotaPhone.DeviceId).PublishedSequence == event3.Sequence);
        Check(quotaHandler.Attempts[0] == 1 && quotaHandler.Attempts[1] == 1 && quotaHandler.Attempts[2] == 2, "Partial 429 restarted accepted prefix or skipped failed fragment");
    }
    Console.WriteLine("PASS: AEAD routing/tamper/large Unicode fragments; C# vector; approval/secret/revocation; DPAPI reload/device isolation; HTTP 429 checkpoint and healthy-device delivery; persisted publication progress.");
}
finally { Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools(); Directory.Delete(directory, true); }
static async Task WaitAsync(Func<bool> predicate)
{
    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
    while (!predicate()) await Task.Delay(50, timeout.Token);
}
sealed class FakeRelay(string throttledTopic, bool block = false) : HttpMessageHandler
{
    public readonly System.Collections.Concurrent.ConcurrentQueue<string> Bodies = new();
    public volatile bool Throttled;
    public volatile bool Started;
    public volatile bool Cancelled;
    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        if (request.Method != HttpMethod.Post || request.RequestUri?.Scheme != "https" || request.Headers.Contains("Title") || request.Headers.Authorization is not null)
            throw new Exception("Unexpected plaintext HTTP metadata");
        var body = await request.Content!.ReadAsStringAsync(cancellationToken);
        Bodies.Enqueue(body);
        if (request.RequestUri!.AbsolutePath.EndsWith(throttledTopic, StringComparison.Ordinal))
        {
            if (block)
            {
                Started = true;
                try { await Task.Delay(Timeout.Infinite, cancellationToken); }
                catch (OperationCanceledException) { Cancelled = true; throw; }
            }
            Throttled = true;
        }
        return new(request.RequestUri!.AbsolutePath.EndsWith(throttledTopic, StringComparison.Ordinal) ? HttpStatusCode.TooManyRequests : HttpStatusCode.OK);
    }
}
