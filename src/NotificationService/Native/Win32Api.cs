using System.Runtime.InteropServices;

namespace NotificationService.Native;

/// <summary>
/// P/Invoke declarations for Windows API functions used by the notification capture service.
/// </summary>
public static class Win32Api
{
    // WinEvent constants
    public const uint EVENT_OBJECT_SHOW = 0x8002;
    public const uint EVENT_OBJECT_CREATE = 0x8000;
    public const uint WINEVENT_OUTOFCONTEXT = 0x0000;
    public const uint WINEVENT_SKIPOWNPROCESS = 0x0002;
    public const uint OBJID_WINDOW = 0x00000000;

    /// <summary>
    /// Delegate for WinEvent hook callback.
    /// </summary>
    public delegate void WinEventDelegate(
        IntPtr hWinEventHook,
        uint eventType,
        IntPtr hwnd,
        int idObject,
        int idChild,
        uint dwEventThread,
        uint dwmsEventTime);

    /// <summary>
    /// Installs an event hook function for a range of events.
    /// </summary>
    [DllImport("user32.dll", SetLastError = true)]
    public static extern IntPtr SetWinEventHook(
        uint eventMin,
        uint eventMax,
        IntPtr hmodWinEventProc,
        WinEventDelegate lpfnWinEventProc,
        uint idProcess,
        uint idThread,
        uint dwFlags);

    /// <summary>
    /// Removes an event hook.
    /// </summary>
    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool UnhookWinEvent(IntPtr hWinEventHook);

    /// <summary>
    /// Retrieves the class name of a window.
    /// </summary>
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern int GetClassName(IntPtr hWnd, System.Text.StringBuilder lpClassName, int nMaxCount);

    /// <summary>
    /// Retrieves the identifier of the thread and process that created a window.
    /// </summary>
    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

    /// <summary>
    /// Retrieves a message from the calling thread's message queue.
    /// </summary>
    [DllImport("user32.dll")]
    public static extern int GetMessage(out MSG lpMsg, IntPtr hWnd, uint wMsgFilterMin, uint wMsgFilterMax);

    /// <summary>
    /// Translates virtual-key messages into character messages.
    /// </summary>
    [DllImport("user32.dll")]
    public static extern bool TranslateMessage(ref MSG lpMsg);

    /// <summary>
    /// Dispatches a message to a window procedure.
    /// </summary>
    [DllImport("user32.dll")]
    public static extern IntPtr DispatchMessage(ref MSG lpMsg);

    /// <summary>
    /// Posts a message to the message queue of the specified thread.
    /// </summary>
    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool PostThreadMessage(uint idThread, uint Msg, IntPtr wParam, IntPtr lParam);

    /// <summary>
    /// MSG structure for the Windows message queue.
    /// </summary>
    [StructLayout(LayoutKind.Sequential)]
    public struct MSG
    {
        public IntPtr hwnd;
        public uint message;
        public IntPtr wParam;
        public IntPtr lParam;
        public uint time;
        public int pt_x;
        public int pt_y;
    }
}
