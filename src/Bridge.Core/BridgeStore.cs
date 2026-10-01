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
    private readonly bool transientQueue;
    private readonly TimeProvider clock;
    private bool disposed;
    public ServerIdentity Identity { get; }
    public event EventHandler? EventsChanged;
    public event EventHandler<DeviceRevokedEventArgs>? DeviceRevoked;

    public BridgeStore(string databasePath, string serverName, bool transientQueue = false, TimeProvider? clock = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(databasePath);
        ArgumentException.ThrowIfNullOrWhiteSpace(serverName);
        this.transientQueue = transientQueue; this.clock = clock ?? TimeProvider.System;
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
        EnsureColumn("events", "captured_at", "TEXT NOT NULL DEFAULT ''");
        EnsureColumn("devices", "start_sequence", "INTEGER NOT NULL DEFAULT 0");
        using (var migrate = Command("UPDATE events SET captured_at=occurred_at WHERE captured_at=''")) migrate.ExecuteNonQuery();
        using var identity = Command("INSERT OR IGNORE INTO metadata(key,value) VALUES ('serverId',$id);", ("$id", Guid.NewGuid().ToString()));
        identity.ExecuteNonQuery();
        using var read = Command("SELECT value FROM metadata WHERE key='serverId'");
        Identity = new ServerIdentity((string)read.ExecuteScalar()!, serverName);
        PruneQueue();
    }

    private void EnsureColumn(string table, string column, string declaration)
    {
        using var schema = Command($"PRAGMA table_info({table})");
        using var rows = schema.ExecuteReader();
        bool exists = false; while (rows.Read()) if (rows.GetString(1) == column) exists = true;
        rows.Close();
        if (!exists) { using var add = Command($"ALTER TABLE {table} ADD COLUMN {column} {declaration}"); add.ExecuteNonQuery(); }
    }

    private SqliteCommand Command(string sql, params (string, object)[] parameters)
    {
        var command = db.CreateCommand(); command.CommandText = sql;
        foreach (var (key, value) in parameters) command.Parameters.AddWithValue(key, value);
        return command;
    }

    public long HighWatermark { get { lock (gate) { Check(); using var cmd = Command("SELECT COALESCE((SELECT seq FROM sqlite_sequence WHERE name='events'),0)"); return (long)cmd.ExecuteScalar()!; } } }
    public long QueueFloor { get { lock (gate) { Check(); using var cmd = Command("SELECT value FROM metadata WHERE key='queueFloor'"); return long.TryParse(cmd.ExecuteScalar() as string, out var floor) ? floor : 0; } } }
    public long GetStartSequence(string deviceId)
    { lock (gate) { Check(); using var cmd = Command("SELECT start_sequence FROM devices WHERE device_id=$id", ("$id", deviceId)); return cmd.ExecuteScalar() is long value ? value : throw new UnauthorizedAccessException(); } }

    public void PruneQueue()
    {
        if (!transientQueue) return;
        lock (gate)
        {
            Check();
            using var cutoff = Command("""
                SELECT MAX(value) FROM (
                  SELECT COALESCE(MAX(sequence),0) AS value FROM events WHERE captured_at<=$cutoff
                  UNION ALL SELECT COALESCE((SELECT sequence-1 FROM events ORDER BY sequence DESC LIMIT 1 OFFSET 999),0));
                """, ("$cutoff", Stamp(clock.GetUtcNow().AddHours(-24))));
            var through = (long)cutoff.ExecuteScalar()!;
            if (through <= QueueFloor) return;
            using var transaction = db.BeginTransaction();
            using var remove = Command("DELETE FROM events WHERE sequence<=$through", ("$through", through)); remove.Transaction = transaction; remove.ExecuteNonQuery();
            using var save = Command("INSERT INTO metadata(key,value) VALUES('queueFloor',$floor) ON CONFLICT(key) DO UPDATE SET value=excluded.value", ("$floor", through.ToString(CultureInfo.InvariantCulture))); save.Transaction = transaction; save.ExecuteNonQuery();
            transaction.Commit();
        }
    }
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
                INSERT INTO events(event_id,source_device_id,source_notification_id,app_id,app_name,title,body,occurred_at,captured_at)
                VALUES($event,$server,$source,$app,$name,$title,$body,$time,$captured) RETURNING sequence;
                """, ("$event", eventId), ("$server", Identity.ServerId), ("$source", notification.SourceNotificationId),
                ("$app", notification.AppId), ("$name", notification.AppName), ("$title", notification.Title), ("$body", notification.Body), ("$time", Stamp(notification.OccurredAt)), ("$captured", Stamp(clock.GetUtcNow())));
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
        PruneQueue();
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
        lock (gate) { Check(); using var cmd = Command("INSERT INTO devices(device_id,device_name,created_at,token_hash,start_sequence,acknowledged_sequence) VALUES($id,$name,$time,$hash,$start,$start)", ("$id", id), ("$name", deviceName), ("$time", Stamp(clock.GetUtcNow())), ("$hash", Hash(token)), ("$start", HighWatermark)); cmd.ExecuteNonQuery(); }
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
