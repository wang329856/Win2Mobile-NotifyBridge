using Win2Mobile.Windows;
using Win2Mobile.Core;
using System.Collections.ObjectModel;
using System.Windows.Controls;

static void Check(bool value, string message) { if (!value) throw new InvalidOperationException(message); }
var tracker = new NotificationSnapshotTracker();
var now = DateTimeOffset.UtcNow;
var noBoundary = DateTimeOffset.MinValue;
tracker.BeginSnapshot(now.AddSeconds(-10));
Check(!tracker.ShouldCapture("startup-old", now.AddMinutes(-1), false, noBoundary), "Startup content must establish the baseline without replay.");
tracker.CompleteSnapshot();

tracker.BeginSnapshot(now);
Check(!tracker.ShouldCapture("old-unreadable-at-startup", now.AddMinutes(-2), false, noBoundary), "Old content unreadable during baseline must not replay when parsing recovers.");
Check(!tracker.ShouldCapture("startup-old", now.AddMinutes(-1), false, noBoundary), "Existing visible notification must be deduplicated.");
Check(tracker.ShouldCapture("temporary-save-failure", now, false, noBoundary), "New toast must be captured.");
tracker.Failed("temporary-save-failure");
Check(tracker.ShouldCapture("malformed-toast", now, false, noBoundary), "Malformed toast enters per-item handling.");
tracker.Failed("malformed-toast");
Check(tracker.ShouldCapture("valid-after-malformed", now, false, noBoundary), "A malformed toast must not block later valid items.");
tracker.CompleteSnapshot();

tracker.BeginSnapshot(now);
Check(tracker.ShouldCapture("temporary-save-failure", now, false, noBoundary), "A failed save must be retried while visible.");
Check(!tracker.ShouldCapture("valid-after-malformed", now, false, noBoundary), "Successfully handled toast must not be captured twice.");
tracker.CompleteSnapshot();
tracker.BeginSnapshot(now);
Check(!tracker.ShouldCapture("temporary-save-failure", now, false, noBoundary), "Successful retry must be deduplicated.");
Check(!tracker.ShouldCapture("paused", now.AddSeconds(1), true, noBoundary), "Paused notifications must not be captured.");
tracker.CompleteSnapshot();

tracker.BeginSnapshot(now.AddSeconds(2));
Check(!tracker.ShouldCapture("paused", now.AddSeconds(1), false, now.AddSeconds(2)), "Paused content must not replay after resume.");
Check(!tracker.ShouldCapture("delayed-pause-callback", now.AddSeconds(1), false, now.AddSeconds(2)), "Delayed callback must respect resume boundary.");
Check(tracker.ShouldCapture("new-after-resume", now.AddSeconds(3), false, now.AddSeconds(2)), "Fresh post-resume content must be captured.");
tracker.CompleteSnapshot();

tracker.ResetBaseline();
tracker.BeginSnapshot(now.AddSeconds(4));
Check(!tracker.ShouldCapture("permission-gap", now.AddSeconds(4), false, noBoundary), "Permission recovery must establish a fresh baseline.");
tracker.CompleteSnapshot();
tracker.BeginSnapshot(now.AddSeconds(5));
Check(!tracker.ShouldCapture("unreadable-during-permission-baseline", now.AddSeconds(3), false, noBoundary), "Unreadable permission-gap content must not replay after parsing recovers.");
Check(tracker.ShouldCapture("fresh-after-permission", now.AddSeconds(5), false, noBoundary), "New notifications after permission recovery must be captured.");
tracker.CompleteSnapshot();
Console.WriteLine("PASS: desktop baseline, deduplication, failed-save retry, per-item failure isolation, pause/resume boundary, permission recovery.");
using (var store = new Win2Mobile.Core.BridgeStore(":memory:", "Queue verification"))
{
    for (int i = 0; i < 1100; i++)
        store.Append(new("ui-queue-" + i, "QueueTest", "QueueTest", "Title", "Body", now));
    var items = DesktopQueueReader.ReadRecent(store.GetEvents, store.HighWatermark);
    Check(items.Count == 1000 && items[0].Sequence == 101 && items[^1].Sequence == 1100, "Desktop must read the latest 1000 events through legal 200-item pages.");
    Check(items.Select(x => x.EventId).Distinct().Count() == items.Count, "Desktop paging must not duplicate events.");
    Console.WriteLine("PASS: desktop queue reader loads 1000 recent events through real storage page limits, in order without duplicates.");
}

Exception? detailFailure = null;
var detailThread = new Thread(() =>
{
    try
    {
        foreach (bool expire in new[] { true, false })
        {
            var clock = new DetailClock();
            using var store = new BridgeStore(":memory:", "Detail PC", true, clock);
            var sensitive = store.Append(new("sensitive", "app", "App", "Sensitive title", "PRIVATE_DETAIL_MARKER", clock.GetUtcNow()))!;
            var items = new ObservableCollection<BridgeEvent> { sensitive };
            var list = new ListBox { ItemsSource = items };
            var details = new TextBox(); var copy = new Button { IsEnabled = false };
            list.SelectionChanged += (_, _) => DesktopNotificationDetails.Update(list.SelectedItem as BridgeEvent, details, copy);
            list.SelectedItem = sensitive;
            Check(details.Text.Contains("PRIVATE_DETAIL_MARKER") && copy.IsEnabled, "Selected message must be readable and copyable.");
            if (expire) { clock.Advance(TimeSpan.FromHours(25)); store.PruneQueue(); }
            else for (int i = 0; i < 1000; i++) store.Append(new("evict-" + i, "app", "App", "Title", "Body", clock.GetUtcNow()));
            foreach (var item in items.Where(x => x.Sequence <= store.QueueFloor).ToArray()) items.Remove(item);
            Check(list.SelectedItem is null && !details.Text.Contains("PRIVATE_DETAIL_MARKER") && !copy.IsEnabled && !details.CanUndo,
                "Expired/evicted selection must clear body, disable copying and discard undo content.");
        }
    }
    catch (Exception error) { detailFailure = error; }
});
detailThread.SetApartmentState(ApartmentState.STA); detailThread.Start(); detailThread.Join();
if (detailFailure is not null) throw detailFailure;
Console.WriteLine("PASS: real WPF selection removal clears details, copy and undo state for expiry and capacity eviction.");

sealed class DetailClock : TimeProvider
{
    private DateTimeOffset now = DateTimeOffset.UtcNow;
    public override DateTimeOffset GetUtcNow() => now;
    public void Advance(TimeSpan duration) => now += duration;
}
