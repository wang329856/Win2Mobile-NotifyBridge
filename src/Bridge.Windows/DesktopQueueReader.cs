using System;
using System.Collections.Generic;
using Win2Mobile.Core;

namespace Win2Mobile.Windows;

internal static class DesktopQueueReader
{
    // Storage/protocol pages have a hard 200-item limit; the UI may retain the entire 1000-item queue.
    public static IReadOnlyList<BridgeEvent> ReadRecent(Func<long, int, IReadOnlyList<BridgeEvent>> read, long highWatermark)
    {
        var items = new List<BridgeEvent>();
        long cursor = Math.Max(0, highWatermark - 1000);
        while (cursor < highWatermark && items.Count < 1000)
        {
            var page = read(cursor, Math.Min(200, 1000 - items.Count));
            if (page.Count == 0) break;
            items.AddRange(page);
            long next = page[^1].Sequence;
            if (next <= cursor) throw new InvalidOperationException("通知队列读取进度没有推进。");
            cursor = next;
        }
        return items;
    }
}
