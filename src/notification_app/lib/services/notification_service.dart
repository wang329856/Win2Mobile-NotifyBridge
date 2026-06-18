import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import '../models/notification_data.dart';

class NotificationService {
  static final FlutterLocalNotificationsPlugin _plugin =
      FlutterLocalNotificationsPlugin();
  bool _initialized = false;

  bool get initialized => _initialized;

  Future<void> initialize() async {
    if (_initialized) return;

    await _plugin.initialize(
      const InitializationSettings(
        android: AndroidInitializationSettings('@mipmap/ic_launcher'),
      ),
    );

    await _plugin
        .resolvePlatformSpecificImplementation<
            AndroidFlutterLocalNotificationsPlugin>()
        ?.createNotificationChannel(const AndroidNotificationChannel(
          'windows_notifications',
          'Windows Notifications',
          importance: Importance.max,
        ));

    _initialized = true;
  }

  int _idCounter = 1000;

  Future<void> showNotification(NotificationData data) async {
    if (!_initialized) return;
    await _plugin.show(
      ++_idCounter,
      data.title.isNotEmpty ? data.title : 'Notification',
      data.body,
      const NotificationDetails(
        android: AndroidNotificationDetails(
          'windows_notifications',
          'Windows Notifications',
          importance: Importance.max,
        ),
      ),
    );
  }
}
