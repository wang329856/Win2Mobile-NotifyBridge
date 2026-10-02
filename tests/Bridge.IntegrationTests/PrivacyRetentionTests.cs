using System.Text;
using Microsoft.Data.Sqlite;
using Win2Mobile.Core;

internal static class PrivacyRetentionTests
{
    public static void Run(string directory)
    {
        var clock = new TestClock();
        var path = Path.Combine(directory, "privacy-expiry.db");
        var marker = "SYNTHETIC_EXPIRY_PRIVATE_91F5";
        string identity; DeviceCredential device;
        using (var store = new BridgeStore(path, "Privacy PC", true, clock))
        {
            identity = store.Identity.ServerId; device = store.RegisterDevice("Phone");
            store.Append(new("private-1", "app", "App", "Title", marker + new string('X', 16000), clock.GetUtcNow()));
            Require(HasMarker(path + "-wal", marker), "Fixture must contain plaintext before expiry.");
            clock.Advance(TimeSpan.FromHours(25)); store.PruneQueue();
            Require(store.GetEvents(0).Count == 0 && !store.StorageCleanupPending, "Expiry cleanup did not complete.");
            AssertAbsent(path, marker);
        }
        AssertAbsent(path, marker);
        using (var store = new BridgeStore(path, "Privacy PC", true, clock))
        {
            Require(store.Identity.ServerId == identity && store.Authenticate(device.AccessToken) == device.DeviceId && store.HighWatermark == 1,
                "Privacy cleanup changed identity, credentials or progress.");
        }

        path = Path.Combine(directory, "privacy-capacity.db"); marker = "SYNTHETIC_CAPACITY_PRIVATE_63A2";
        using (var store = new BridgeStore(path, "Capacity PC", true, clock))
        {
            store.Append(new("capacity-0", "app", "App", "Title", marker, clock.GetUtcNow()));
            for (var i = 1; i <= 1000; i++) store.Append(new("capacity-" + i, "app", "App", "Title", "Body", clock.GetUtcNow()));
            Require(store.QueueFloor == 1 && store.HighWatermark == 1001 && !store.StorageCleanupPending, "Capacity cleanup failed.");
            AssertAbsent(path, marker);
        }

        path = Path.Combine(directory, "privacy-legacy.db"); marker = "SYNTHETIC_LEGACY_PRIVATE_14B7";
        using (var store = new BridgeStore(path, "Legacy PC", false, clock))
        {
            store.Append(new("legacy-private", "app", "App", "Title", new string('X', 8000) + marker + new string('X', 8000), clock.GetUtcNow()));
            store.Append(new("legacy-kept", "app", "App", "Kept", "Keep this record", clock.GetUtcNow()));
            identity = store.Identity.ServerId; device = store.RegisterDevice("Kept phone"); store.Acknowledge(device.DeviceId, 2);
        }
        // Reproduce a pre-fix database, including its deleted free-page plaintext.
        using (var legacy = new SqliteConnection($"Data Source={path};Pooling=False"))
        {
            legacy.Open();
            Execute(legacy, "PRAGMA secure_delete=OFF; DELETE FROM metadata WHERE key='privacyCleanupVersion'; DELETE FROM events WHERE sequence=1; PRAGMA wal_checkpoint(TRUNCATE);");
        }
        Require(HasMarker(path, marker), "Legacy fixture must reproduce deleted plaintext.");
        using (var store = new BridgeStore(path, "Legacy PC", true, clock))
        {
            AssertAbsent(path, marker);
            Require(store.GetEvents(0).Single().Body == "Keep this record" && store.HighWatermark == 2 && store.Identity.ServerId == identity &&
                store.Authenticate(device.AccessToken) == device.DeviceId && store.GetDevices().Single().AcknowledgedSequence == 2 && store.GetStartSequence(device.DeviceId) == 2,
                "Legacy cleanup changed live content or authorization progress.");
            Require(store.Append(new("after-migration", "app", "App", "Title", "Body", clock.GetUtcNow()))?.Sequence == 3, "VACUUM reset AUTOINCREMENT.");
        }

        path = Path.Combine(directory, "privacy-busy.db"); marker = "SYNTHETIC_BUSY_PRIVATE_27D8";
        using (var store = new BridgeStore(path, "Busy PC", true, clock))
        {
            store.Append(new("busy", "app", "App", "Title", marker, clock.GetUtcNow()));
            using (var reader = new SqliteConnection($"Data Source={path};Pooling=False"))
            {
                reader.Open(); Execute(reader, "BEGIN; SELECT COUNT(*) FROM events;");
                clock.Advance(TimeSpan.FromHours(25)); store.PruneQueue();
                Require(store.StorageCleanupPending, "Busy checkpoint must not claim successful erasure.");
            }
            store.PruneQueue();
            Require(!store.StorageCleanupPending, "Checkpoint must retry even when no additional records expire.");
            AssertAbsent(path, marker);
        }
        Console.WriteLine("PASS: expired/capacity plaintext absent from live/closed DB and WAL, legacy cleanup preserves state, busy checkpoint retries.");
    }
    private static void Execute(SqliteConnection connection, string sql) { using var command = connection.CreateCommand(); command.CommandText = sql; command.ExecuteNonQuery(); }
    private static bool HasMarker(string path, string marker)
    {
        if (!File.Exists(path)) return false;
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        using var bytes = new MemoryStream(); stream.CopyTo(bytes);
        return Encoding.UTF8.GetString(bytes.ToArray()).Contains(marker, StringComparison.Ordinal);
    }
    private static void AssertAbsent(string path, string marker)
    { foreach (var suffix in new[] { "", "-wal" }) Require(!HasMarker(path + suffix, marker), "Deleted plaintext remains in " + suffix); }
    private static void Require(bool value, string message) { if (!value) throw new InvalidOperationException(message); }
}
