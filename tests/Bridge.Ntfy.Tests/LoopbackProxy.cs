using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;

// A real CONNECT proxy routes a deliberately unresolvable host to our pinned TLS broker.
sealed class LoopbackProxy(int upstreamPort) : IAsyncDisposable
{
    private readonly TcpListener listener = new(IPAddress.Loopback, 0);
    private readonly CancellationTokenSource lifetime = new();
    private readonly ConcurrentBag<Task> connections = [];
    public ConcurrentQueue<string> Targets { get; } = new();
    private Task? worker;
    public string Url { get; private set; } = "";
    public void Start()
    {
        listener.Start(); Url = "http://127.0.0.1:" + ((IPEndPoint)listener.LocalEndpoint).Port;
        worker = Task.Run(async () => {
            try { while (!lifetime.IsCancellationRequested) {
                var client = await listener.AcceptTcpClientAsync(lifetime.Token);
                connections.Add(Tunnel(client));
            } } catch (OperationCanceledException) { }
        });
    }
    private async Task Tunnel(TcpClient client)
    {
        using (client)
        using (var upstream = new TcpClient())
        {
            try {
                var stream = client.GetStream(); var header = new List<byte>(); var buffer = new byte[1];
                while (header.Count < 8192) {
                    if (await stream.ReadAsync(buffer, lifetime.Token) == 0) return;
                    header.Add(buffer[0]);
                    if (header.Count >= 4 && Encoding.ASCII.GetString(header.TakeLast(4).ToArray()) == "\r\n\r\n") break;
                }
                var first = Encoding.ASCII.GetString(header.ToArray()).Split("\r\n")[0].Split(' ');
                if (first.Length != 3 || first[0] != "CONNECT" || first[1] != $"relay.invalid:{upstreamPort}") throw new IOException("Unexpected proxy target.");
                Targets.Enqueue(first[1]);
                await upstream.ConnectAsync(IPAddress.Loopback, upstreamPort, lifetime.Token);
                await stream.WriteAsync(Encoding.ASCII.GetBytes("HTTP/1.1 200 Connection Established\r\n\r\n"), lifetime.Token);
                var other = upstream.GetStream();
                using var cancelled = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);
                var upload = stream.CopyToAsync(other, cancelled.Token); var download = other.CopyToAsync(stream, cancelled.Token);
                await Task.WhenAny(upload, download); await cancelled.CancelAsync();
                try { await Task.WhenAll(upload, download); } catch (Exception e) when (e is OperationCanceledException or IOException) { }
            } catch (Exception e) when (e is OperationCanceledException or IOException or SocketException) { }
        }
    }
    public async ValueTask DisposeAsync()
    {
        await lifetime.CancelAsync(); listener.Stop();
        if (worker is not null) await worker;
        await Task.WhenAll(connections); lifetime.Dispose();
    }
}
