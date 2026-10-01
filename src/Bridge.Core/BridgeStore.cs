using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Data.Sqlite;

namespace Win2Mobile.Core;

/// <summary>One serialized connection; committed outbox sequences and credentials survive process restarts.</summary>
public sealed class BridgeStore : IDisposable
{
    private readonly object gate = new();
    private readonly SqliteConnection db;
    private bool disposed;
    public ServerIdentity Identity { get; }
    public event EventHandler? EventsChanged;
    public event EventHandler<DeviceRevokedEventArgs>? DeviceRevoked;

    public BridgeStore(string databasePath, string serverName)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(databasePath);
        ArgumentException.ThrowIfNullOrWhiteSpace(serverName);
        if (databasePath != ":memory:") Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(databasePath))!);
        db = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = databasePath, Mode = SqliteOpenMode.ReadWriteCreate }.ToString());
        db.Open();
        using var setup = Command("""
            PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA busy_timeout=5000;
            CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS events (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL UNIQUE,
                source_device_id TEXT NOT NULL, source_notification_id TEXT NOT NULL UNIQUE,
                app_id TEXT NOT NULL, app_name TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, occurred_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS devices (
                device_id TEXT PRIMARY KEY, device_name TEXT NOT NULL, created_at TEXT NOT NULL,
                token_hash TEXT NOT NULL UNIQUE, acknowledged_sequence INTEGER NOT NULL DEFAULT 0);
            """);
        setup.ExecuteNonQuery();
        using var identity = Command("INSERT OR IGNORE INTO metadata(key,value) VALUES ('serverId',$id);", ("$id", Guid.NewGuid().ToString()));
        identity.ExecuteNonQuery();
        using var read = Command("SELECT value FROM metadata WHERE key='serverId'");
        Identity = new ServerIdentity((string)read.ExecuteScalar()!, serverName);
    }

    private SqliteCommand Command(string sql, params (string, object)[] parameters)
    {
        var command = db.CreateCommand(); command.CommandText = sql;
        foreach (var (key, value) in parameters) command.Parameters.AddWithValue(key, value);
        return command;
    }

    public long HighWatermark { get { lock (gate) { Check(); using var cmd = Command("SELECT COALESCE(MAX(sequence),0) FROM events"); return (long)cmd.ExecuteScalar()!; } } }
    private void Check() => ObjectDisposedException.ThrowIf(disposed, this);
    private static string Stamp(DateTimeOffset value) => value.ToUniversalTime().ToString("O", CultureInfo.InvariantCulture);

    public BridgeEvent? Append(CapturedNotification notification)
    {
        ArgumentNullException.ThrowIfNull(notification);
        Validate(notification.SourceNotificationId, 1024, false); Validate(notification.AppId, 512, false);
        Validate(notification.AppName, 1024); Validate(notification.Title, 4096); Validate(notification.Body, 32768);
        BridgeEvent result;
        lock (gate)
        {
            Check();
            using var duplicate = Command("SELECT 1 FROM events WHERE source_notification_id=$source", ("$source", notification.SourceNotificationId));
            if (duplicate.ExecuteScalar() is not null) return null;
            var eventId = Guid.NewGuid().ToString();
            using var cmd = Command("""
                INSERT INTO events(event_id,source_device_id,source_notification_id,app_id,app_name,title,body,occurred_at)
                VALUES($event,$server,$source,$app,$name,$title,$body,$time) RETURNING sequence;
                """, ("$event", eventId), ("$server", Identity.ServerId), ("$source", notification.SourceNotificationId),
                ("$app", notification.AppId), ("$name", notification.AppName), ("$title", notification.Title), ("$body", notification.Body), ("$time", Stamp(notification.OccurredAt)));
            long sequence;
            try { sequence = (long)cmd.ExecuteScalar()!; }
            catch (SqliteException error) when (error.SqliteErrorCode == 19)
            {
                // A second store/process may commit this source between the read and insert.
                using var concurrentDuplicate = Command("SELECT 1 FROM events WHERE source_notification_id=$source", ("$source", notification.SourceNotificationId));
                if (concurrentDuplicate.ExecuteScalar() is not null) return null;
                throw;
            }
            result = new BridgeEvent(sequence, eventId, Identity.ServerId, notification.SourceNotificationId, notification.AppId, notification.AppName, notification.Title, notification.Body, notification.OccurredAt.ToUniversalTime());
        }
        EventsChanged?.Invoke(this, EventArgs.Empty);
        return result;
    }

    public IReadOnlyList<BridgeEvent> GetEvents(long after, int limit = 200)
    {
        if (after < 0) throw new ArgumentOutOfRangeException(nameof(after));
        if (limit is < 1 or > 200) throw new ArgumentOutOfRangeException(nameof(limit));
        lock (gate)
        {
            Check(); using var cmd = Command("SELECT sequence,event_id,source_device_id,source_notification_id,app_id,app_name,title,body,occurred_at FROM events WHERE sequence>$after ORDER BY sequence LIMIT $limit", ("$after", after), ("$limit", limit));
            using var rows = cmd.ExecuteReader(); var result = new List<BridgeEvent>();
            while (rows.Read()) result.Add(new BridgeEvent(rows.GetInt64(0), rows.GetString(1), rows.GetString(2), rows.GetString(3), rows.GetString(4), rows.GetString(5), rows.GetString(6), rows.GetString(7), DateTimeOffset.Parse(rows.GetString(8), CultureInfo.InvariantCulture)));
            return result;
        }
    }

    public DeviceCredential RegisterDevice(string deviceName)
    {
        Validate(deviceName, 128, false);
        var id = Guid.NewGuid().ToString(); var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        lock (gate) { Check(); using var cmd = Command("INSERT INTO devices(device_id,device_name,created_at,token_hash) VALUES($id,$name,$time,$hash)", ("$id", id), ("$name", deviceName), ("$time", Stamp(DateTimeOffset.UtcNow)), ("$hash", Hash(token))); cmd.ExecuteNonQuery(); }
        return new DeviceCredential(id, token);
    }

    public string? Authenticate(string token)
    {
        if (token.Length != 64) return null;
        lock (gate) { Check(); using var cmd = Command("SELECT device_id FROM devices WHERE token_hash=$hash", ("$hash", Hash(token))); return cmd.ExecuteScalar() as string; }
    }
    public bool IsDeviceActive(string deviceId)
    { lock (gate) { Check(); using var cmd = Command("SELECT 1 FROM devices WHERE device_id=$id", ("$id", deviceId)); return cmd.ExecuteScalar() is not null; } }
    private static string Hash(string token) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token)));

    public IReadOnlyList<PairedDevice> GetDevices()
    {
        lock (gate) { Check(); using var cmd = Command("SELECT device_id,device_name,created_at,acknowledged_sequence FROM devices ORDER BY created_at"); using var rows = cmd.ExecuteReader(); var result = new List<PairedDevice>(); while (rows.Read()) result.Add(new PairedDevice(rows.GetString(0), rows.GetString(1), DateTimeOffset.Parse(rows.GetString(2), CultureInfo.InvariantCulture), rows.GetInt64(3))); return result; }
    }
    public void RevokeDevice(string deviceId)
    {
        int removed;
        lock (gate) { Check(); using var cmd = Command("DELETE FROM devices WHERE device_id=$id", ("$id", deviceId)); removed = cmd.ExecuteNonQuery(); }
        if (removed > 0) DeviceRevoked?.Invoke(this, new DeviceRevokedEventArgs(deviceId));
    }
    public long Acknowledge(string deviceId, long sequence)
    {
        lock (gate)
        {
            Check(); if (sequence < 0 || sequence > HighWatermark) throw new ArgumentOutOfRangeException(nameof(sequence));
            using var cmd = Command("UPDATE devices SET acknowledged_sequence=MAX(acknowledged_sequence,$sequence) WHERE device_id=$id RETURNING acknowledged_sequence", ("$sequence", sequence), ("$id", deviceId));
            return cmd.ExecuteScalar() is long value ? value : throw new UnauthorizedAccessException();
        }
    }
    private static void Validate(string? value, int max, bool emptyAllowed = true)
    { if (value is null || value.Length > max || (!emptyAllowed && string.IsNullOrWhiteSpace(value))) throw new ArgumentException($"Invalid field (maximum {max} characters)."); }
    public void Dispose() { lock (gate) { if (disposed) return; disposed = true; db.Dispose(); } }
}
