using System.Security.Cryptography;
using System.Text;
using Win2Mobile.Core;

namespace Win2Mobile.Transport.Lan;

public record PendingPairing(string RequestId, string DeviceName, DateTimeOffset ExpiresAt);
public record PairingRequestResult(string RequestId, string RequestSecret, string Status, DateTimeOffset ExpiresAt);
public record PairingStatus(string Status, string? DeviceId, string? AccessToken, string ServerId, string ServerName);

public sealed class PairingService : IDisposable
{
    private sealed class Request(PendingPairing pending, byte[] secretHash)
    {
        public PendingPairing Pending { get; } = pending;
        public byte[] SecretHash { get; } = secretHash;
        public string Status { get; set; } = "pending";
        public DeviceCredential? Credential { get; set; }
    }
    private readonly object gate = new();
    private readonly BridgeStore store;
    private readonly TimeProvider clock;
    private readonly Dictionary<string, Request> requests = [];
    private readonly ITimer expiryTimer;
    private string code = "";
    private DateTimeOffset codeExpiresAt;
    private bool disposed;
    public event EventHandler? Changed;

    public PairingService(BridgeStore store, TimeProvider? clock = null)
    {
        this.store = store; this.clock = clock ?? TimeProvider.System;
        RefreshCode();
        expiryTimer = this.clock.CreateTimer(_ => Expire(), null, TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(1));
    }
    public IReadOnlyList<PendingPairing> PendingRequests
    {
        get
        {
            bool changed; PendingPairing[] pending;
            lock (gate)
            {
                ObjectDisposedException.ThrowIf(disposed, this);
                changed = ExpireLocked(); pending = requests.Values.Where(r => r.Status == "pending").Select(r => r.Pending).ToArray();
            }
            if (changed) Changed?.Invoke(this, EventArgs.Empty);
            return pending;
        }
    }
    public string CurrentCode { get { lock (gate) { ObjectDisposedException.ThrowIf(disposed, this); return code; } } }
    public void RefreshCode()
    {
        lock (gate) { ObjectDisposedException.ThrowIf(disposed, this); code = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)); codeExpiresAt = clock.GetUtcNow().AddSeconds(120); }
        Changed?.Invoke(this, EventArgs.Empty);
    }
    private static byte[] Hash(string value) => SHA256.HashData(Encoding.UTF8.GetBytes(value));
    public PairingRequestResult CreateRequest(string pairingCode, string deviceName)
    {
        if (string.IsNullOrWhiteSpace(deviceName) || deviceName.Length > 128 || pairingCode.Length != 64) throw new ArgumentException("Invalid pairing request.");
        bool changed = false;
        try
        {
            lock (gate)
            {
                ObjectDisposedException.ThrowIf(disposed, this); changed = ExpireLocked();
                if (clock.GetUtcNow() >= codeExpiresAt || !CryptographicOperations.FixedTimeEquals(Hash(pairingCode), Hash(code))) throw new UnauthorizedAccessException();
                if (requests.Values.Count(r => r.Status == "pending") >= 8 || requests.Count >= 128) throw new InvalidOperationException("Too many pairing requests.");
                var id = Guid.NewGuid().ToString(); var secret = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)); var expiresAt = clock.GetUtcNow().AddSeconds(120);
                requests.Add(id, new Request(new PendingPairing(id, deviceName.Trim(), expiresAt), Hash(secret)));
                changed = true;
                return new PairingRequestResult(id, secret, "pending", expiresAt);
            }
        }
        finally { if (changed) Changed?.Invoke(this, EventArgs.Empty); }
    }
    public PairingStatus? GetStatus(string requestId, string secret)
    {
        if (secret.Length != 64) return null;
        bool changed = false;
        try
        {
            lock (gate)
            {
                ObjectDisposedException.ThrowIf(disposed, this); changed = ExpireLocked();
                if (!requests.TryGetValue(requestId, out var request) || !CryptographicOperations.FixedTimeEquals(Hash(secret), request.SecretHash)) return null;
                var credential = request.Credential;
                if (credential is not null && !store.IsDeviceActive(credential.DeviceId)) { request.Status = "denied"; request.Credential = credential = null; changed = true; }
                return new PairingStatus(request.Status, credential?.DeviceId, credential?.AccessToken, store.Identity.ServerId, store.Identity.ServerName);
            }
        }
        finally { if (changed) Changed?.Invoke(this, EventArgs.Empty); }
    }
    public void Approve(string requestId)
    {
        bool changed = false;
        try
        {
            lock (gate)
            {
                ObjectDisposedException.ThrowIf(disposed, this); changed = ExpireLocked();
                if (!requests.TryGetValue(requestId, out var request) || request.Status != "pending") return;
                request.Credential = store.RegisterDevice(request.Pending.DeviceName); request.Status = "approved"; changed = true;
            }
        }
        finally { if (changed) Changed?.Invoke(this, EventArgs.Empty); }
    }
    public void Deny(string requestId)
    {
        bool changed = false;
        try
        {
            lock (gate) { ObjectDisposedException.ThrowIf(disposed, this); changed = ExpireLocked(); if (!requests.TryGetValue(requestId, out var request) || request.Status != "pending") return; request.Status = "denied"; changed = true; }
        }
        finally { if (changed) Changed?.Invoke(this, EventArgs.Empty); }
    }
    private bool ExpireLocked()
    {
        bool changed = false; var now = clock.GetUtcNow();
        foreach (var request in requests.Values.Where(r => now >= r.Pending.ExpiresAt && r.Status != "expired")) { request.Status = "expired"; request.Credential = null; changed = true; }
        foreach (var id in requests.Where(r => now >= r.Value.Pending.ExpiresAt.AddMinutes(2)).Select(r => r.Key).ToArray()) requests.Remove(id);
        return changed;
    }
    private void Expire() { bool changed; lock (gate) { if (disposed) return; changed = ExpireLocked(); } if (changed) Changed?.Invoke(this, EventArgs.Empty); }
    public void Dispose() { lock (gate) { if (disposed) return; disposed = true; requests.Clear(); code = ""; } expiryTimer.Dispose(); }
}
