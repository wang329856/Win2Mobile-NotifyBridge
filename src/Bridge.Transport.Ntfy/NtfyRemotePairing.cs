using System.Net.WebSockets;
using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Win2Mobile.Transport.Lan;
using Win2Mobile.Core;

namespace Win2Mobile.Transport.Ntfy;

public record RemotePairPayload(string Schema, int ProtocolVersion, string ServerUrl, string RequestTopic, string ResponseTopic,
    string PairingKey, string ServerPublicKey, string ServerId, string ServerName, string BaseUrl, string CertificateSha256, DateTimeOffset ExpiresAt);
public record RemotePairRequest(string RequestId, string DeviceName, string ClientPublicKey);
public record PairCipher(string RequestId, string Nonce, string Data);

public static class RemotePairCrypto
{
    public static string Seal(string clear, byte[] key, string serverId, string topic, string direction, string requestId)
    {
        var nonce = RandomNumberGenerator.GetBytes(12); var bytes = Encoding.UTF8.GetBytes(clear);
        var data = new byte[bytes.Length + 16];
        using var aes = new AesGcm(key, 16);
        aes.Encrypt(nonce, bytes, data.AsSpan(0, bytes.Length), data.AsSpan(bytes.Length), Aad(serverId, topic, direction, requestId));
        return JsonSerializer.Serialize(new PairCipher(requestId, Convert.ToBase64String(nonce), Convert.ToBase64String(data)), NtfyProtocol.Json);
    }
    public static string Open(string wire, byte[] key, string serverId, string topic, string direction)
    {
        if (Encoding.UTF8.GetByteCount(wire) > 4096) throw new ArgumentException("Pairing message too large.");
        var envelope = JsonSerializer.Deserialize<PairCipher>(wire, NtfyProtocol.Json)!;
        if (!Guid.TryParseExact(envelope.RequestId, "D", out _)) throw new ArgumentException("Invalid pairing request ID.");
        var nonce = Convert.FromBase64String(envelope.Nonce); var encrypted = Convert.FromBase64String(envelope.Data);
        if (nonce.Length != 12 || encrypted.Length is < 17 or > 3000) throw new ArgumentException("Invalid pairing ciphertext.");
        var clear = new byte[encrypted.Length - 16]; using var aes = new AesGcm(key, 16);
        aes.Decrypt(nonce, encrypted.AsSpan(0, clear.Length), encrypted.AsSpan(clear.Length), clear, Aad(serverId, topic, direction, envelope.RequestId));
        try { return Encoding.UTF8.GetString(clear); } finally { CryptographicOperations.ZeroMemory(clear); }
    }
    private static byte[] Aad(string serverId, string topic, string direction, string requestId)
        => Encoding.UTF8.GetBytes($"win2mobile-ntfy-pair-v1|{serverId}|{topic}|{direction}|{requestId}");
    public static byte[] ResponseKey(ECDiffieHellman server, string clientPublicKey)
    {
        using var client = ECDiffieHellman.Create(); var bytes = Convert.FromBase64String(clientPublicKey);
        client.ImportSubjectPublicKeyInfo(bytes, out var read);
        if (read != bytes.Length || client.KeySize != 256 || client.ExportParameters(false).Curve.Oid.Value != "1.2.840.10045.3.1.7") throw new CryptographicException("P-256 key required.");
        return server.DeriveKeyFromHash(client.PublicKey, HashAlgorithmName.SHA256);
    }
    public static string VerificationCode(byte[] responseKey)
    {
        var hash = SHA256.HashData(responseKey);
        uint number = System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(hash);
        return (number % 1_000_000).ToString("D6", System.Globalization.CultureInfo.InvariantCulture);
    }
}

/// <summary>Ephemeral remote QR handshake. QR secret encrypts requests; ECDH isolates approved replies per phone.</summary>
public sealed class NtfyRemotePairing : IAsyncDisposable
{
    private sealed class Request(string localId, string secret, byte[] responseKey)
    {
        public string LocalId { get; } = localId;
        public string Secret { get; } = secret;
        public byte[] ResponseKey { get; } = responseKey;
        public DateTimeOffset RetryAt { get; set; }
    }
    private readonly PairingService pairing;
    private readonly ECDiffieHellman key = ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);
    private readonly byte[] pairingKey = RandomNumberGenerator.GetBytes(32);
    private readonly ConcurrentDictionary<string, Request> requests = new();
    private readonly ConcurrentDictionary<string, bool> seen = new();
    private readonly HttpClient http;
    private readonly CancellationTokenSource lifetime = new();
    private readonly RemotePairPayload payload;
    private Task? worker;
    public event Action<string>? Status;
    private readonly Func<ClientWebSocket> socketFactory;
    public NtfyRemotePairing(LanBridgeHost host, string lanHost, string serverUrl, HttpMessageHandler? handler = null, Func<ClientWebSocket>? socketFactory = null, string? proxyUrl = null)
    {
        pairing = host.Pairing; pairing.RefreshCode();
        http = new HttpClient(handler ?? NtfyNetwork.CreateHandler(proxyUrl)) { Timeout = TimeSpan.FromSeconds(15) };
        this.socketFactory = socketFactory ?? (() => NtfyNetwork.CreateSocket(proxyUrl));
        using var lan = JsonDocument.Parse(host.GetPairingPayload(lanHost)); var root = lan.RootElement;
        string Topic() => "w2m_" + Convert.ToHexString(RandomNumberGenerator.GetBytes(30)).ToLowerInvariant();
        payload = new("win2mobile-ntfy-pair", 1, NtfyProtocol.ValidateServer(serverUrl), Topic(), Topic(), Convert.ToBase64String(pairingKey),
            Convert.ToBase64String(key.ExportSubjectPublicKeyInfo()), root.GetProperty("serverId").GetString()!, root.GetProperty("serverName").GetString()!,
            root.GetProperty("baseUrl").GetString()!, root.GetProperty("certificateSha256").GetString()!, DateTimeOffset.UtcNow.AddSeconds(120));
    }
    public string Payload => JsonSerializer.Serialize(payload, NtfyProtocol.Json);
    public DateTimeOffset ExpiresAt => payload.ExpiresAt;
    public void Start() { worker ??= Task.Run(() => RunAsync(lifetime.Token)); }
    private async Task RunAsync(CancellationToken token)
    {
        using var session = CancellationTokenSource.CreateLinkedTokenSource(token); session.CancelAfter(TimeSpan.FromSeconds(250));
        var reader = ReadRequestsAsync(session.Token);
        try
        {
            while (!session.IsCancellationRequested)
            {
                foreach (var (id, request) in requests.ToArray())
                {
                    if (DateTimeOffset.UtcNow < request.RetryAt) continue;
                    try
                    {
                        var result = pairing.GetStatus(request.LocalId, request.Secret);
                        if (result is null || result.Status == "pending") continue;
                        var wire = RemotePairCrypto.Seal(JsonSerializer.Serialize(result, NtfyProtocol.Json), request.ResponseKey,
                            payload.ServerId, payload.ResponseTopic, "response", id);
                        using var message = new HttpRequestMessage(HttpMethod.Post, payload.ServerUrl + "/" + payload.ResponseTopic)
                        { Content = new StringContent(wire, Encoding.UTF8, "text/plain") };
                        message.Headers.Add("Firebase", "no");
                        using var response = await http.SendAsync(message, HttpCompletionOption.ResponseHeadersRead, session.Token);
                        if (!response.IsSuccessStatusCode) { request.RetryAt = DateTimeOffset.UtcNow.AddSeconds(30); Status?.Invoke("远程授权回复暂未交给中转，请稍候；超时后重新生成二维码。"); continue; }
                        requests.TryRemove(id, out _); CryptographicOperations.ZeroMemory(request.ResponseKey);
                        Status?.Invoke(result.Status == "approved" ? "远程授权密文已发送，手机保存结果请在手机查看。" : "远程配对请求已拒绝或过期。");
                    }
                    catch (OperationCanceledException) when (session.IsCancellationRequested) { break; }
                    catch (HttpRequestException) { request.RetryAt = DateTimeOffset.UtcNow.AddSeconds(30); Status?.Invoke("无法发布远程授权回复，请检查互联网。"); }
                    catch (OperationCanceledException) { request.RetryAt = DateTimeOffset.UtcNow.AddSeconds(30); Status?.Invoke("远程授权回复超时，稍后重试。"); }
                    catch (Exception) { request.RetryAt = DateTimeOffset.UtcNow.AddSeconds(5); Status?.Invoke("远程授权本地保存暂时失败，5 秒后重试。"); }
                }
                await Task.Delay(1000, session.Token);
            }
        }
        catch (OperationCanceledException) when (session.IsCancellationRequested) { }
        finally { await session.CancelAsync(); try { await reader; } catch (OperationCanceledException) { } }
    }
    private async Task ReadRequestsAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested && DateTimeOffset.UtcNow < payload.ExpiresAt)
        {
            using var socket = socketFactory(); socket.Options.KeepAliveInterval = TimeSpan.FromSeconds(25);
            try
            {
                using var connect = CancellationTokenSource.CreateLinkedTokenSource(token);
                connect.CancelAfter(TimeSpan.FromSeconds(10));
                await socket.ConnectAsync(new Uri(payload.ServerUrl.Replace("https://", "wss://", StringComparison.Ordinal) + "/" + payload.RequestTopic + "/ws?since=all"), connect.Token);
                Status?.Invoke("远程配对已连接中转，手机可扫码；请核对两端六位校验码后批准。");
                var buffer = new byte[8192];
                while (socket.State == WebSocketState.Open && !token.IsCancellationRequested && DateTimeOffset.UtcNow < payload.ExpiresAt)
                {
                    int length = 0; WebSocketReceiveResult frame;
                    do { frame = await socket.ReceiveAsync(new ArraySegment<byte>(buffer, length, buffer.Length - length), token); length += frame.Count;
                        if (length == buffer.Length && !frame.EndOfMessage) throw new InvalidDataException("Pairing frame too large."); }
                    while (!frame.EndOfMessage);
                    if (frame.MessageType == WebSocketMessageType.Close) break;
                    if (frame.MessageType != WebSocketMessageType.Text) continue;
                    try
                    {
                        using var message = JsonDocument.Parse(buffer.AsMemory(0, length)); var root = message.RootElement;
                        if (root.GetProperty("event").GetString() != "message" || root.GetProperty("topic").GetString() != payload.RequestTopic || DateTimeOffset.UtcNow >= payload.ExpiresAt) continue;
                        var clear = RemotePairCrypto.Open(root.GetProperty("message").GetString()!, pairingKey, payload.ServerId, payload.RequestTopic, "request");
                        var request = JsonSerializer.Deserialize<RemotePairRequest>(clear, NtfyProtocol.Json)!;
                        var envelope = JsonSerializer.Deserialize<PairCipher>(root.GetProperty("message").GetString()!, NtfyProtocol.Json)!;
                        if (request.RequestId != envelope.RequestId || seen.ContainsKey(request.RequestId) || seen.Count >= 8 || string.IsNullOrWhiteSpace(request.DeviceName) || request.DeviceName.Length > 80) continue;
                        var responseKey = RemotePairCrypto.ResponseKey(key, request.ClientPublicKey);
                        try
                        {
                            var name = new string(request.DeviceName.Where(c => !char.IsControl(c) && char.GetUnicodeCategory(c) != System.Globalization.UnicodeCategory.Format).ToArray());
                            var pending = pairing.CreateRequest(pairing.CurrentCode, name, RemotePairCrypto.VerificationCode(responseKey));
                            requests.TryAdd(request.RequestId, new(pending.RequestId, pending.RequestSecret, responseKey)); seen.TryAdd(request.RequestId, true);
                            Status?.Invoke("收到远程配对请求，请在配对手机页核对设备名称和六位校验码再批准。");
                        }
                        catch { CryptographicOperations.ZeroMemory(responseKey); throw; }
                    }
                    catch (Exception) { /* Malformed or unauthenticated frames must not poison cached replay. */ }
                }
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested) { return; }
            catch (Exception ex) { Status?.Invoke(NtfyNetwork.Failure(ex) + "；正在重试，二维码有效期 120 秒。"); }
            await Task.Delay(3000, token);
        }
    }
    public async ValueTask DisposeAsync()
    {
        try { await lifetime.CancelAsync(); if (worker is not null) await worker; }
        finally
        {
            foreach (var request in requests.Values)
            {
                try { pairing.Deny(request.LocalId); } catch (Exception) { }
                finally { CryptographicOperations.ZeroMemory(request.ResponseKey); }
            }
            requests.Clear(); key.Dispose(); CryptographicOperations.ZeroMemory(pairingKey); http.Dispose(); lifetime.Dispose();
        }
    }
}
