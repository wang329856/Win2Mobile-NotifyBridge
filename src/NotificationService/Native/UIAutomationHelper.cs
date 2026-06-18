using System.Runtime.InteropServices;

namespace NotificationService.Native;

/// <summary>
/// Helper to extract text content from notification windows using UI Automation.
/// </summary>
public static class UIAutomationHelper
{
    /// <summary>
    /// Tries to extract title and body text from a notification window using UI Automation.
    /// Returns (title, body). Either may be empty if extraction fails.
    /// </summary>
    public static (string title, string body) ExtractTextFromWindow(IntPtr hwnd)
    {
        try
        {
            // Use the IUIAutomation COM interface to get the element for this window
            var automation = GetAutomation();
            if (automation == null)
                return ("", "");

            var element = automation.ElementFromHandle(hwnd);

            // Try to get the notification title (Name property of the window)
            string title = "";
            try { title = element.CurrentName ?? ""; } catch { }

            // Walk the element tree to find text content
            var body = ExtractBodyText(element);

            Marshal.ReleaseComObject(element);
            return (title, body);
        }
        catch
        {
            return ("", "");
        }
    }

    private static string ExtractBodyText(dynamic element)
    {
        try
        {
            // Get all descendants that are text controls
            var condition = GetAutomation()?.CreateTrueCondition();
            if (condition == null) return "";

            var children = element.FindAll(Interop.UIAutomationClient.TreeScope.TreeScope_Descendants, condition);

            var texts = new List<string>();
            for (int i = 0; i < children.Length; i++)
            {
                try
                {
                    var child = children.GetElement(i);
                    var controlType = child.CurrentControlType;
                    var name = child.CurrentName;
                    var isText = child.CurrentIsTextPatternAvailable != 0;

                    // Collect names from text elements
                    if (isText && !string.IsNullOrWhiteSpace(name))
                    {
                        texts.Add(name);
                    }
                    // Also collect from Text control types
                    else if (controlType == 50020 && !string.IsNullOrWhiteSpace(name)) // Text
                    {
                        texts.Add(name);
                    }

                    Marshal.ReleaseComObject(child);
                }
                catch
                {
                    // Skip elements that cause errors
                }
            }

            Marshal.ReleaseComObject(children);
            return string.Join(" ", texts).Trim();
        }
        catch
        {
            return "";
        }
    }

    private static dynamic? _cachedAutomation;
    private static readonly object _automationLock = new();

    private static dynamic? GetAutomation()
    {
        if (_cachedAutomation != null) return _cachedAutomation;

        lock (_automationLock)
        {
            if (_cachedAutomation != null) return _cachedAutomation;

            try
            {
                var type = Type.GetTypeFromProgID("UIAutomation.CUIAutomation8")
                    ?? Type.GetTypeFromProgID("UIAutomation.CUIAutomation");
                if (type != null)
                {
                    _cachedAutomation = Activator.CreateInstance(type);
                }
            }
            catch
            {
                // UI Automation not available
            }
            return _cachedAutomation;
        }
    }

    /// <summary>
    /// Clean up the cached automation object.
    /// </summary>
    public static void Cleanup()
    {
        lock (_automationLock)
        {
            if (_cachedAutomation != null)
            {
                try { Marshal.ReleaseComObject(_cachedAutomation); } catch { }
                _cachedAutomation = null;
            }
        }
    }
}
