import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/notification_provider.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: Consumer<NotificationProvider>(
        builder: (context, provider, _) => ListView(
          children: [
            const _Section('Connection'),
            ListTile(
              leading: const Icon(Icons.link),
              title: const Text('Status'),
              subtitle: Text(provider.status.toUpperCase()),
              trailing: _statusChip(provider.status),
            ),
            if (provider.error != null)
              ListTile(
                leading: const Icon(Icons.error_outline, color: Colors.red),
                title: const Text('Error'),
                subtitle: Text(provider.error!, style: const TextStyle(fontSize: 12)),
              ),

            const Divider(),
            const _Section('Actions'),
            ListTile(
              leading: const Icon(Icons.delete_sweep, color: Colors.red),
              title: Text('Clear History (${provider.notifications.length})'),
              onTap: () {
                showDialog(
                  context: context,
                  builder: (_) => AlertDialog(
                    title: const Text('Clear History'),
                    content: const Text('Remove all received notifications?'),
                    actions: [
                      TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
                      TextButton(
                        onPressed: () {
                          provider.clearAll();
                          Navigator.pop(context);
                        },
                        child: const Text('Clear', style: TextStyle(color: Colors.red)),
                      ),
                    ],
                  ),
                );
              },
            ),
            if (provider.status == 'connected')
              ListTile(
                leading: const Icon(Icons.link_off, color: Colors.orange),
                title: const Text('Disconnect'),
                onTap: () => provider.unsubscribe(),
              ),

            const Divider(),
            const _Section('About'),
            const ListTile(
              leading: Icon(Icons.info_outline),
              title: Text('NotifForward v2'),
              subtitle: Text('ntfy-based notification forwarder'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _statusChip(String status) {
    final (c, label) = switch (status) {
      'connected' => (Colors.green, 'Connected'),
      'connecting' => (Colors.orange, 'Connecting'),
      'reconnecting' => (Colors.orange, 'Reconnecting'),
      _ => (Colors.grey, 'Disconnected'),
    };
    return Chip(
      label: Text(label, style: const TextStyle(fontSize: 12, color: Colors.white)),
      backgroundColor: c,
      padding: EdgeInsets.zero,
      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
    );
  }
}

class _Section extends StatelessWidget {
  final String title;
  const _Section(this.title);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Text(title, style: TextStyle(
        fontSize: 13, fontWeight: FontWeight.w600,
        color: Theme.of(context).colorScheme.primary,
      )),
    );
  }
}
