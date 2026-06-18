import 'dart:async';
import 'dart:convert';
import 'package:http/http.dart' as http;
import '../models/notification_data.dart';

class NtfyService {
  final String baseUrl;
  final String topic;
  final void Function(NotificationData) onMessage;
  final void Function(int count, String? last) onPoll;

  bool _running = false;
  Timer? _timer;
  int _pollCount = 0;
  String? _lastId;

  NtfyService({
    required this.baseUrl,
    required this.topic,
    required this.onMessage,
    required this.onPoll,
  });

  bool get isRunning => _running;

  String get _url {
    var u = baseUrl.endsWith('/') ? baseUrl.substring(0, baseUrl.length - 1) : baseUrl;
    return '$u/$topic/latest';
  }

  void start() {
    _running = true;
    _doPoll();
  }

  void _doPoll() {
    if (!_running) return;

    _pollCount++;
    onPoll(_pollCount, null);

    http.get(Uri.parse(_url)).timeout(const Duration(seconds: 5)).then((resp) {
      if (!_running) return;
      onPoll(_pollCount, 'HTTP ${resp.statusCode} len=${resp.body.length}');

      if (resp.statusCode == 200 && resp.body.isNotEmpty && resp.body != '{}') {
        try {
          final decoded = jsonDecode(resp.body);
          if (decoded is Map<String, dynamic>) {
            final id = decoded['id']?.toString();
            if (id != null && id != _lastId) {
              _lastId = id;
              onMessage(NotificationData.fromNtfy(decoded));
              onPoll(_pollCount, 'GOT: ${decoded['title']}');
            } else {
              onPoll(_pollCount, 'SKIP: same id=$id');
            }
          }
        } catch (e) {
          onPoll(_pollCount, 'Parse error: $e');
        }
      }
    }).catchError((e) {
      if (_running) onPoll(_pollCount, 'ERR: $e');
    }).whenComplete(() {
      if (_running) _timer = Timer(const Duration(seconds: 2), _doPoll);
    });
  }

  void stop() {
    _running = false;
    _timer?.cancel();
  }
}
