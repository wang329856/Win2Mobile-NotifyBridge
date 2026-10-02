using System.Windows.Controls;
using Win2Mobile.Core;

namespace Win2Mobile.Windows;

internal static class DesktopNotificationDetails
{
    public static void Update(BridgeEvent? item, TextBox details, Button copy)
    {
        // Clear TextBox's undo history too: replacing Text alone can retain old content.
        details.IsUndoEnabled = false;
        details.Text = item is null ? "选择一条通知，查看完整内容。" :
            $"{item.AppName}\n应用 ID：{item.AppId}\n本地时间：{item.OccurredAt.ToLocalTime():yyyy-MM-dd HH:mm:ss}\n{item.Title}\n\n{item.Body}";
        copy.IsEnabled = item is not null;
    }
}
