using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Authentication;

namespace Win2Mobile.Transport.Ntfy;

public static class NtfyNetwork
{
    public static string ValidateProxy(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return "";
        if (!Uri.TryCreate(value.Trim(), UriKind.Absolute, out var uri) || uri.Scheme != "http" ||
            string.IsNullOrEmpty(uri.Host) || !string.IsNullOrEmpty(uri.UserInfo) ||
            !string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment) ||
            (uri.AbsolutePath != "/" && uri.AbsolutePath != ""))
            throw new ArgumentException("代理地址须为 http://主机:端口，不含账号或路径。");
        return uri.GetLeftPart(UriPartial.Authority);
    }
    public static HttpClientHandler CreateHandler(string? proxyUrl = null)
    {
        var handler = new HttpClientHandler { AllowAutoRedirect = false };
        var proxy = ValidateProxy(proxyUrl);
        if (proxy.Length != 0) { handler.Proxy = new WebProxy(proxy); handler.UseProxy = true; }
        return handler;
    }
    public static ClientWebSocket CreateSocket(string? proxyUrl = null)
    {
        var socket = new ClientWebSocket();
        var proxy = ValidateProxy(proxyUrl);
        if (proxy.Length != 0) socket.Options.Proxy = new WebProxy(proxy);
        return socket;
    }
    public static string Failure(Exception exception)
    {
        for (Exception? error = exception; error is not null; error = error.InnerException)
        {
            if (error is AuthenticationException) return "TLS 握手或证书验证失败，请检查网络、代理与系统时间";
            if (error is SocketException socket && socket.SocketErrorCode is SocketError.HostNotFound or SocketError.NoData or SocketError.TryAgain)
                return "无法解析中转地址，请检查 DNS 或代理";
        }
        return exception is OperationCanceledException ? "中转连接超时，请检查网络或代理" : "无法连接中转，请检查网络或代理";
    }
}
