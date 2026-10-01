using System;
using System.Collections.Generic;

namespace Win2Mobile.Windows;

// Tracks only successfully handled visible notifications; an unsuccessful save stays retryable.
internal sealed class NotificationSnapshotTracker
{
    private readonly HashSet<string> _seen = new(StringComparer.Ordinal);
    private readonly HashSet<string> _current = new(StringComparer.Ordinal);
    private bool _baseline;
    private DateTimeOffset _baselineThrough = DateTimeOffset.MinValue;
    private DateTimeOffset _snapshotAt;

    public void ResetBaseline() => _baseline = false;
    public void BeginSnapshot(DateTimeOffset snapshotAt)
    {
        _snapshotAt = snapshotAt;
        _current.Clear();
    }
    public bool ShouldCapture(string key, DateTimeOffset occurredAt, bool paused, DateTimeOffset resumeAt)
    {
        _current.Add(key);
        return _baseline && !_seen.Contains(key) && !paused && occurredAt > resumeAt && occurredAt > _baselineThrough;
    }
    public void Failed(string key) => _current.Remove(key);
    public void CompleteSnapshot()
    {
        if (!_baseline) _baselineThrough = _snapshotAt;
        _seen.Clear();
        _seen.UnionWith(_current);
        _baseline = true;
    }
}
