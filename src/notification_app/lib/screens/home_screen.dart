import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:provider/provider.dart';
import '../providers/notification_provider.dart';
import '../widgets/notification_card.dart';
import 'settings_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _urlController = TextEditingController(text: 'http://');
  final _topicController = TextEditingController(text: 'windows-notifications');
  String _testResult = '';

  @override
  void dispose() {
    _urlController.dispose();
    _topicController.dispose();
    super.dispose();
  }

  Future<void> _testConnection() async {
    setState(() => _testResult = 'Testing...');
    try {
      var url = _urlController.text.trim();
      if (url.endsWith('/')) url = url.substring(0, url.length - 1);
      final topic = _topicController.text.trim();
      final resp = await http.get(Uri.parse('$url/$topic/latest'))
          .timeout(const Duration(seconds: 5));
      setState(() => _testResult = 'HTTP ${resp.statusCode}: ${resp.body}');
    } catch (e) {
      setState(() => _testResult = 'ERROR: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Consumer<NotificationProvider>(
      builder: (context, provider, _) {
        return Scaffold(
          appBar: AppBar(
            title: const Text('NotifForward'),
            actions: [
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: Icon(
                  provider.status == 'connected' ? Icons.circle : Icons.circle_outlined,
                  size: 12,
                  color: provider.status == 'connected' ? Colors.green : Colors.grey,
                ),
              ),
              if (provider.status == 'connected')
                IconButton(
                  icon: const Icon(Icons.link_off),
                  onPressed: () => provider.unsubscribe(),
                ),
            ],
          ),
          body: provider.status == 'connected'
              ? _buildNotificationList(provider)
              : _buildConnectPanel(provider),
        );
      },
    );
  }

  Widget _buildConnectPanel(NotificationProvider provider) {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Center(
        child: SingleChildScrollView(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.notifications_active, size: 48, color: Colors.blue),
              const SizedBox(height: 12),
              const Text('Connect to ntfy server', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
              const SizedBox(height: 20),
              TextField(
                controller: _urlController,
                decoration: const InputDecoration(
                  labelText: 'Server URL',
                  hintText: 'http://192.168.1.5:8080',
                  border: OutlineInputBorder(),
                ),
                keyboardType: TextInputType.url,
              ),
              const SizedBox(height: 10),
              TextField(
                controller: _topicController,
                decoration: const InputDecoration(
                  labelText: 'Topic',
                  hintText: 'windows-notifications',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 12),
              // Test connection button
              OutlinedButton.icon(
                onPressed: _testConnection,
                icon: const Icon(Icons.wifi_find),
                label: const Text('Test Connection'),
              ),
              if (_testResult.isNotEmpty) ...[
                const SizedBox(height: 8),
                Container(
                  padding: const EdgeInsets.all(8),
                  color: Colors.grey.shade100,
                  child: Text(_testResult, style: const TextStyle(fontSize: 11, fontFamily: 'monospace')),
                ),
              ],
              const SizedBox(height: 10),
              SizedBox(
                width: double.infinity,
                child: ElevatedButton.icon(
                  onPressed: provider.status == 'connecting'
                      ? null
                      : () => provider.subscribe(
                            _urlController.text.trim(),
                            _topicController.text.trim(),
                          ),
                  icon: const Icon(Icons.play_arrow),
                  label: const Text('Connect'),
                  style: ElevatedButton.styleFrom(padding: const EdgeInsets.symmetric(vertical: 14)),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildNotificationList(NotificationProvider provider) {
    return Column(
      children: [
        // Poll status indicator
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(8),
          color: Colors.blue.shade50,
          child: Text(
            'Poll #${provider.pollCount}: ${provider.pollLast ?? "starting..."}',
            style: const TextStyle(fontSize: 11, fontFamily: 'monospace'),
          ),
        ),
        Expanded(
          child: provider.notifications.isEmpty
              ? const Center(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(Icons.notifications_none, size: 64, color: Colors.grey),
                      SizedBox(height: 16),
                      Text('Waiting...', style: TextStyle(color: Colors.grey)),
                    ],
                  ),
                )
              : ListView.builder(
                  itemCount: provider.notifications.length,
                  itemBuilder: (context, index) {
                    return NotificationCard(
                      notification: provider.notifications[index],
                    );
                  },
                ),
        ),
      ],
    );
  }
}
