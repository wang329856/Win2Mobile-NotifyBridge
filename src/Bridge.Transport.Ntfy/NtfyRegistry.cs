using System.Security.Cryptography;
using System.Text.Json;
using Win2Mobile.Core;
using Win2Mobile.Transport.Lan;

namespace Win2Mobile.Transport.Ntfy;

public record RelayDevice(string DeviceId, NtfyCredentials Credentials, long PublishedSequence);

/// <summary>One DPAPI-protected file. A checkpoint is distinct from phone acknowledgement.</summary>
public sealed class NtfyRegistry
{
    private readonly object gate = new();
    private readonly string path;
    private readonly BridgeStore store;
    private readonly List<RelayDevice> devices;
    private string? serverUrl;
    public bool RemovedLegacyTopics { get; private set; }
    public NtfyRegistry(string path, BridgeStore store)
    {
        this.path = path; this.store = store;
        if (File.Exists(path))
        {
            var clear = CertificateManager.Protect(File.ReadAllBytes(path), true);
            try { devices = JsonSerializer.Deserialize<List<RelayDevice>>(clear, NtfyProtocol.Json) ?? throw new InvalidDataException("Invalid relay registry."); }
            finally { CryptographicOperations.ZeroMemory(clear); }
            var valid = new List<RelayDevice>();
            foreach (var device in devices)
            {
                bool legacy = System.Text.RegularExpressions.Regex.IsMatch(device.Credentials.Topic, "^w2m_[a-f0-9]{64}$");
                NtfyProtocol.Validate(legacy ? device.Credentials with { Topic = device.Credentials.Topic[..64] } : device.Credentials);
                if (device.PublishedSequence < device.Credentials.StartSequence) throw new InvalidDataException("Invalid relay checkpoint.");
                if (legacy) RemovedLegacyTopics = true; else valid.Add(device);
            }
            if (RemovedLegacyTopics) { Save(valid); devices.Clear(); devices.AddRange(valid); }
        }
        else devices = [];
    }
    public void Configure(bool enabled, string url)
    {
        var normalized = NtfyProtocol.ValidateServer(url);
        lock (gate)
        {
            if (enabled && devices.Any(d => store.IsDeviceActive(d.DeviceId) && d.Credentials.ServerUrl != normalized))
                throw new InvalidOperationException("已有设备绑定其他 ntfy 服务，请先撤销这些设备并重新配对，再更换服务地址。");
            serverUrl = enabled ? normalized : null;
        }
    }
    public NtfyCredentials? Provision(string deviceId)
    {
        lock (gate)
        {
            if (serverUrl is null || !store.IsDeviceActive(deviceId)) return null;
            var existing = devices.FirstOrDefault(d => d.DeviceId == deviceId);
            if (existing is not null) return existing.Credentials;
            long start = store.GetStartSequence(deviceId);
            var credentials = new NtfyCredentials(serverUrl, "w2m_" + Convert.ToHexString(RandomNumberGenerator.GetBytes(30)).ToLowerInvariant(), Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)), start);
            var updated = devices.Append(new RelayDevice(deviceId, credentials, start)).ToList();
            Save(updated); devices.Add(updated[^1]); return credentials;
        }
    }
    public IReadOnlyList<RelayDevice> ActiveDevices()
    {
        lock (gate) { return serverUrl is null ? [] : devices.Where(d => d.Credentials.ServerUrl == serverUrl && store.IsDeviceActive(d.DeviceId)).ToArray(); }
    }
    public bool IsActive(string deviceId) { lock (gate) { return serverUrl is not null && devices.Any(d => d.DeviceId == deviceId && d.Credentials.ServerUrl == serverUrl) && store.IsDeviceActive(deviceId); } }
    public void MarkPublished(string deviceId, long sequence)
    {
        lock (gate)
        {
            var index = devices.FindIndex(d => d.DeviceId == deviceId);
            if (index < 0 || sequence <= devices[index].PublishedSequence) return;
            var updated = devices.ToList(); updated[index] = updated[index] with { PublishedSequence = sequence };
            Save(updated); devices[index] = updated[index];
        }
    }
    public void RemoveRevoked()
    {
        lock (gate) { var updated = devices.Where(d => store.IsDeviceActive(d.DeviceId)).ToList(); if (updated.Count == devices.Count) return; Save(updated); devices.Clear(); devices.AddRange(updated); }
    }
    private void Save(List<RelayDevice> updated)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        var clear = JsonSerializer.SerializeToUtf8Bytes(updated, NtfyProtocol.Json);
        var temporary = path + ".tmp";
        try { File.WriteAllBytes(temporary, CertificateManager.Protect(clear, false)); File.Move(temporary, path, true); }
        finally { CryptographicOperations.ZeroMemory(clear); if (File.Exists(temporary)) File.Delete(temporary); }
    }
}
