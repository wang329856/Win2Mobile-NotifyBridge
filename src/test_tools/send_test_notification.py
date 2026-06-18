"""
Windows Notification Forwarder - Test Script
Sends a Windows toast AND forwards to ntfy server for phone delivery.

Usage:
    python send_test_notification.py --title "Hello" --body "World"
    python send_test_notification.py --server http://localhost:8080 --topic windows-notifications
"""
import argparse
import json
import time
import urllib.request

DEFAULT_SERVER = "http://localhost:8080"
DEFAULT_TOPIC = "windows-notifications"


def send_notification(title, body, app_name, server, topic):
    """Show Windows toast and POST to ntfy server."""
    from winotify import Notification

    toast = Notification(app_id=app_name, title=title, msg=body, duration="short")
    toast.show()
    print(f"[Toast] [{app_name}] {title}")

    try:
        data = json.dumps({
            "title": f"[{app_name}] {title}",
            "message": body,
        }, ensure_ascii=False).encode("utf-8")

        url = f"{server.rstrip('/')}/{topic}"
        req = urllib.request.Request(url, data=data, method="POST")
        req.add_header("Content-Type", "application/json; charset=utf-8")
        with urllib.request.urlopen(req, timeout=5) as resp:
            result = json.loads(resp.read())
            print(f"  -> Phone: {result.get('status', 'sent')} (id={result.get('id')})")
    except Exception as e:
        print(f"  -> Server error: {e}")


def main():
    parser = argparse.ArgumentParser(description="Send test Windows notifications")
    parser.add_argument("--title", default="Test", help="Notification title")
    parser.add_argument("--body", default="Test body", help="Notification body")
    parser.add_argument("--app", default="PythonTest", help="App name")
    parser.add_argument("--server", default=DEFAULT_SERVER, help="ntfy server URL")
    parser.add_argument("--topic", default=DEFAULT_TOPIC, help="ntfy topic")
    parser.add_argument("--count", type=int, default=1, help="Number to send")
    parser.add_argument("--interval", type=float, default=2.0, help="Seconds between")
    args = parser.parse_args()

    for i in range(args.count):
        title = f"{args.title} ({i+1}/{args.count})" if args.count > 1 else args.title
        send_notification(args.title, args.body, args.app, args.server, args.topic)
        if i < args.count - 1:
            time.sleep(args.interval)

    print(f"\nDone. Sent {args.count} notification(s).")


if __name__ == "__main__":
    main()
