import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/notification_data.dart';
import '../services/notification_service.dart';
import '../services/ntfy_service.dart';

/// Manages ntfy subscription and notification history.
class NotificationProvider extends ChangeNotifier {
  final NotificationService _notificationService;

  final List<NotificationData> _notifications = [];
  NtfyService? _ntfyService;
  String _status = 'disconnected';
  String? _error;
  int _pollCount = 0;
  String? _pollLast;

  int get pollCount => _pollCount;
  String? get pollLast => _pollLast;

  static const _maxNotifications = 100;
  static const _urlKey = 'ntfy_base_url';
  static const _topicKey = 'ntfy_topic';

  NotificationProvider(this._notificationService) {
    _loadConfig();
  }

  List<NotificationData> get notifications => List.unmodifiable(_notifications);
  String get status => _status;
  String? get error => _error;
  NotificationService get notificationService => _notificationService;

  /// Subscribe to ntfy server and start receiving notifications.
  Future<bool> subscribe(String baseUrl, String topic) async {
    _error = null;
    _status = 'connecting';
    notifyListeners();

    _ntfyService?.stop();

    _ntfyService = NtfyService(
      baseUrl: baseUrl,
      topic: topic,
      onMessage: (data) {
        addNotification(data);
      },
      onPoll: (count, last) {
        _pollCount = count;
        _pollLast = last;
        notifyListeners();
      },
    );

    try {
      await _saveConfig(baseUrl, topic);
      _status = 'connected';
      notifyListeners();
      _ntfyService!.start();
      return true;
    } catch (e) {
      _status = 'disconnected';
      _error = e.toString();
      notifyListeners();
      return false;
    }
  }

  /// Disconnect from ntfy.
  void unsubscribe() {
    _ntfyService?.stop();
    _ntfyService = null;
    _status = 'disconnected';
    notifyListeners();
  }

  Future<void> addNotification(NotificationData data) async {
    _notifications.insert(0, data);
    while (_notifications.length > _maxNotifications) {
      _notifications.removeLast();
    }
    notifyListeners();
    try {
      await _notificationService.showNotification(data);
    } catch (e) {
      debugPrint('showNotification error: $e');
    }
  }
  static int _notifIdCounter = 100;

  void clearAll() {
    _notifications.clear();
    notifyListeners();
  }

  Future<void> _loadConfig() async {
    final prefs = await SharedPreferences.getInstance();
    final url = prefs.getString(_urlKey);
    final topic = prefs.getString(_topicKey);
    if (url != null && topic != null && url.isNotEmpty && topic.isNotEmpty) {
      await subscribe(url, topic);
    }
  }

  Future<void> _saveConfig(String url, String topic) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_urlKey, url);
    await prefs.setString(_topicKey, topic);
  }
}
