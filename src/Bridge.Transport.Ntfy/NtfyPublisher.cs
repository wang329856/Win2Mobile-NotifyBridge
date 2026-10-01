using System.Collections.Concurrent;
using System.Net;
using System.Text;
using Win2Mobile.Core;

namespace Win2Mobile.Transport.Ntfy;

public sealed class NtfyPublisher : IAsyncDisposable
{
    private sealed record Worker(CancellationTokenSource Cancellation, Task Task);
    private readonly BridgeStore store;
    private readonly NtfyRegistry registry;
    private readonly HttpClient http;
    private readonly TimeSpan requestInterval;
    private readonly CancellationTokenSource lifetime = new();
    private readonly ConcurrentDictionary<string, Worker> workers = new();
    private readonly ConcurrentDictionary<string, string> states = new();
    private Task? coordinator;
    public event Action<string>? Status;
    private void Report(string deviceId, string status)
    {
        states[deviceId] = status;
        Status?.Invoke(string.Join(" · ", states.Values.Distinct()));
    }
    public NtfyPublisher(BridgeStore store, NtfyRegistry registry, HttpMessageHandler? handler = null, TimeSpan? requestInterval = null, string? proxyUrl = null)
    {
        this.store = store; this.registry = registry;
        http = new HttpClient(handler ?? NtfyNetwork.CreateHandler(proxyUrl)) { Timeout = TimeSpan.FromSeconds(20) };
        this.requestInterval = requestInterval ?? TimeSpan.FromSeconds(5);
        if (this.requestInterval < TimeSpan.Zero) throw new ArgumentOutOfRangeException(nameof(requestInterval));
        store.DeviceRevoked += DeviceRevoked;
    }
    public void Start() { coordinator ??= Task.Run(() => RunAsync(lifetime.Token)); }
    private void DeviceRevoked(object? sender, DeviceRevokedEventArgs args)
    {
        if (workers.TryGetValue(args.DeviceId, out var worker))
        {
            try { worker.Cancellation.Cancel(); } catch (ObjectDisposedException) { }
        }
    }
    private async Task RunAsync(CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    registry.RemoveRevoked();
                    var devices = registry.ActiveDevices();
                    foreach (var id in workers.Keys.Where(id => devices.All(d => d.DeviceId != id)))
                    {
                        if (!workers.TryRemove(id, out var old)) continue;
                        await old.Cancellation.CancelAsync();
                        try { await old.Task; } catch (OperationCanceledException) { }
                        old.Cancellation.Dispose();
                        states.TryRemove(id, out _);
                    }
                    foreach (var device in devices)
                    {
                        if (workers.ContainsKey(device.DeviceId)) continue;
                        var cancellation = CancellationTokenSource.CreateLinkedTokenSource(token);
                        workers[device.DeviceId] = new Worker(cancellation, Task.Run(() => RunDeviceAsync(device, cancellation.Token), CancellationToken.None));
                        Report(device.DeviceId, "跨网络：已准备接收新通知；中转连接不代表手机已保存。");
                    }
                    if (devices.Count == 0) Status?.Invoke("跨网络：等待已授权手机；开启后需重新扫码获取中转凭据。");
                }
                catch (Exception) { Status?.Invoke("跨网络：本地中转状态暂不可用，稍后重试。"); }
                await Task.Delay(TimeSpan.FromSeconds(1), token);
            }
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested) { }
        finally
        {
            foreach (var worker in workers.Values) await worker.Cancellation.CancelAsync();
            foreach (var worker in workers.Values)
            {
                try { await worker.Task; } catch (OperationCanceledException) { }
                worker.Cancellation.Dispose();
            }
            workers.Clear();
        }
    }
    private async Task RunDeviceAsync(RelayDevice device, CancellationToken token)
    {
        long after = device.PublishedSequence;
        BridgeEvent? pending = null; IReadOnlyList<string>? fragments = null; int nextFragment = 0;
        while (!token.IsCancellationRequested && registry.IsActive(device.DeviceId))
        {
            var delay = requestInterval > TimeSpan.Zero ? requestInterval : TimeSpan.FromSeconds(1);
            try
            {
                var floor = store.QueueFloor;
                if (after < floor) { after = floor; registry.MarkPublished(device.DeviceId, after); }
                if (pending is not null && pending.Sequence <= floor) { pending = null; fragments = null; nextFragment = 0; }
                var bridgeEvent = pending ?? store.GetEvents(after, 1).FirstOrDefault();
                if (bridgeEvent is not null)
                {
                    bool complete = true;
                    pending = bridgeEvent; fragments ??= NtfyProtocol.Encrypt(bridgeEvent, device.DeviceId, device.Credentials);
                    while (nextFragment < fragments.Count)
                    {
                        token.ThrowIfCancellationRequested();
                        if (!registry.IsActive(device.DeviceId)) return;
                        if (bridgeEvent.Sequence <= store.QueueFloor) { complete = false; break; }
                        using var request = new HttpRequestMessage(HttpMethod.Post, device.Credentials.ServerUrl + "/" + device.Credentials.Topic)
                        { Content = new StringContent(fragments[nextFragment], Encoding.UTF8, "text/plain") };
                        request.Headers.Add("Firebase", "no"); // Own Android app consumes WSS; no plaintext push metadata.
                        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, token);
                        if (!response.IsSuccessStatusCode)
                        {
                            complete = false;
                            var retry = response.Headers.RetryAfter;
                            var requested = retry?.Delta ?? (retry?.Date is { } date ? date - DateTimeOffset.UtcNow :
                                TimeSpan.FromSeconds(response.StatusCode == HttpStatusCode.TooManyRequests ? 900 : 30));
                            delay = TimeSpan.FromSeconds(Math.Clamp(requested.TotalSeconds, 5, 86400));
                            Report(device.DeviceId, response.StatusCode == HttpStatusCode.TooManyRequests ? "跨网络：ntfy 限流，通知保存在电脑，稍后自动重试。" : $"跨网络：中转返回 HTTP {(int)response.StatusCode}，稍后重试。");
                            break;
                        }
                        nextFragment++;
                        if (nextFragment < fragments.Count) await Task.Delay(requestInterval, token);
                    }
                    if (complete && registry.IsActive(device.DeviceId))
                    {
                        registry.MarkPublished(device.DeviceId, bridgeEvent.Sequence);
                        after = bridgeEvent.Sequence;
                        pending = null; fragments = null; nextFragment = 0;
                        Report(device.DeviceId, $"跨网络：第 {after} 条通知的密文已交给中转；手机保存状态请在手机查看。");
                    }
                }
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested) { return; }
            catch (Exception)
            {
                delay = TimeSpan.FromSeconds(30);
                Report(device.DeviceId, "跨网络：发布或保存进度失败，通知保留在电脑，30 秒后重试。");
            }
            await Task.Delay(delay, token);
        }
    }
    public async ValueTask DisposeAsync()
    {
        store.DeviceRevoked -= DeviceRevoked;
        await lifetime.CancelAsync();
        if (coordinator is not null) await coordinator;
        http.Dispose(); lifetime.Dispose();
    }
}
