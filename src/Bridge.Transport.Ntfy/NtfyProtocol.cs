using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Win2Mobile.Core;

namespace Win2Mobile.Transport.Ntfy;

public record NtfyFragment(int V, string Id, int Index, int Count, string Nonce, string Data);

/// <summary>Every fragment is independently authenticated, including routing and assembly metadata.</summary>
public static partial class NtfyProtocol
{
    public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    public const int FragmentBytes = 2400;
    public const int MaxFragments = 128;
    [GeneratedRegex("^w2m_[a-f0-9]{60}$", RegexOptions.CultureInvariant)] private static partial Regex TopicPattern();
    public static string ValidateServer(string value)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) || uri.Scheme != "https" || string.IsNullOrEmpty(uri.Host) ||
            uri.UserInfo.Length != 0 || uri.Query.Length != 0 || uri.Fragment.Length != 0 || uri.AbsolutePath != "/")
            throw new ArgumentException("ntfy 地址必须是 HTTPS 服务器根地址，不能含账号、路径或参数。");
        return uri.GetLeftPart(UriPartial.Authority);
    }
    public static void Validate(NtfyCredentials credentials)
    {
        ValidateServer(credentials.ServerUrl);
        if (!TopicPattern().IsMatch(credentials.Topic) || Convert.FromBase64String(credentials.Key).Length != 32 || credentials.StartSequence < 0)
            throw new ArgumentException("Invalid relay credentials.");
    }
    public static byte[] AssociatedData(string serverId, string deviceId, string topic, string eventId, int index, int count)
        => Encoding.UTF8.GetBytes($"win2mobile-ntfy-v1|{serverId}|{deviceId}|{topic}|{eventId}|{index}|{count}");
    public static IReadOnlyList<string> Encrypt(BridgeEvent bridgeEvent, string deviceId, NtfyCredentials credentials)
    {
        Validate(credentials);
        var bytes = JsonSerializer.SerializeToUtf8Bytes(bridgeEvent, Json);
        var key = Convert.FromBase64String(credentials.Key);
        try
        {
            int count = (bytes.Length + FragmentBytes - 1) / FragmentBytes;
            if (count is < 1 or > MaxFragments) throw new ArgumentException("Event exceeds relay size limit.");
            var result = new List<string>(count);
            using var aes = new AesGcm(key, 16);
            for (int index = 0; index < count; index++)
            {
                var chunk = bytes.AsSpan(index * FragmentBytes, Math.Min(FragmentBytes, bytes.Length - index * FragmentBytes));
                var nonce = RandomNumberGenerator.GetBytes(12); var ciphertext = new byte[chunk.Length]; var tag = new byte[16];
                aes.Encrypt(nonce, chunk, ciphertext, tag, AssociatedData(bridgeEvent.SourceDeviceId, deviceId, credentials.Topic, bridgeEvent.EventId, index, count));
                var data = new byte[ciphertext.Length + tag.Length]; ciphertext.CopyTo(data, 0); tag.CopyTo(data, ciphertext.Length);
                var wire = JsonSerializer.Serialize(new NtfyFragment(1, bridgeEvent.EventId, index, count, Convert.ToBase64String(nonce), Convert.ToBase64String(data)), Json);
                if (Encoding.UTF8.GetByteCount(wire) > 4096) throw new InvalidOperationException("Relay fragment exceeds ntfy message limit.");
                result.Add(wire);
            }
            return result;
        }
        finally { CryptographicOperations.ZeroMemory(key); CryptographicOperations.ZeroMemory(bytes); }
    }
}
