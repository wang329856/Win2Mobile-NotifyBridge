using System.Net;
using System.Text.Json;
using Win2Mobile.Transport.Ntfy;

sealed class PartialQuotaRelay(string topic) : HttpMessageHandler
{
    public readonly System.Collections.Concurrent.ConcurrentDictionary<int, int> Attempts = new();
    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        if (!request.RequestUri!.AbsolutePath.EndsWith(topic, StringComparison.Ordinal)) return new(HttpStatusCode.OK);
        var part = JsonSerializer.Deserialize<NtfyFragment>(await request.Content!.ReadAsStringAsync(cancellationToken), NtfyProtocol.Json)!;
        var attempt = Attempts.AddOrUpdate(part.Index, 1, (_, previous) => previous + 1);
        if (part.Index == 2 && attempt == 1)
        {
            var retry = new HttpResponseMessage(HttpStatusCode.TooManyRequests);
            retry.Headers.RetryAfter = new System.Net.Http.Headers.RetryConditionHeaderValue(TimeSpan.FromSeconds(5)); return retry;
        }
        return new(HttpStatusCode.OK);
    }
}