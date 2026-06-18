/// Represents a notification received from the ntfy server.
class NotificationData {
  final String id;
  final String title;
  final String body;
  final String appName;
  final DateTime timestamp;

  NotificationData({
    required this.id,
    required this.title,
    required this.body,
    required this.appName,
    required this.timestamp,
  });

  /// Parse from ntfy JSON message format.
  /// ntfy sends: {"id":"...","time":1234567890,"title":"...","message":"..."}
  factory NotificationData.fromNtfy(Map<String, dynamic> json) {
    final time = json['time'] ?? json['timestamp'];
    final ts = time is int
        ? DateTime.fromMillisecondsSinceEpoch(time * 1000)
        : DateTime.tryParse(time?.toString() ?? '') ?? DateTime.now();

    // ntfy "title" contains "[AppName] Title" from our server
    var title = (json['title'] as String?) ?? '';
    var appName = 'Windows';
    final match = RegExp(r'^[\[【](.+?)[\]】]\s*(.+)$').firstMatch(title);
    if (match != null) {
      appName = match.group(1)!;
      title = match.group(2)!;
    }

    return NotificationData(
      id: (json['id'] as String?) ?? DateTime.now().millisecondsSinceEpoch.toString(),
      title: title,
      body: (json['message'] as String?) ?? '',
      appName: appName,
      timestamp: ts,
    );
  }
}
