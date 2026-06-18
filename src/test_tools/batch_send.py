"""
Batch notification sender - sends a variety of simulated app notifications
to test the full NotificationForwarder pipeline end-to-end.

Usage:
    python batch_send.py
"""
import time
import random
from winotify import Notification

# Simulated notifications from various common apps
TEST_NOTIFICATIONS = [
    ("Microsoft Outlook", "📧 New Email", "Alice Johnson: Meeting at 3pm tomorrow — can you confirm?"),
    ("Slack", "💬 New Message", "Bob (engineering): Can you review the PR for issue #4521?"),
    ("Microsoft Teams", "📅 Meeting Reminder", "Daily standup starts in 5 minutes — don't be late!"),
    ("Windows Security", "🛡️ Security Scan", "Quick scan complete. No threats found on your device."),
    ("Discord", "💬 New Mention", "Charlie mentioned you in #project-zeus: 'Great work on the demo!'"),
    ("GitHub", "🔔 Pull Request", "dependabot wants to merge 1 commit into main: bump serilog from 2.0 to 3.0"),
    ("Spotify", "🎵 Now Playing", "Artist: Daft Punk — Song: Get Lucky"),
    ("Visual Studio Code", "📝 Extension Update", "C# extension v2.45.0 is ready to install. Restart needed."),
    ("Windows Update", "🔄 Update Available", "2026-06 Cumulative Update for Windows 11 — restart required."),
    ("Calendar", "📅 Event", "In 10 minutes: Lunch with Sarah at the usual place"),
]


def send_notification(app_name, title, body):
    """Send a single Windows toast notification."""
    toast = Notification(
        app_id=app_name,
        title=title,
        msg=body,
        duration="short",
    )
    toast.show()
    print(f"[+] [{app_name}] {title}: {body[:50]}...")


def main():
    print("=" * 60)
    print("Batch Notification Sender for NotificationForwarder Testing")
    print("=" * 60)
    print(f"Sending {len(TEST_NOTIFICATIONS)} notifications with random intervals...\n")

    for i, (app, title, body) in enumerate(TEST_NOTIFICATIONS, 1):
        send_notification(app, title, body)
        if i < len(TEST_NOTIFICATIONS):
            delay = random.uniform(1.5, 3.0)
            time.sleep(delay)

    print("\n" + "=" * 60)
    print(f"Done! Sent all {len(TEST_NOTIFICATIONS)} notifications.")
    print("Check your phone app to verify all were forwarded.")
    print("=" * 60)


if __name__ == "__main__":
    main()
