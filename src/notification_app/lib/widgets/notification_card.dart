import 'package:flutter/material.dart';
import '../models/notification_data.dart';

/// A Material card displaying a single forwarded Windows notification.
class NotificationCard extends StatelessWidget {
  final NotificationData notification;
  final VoidCallback? onTap;

  const NotificationCard({
    super.key,
    required this.notification,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      elevation: 1,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // App name + timestamp row
              Row(
                children: [
                  _AppIcon(appName: notification.appName),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      notification.appName,
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: Colors.grey.shade700,
                      ),
                    ),
                  ),
                  Text(
                    _formatTime(notification.timestamp),
                    style: TextStyle(fontSize: 11, color: Colors.grey.shade500),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              // Title
              Text(
                notification.title,
                style: const TextStyle(
                  fontSize: 15,
                  fontWeight: FontWeight.w600,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              const SizedBox(height: 2),
              // Body
              Text(
                notification.body,
                style: TextStyle(
                  fontSize: 13,
                  color: Colors.grey.shade800,
                ),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _formatTime(DateTime time) {
    final now = DateTime.now();
    final diff = now.difference(time);

    if (diff.inSeconds < 60) return 'just now';
    if (diff.inMinutes < 60) return '${diff.inMinutes}m ago';
    if (diff.inHours < 24) return '${diff.inHours}h ago';
    return '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
  }
}

/// Simple colored circle with the first letter of the app name as an icon placeholder.
class _AppIcon extends StatelessWidget {
  final String appName;

  const _AppIcon({required this.appName});

  @override
  Widget build(BuildContext context) {
    final initial = appName.isNotEmpty ? appName[0].toUpperCase() : '?';
    final color = _getColor(appName);

    return CircleAvatar(
      radius: 14,
      backgroundColor: color,
      child: Text(
        initial,
        style: const TextStyle(
          color: Colors.white,
          fontSize: 14,
          fontWeight: FontWeight.bold,
        ),
      ),
    );
  }

  Color _getColor(String name) {
    final colors = [
      Colors.blue,
      Colors.teal,
      Colors.orange,
      Colors.purple,
      Colors.red,
      Colors.green,
      Colors.indigo,
      Colors.pink,
    ];
    return colors[name.hashCode.abs() % colors.length];
  }
}
