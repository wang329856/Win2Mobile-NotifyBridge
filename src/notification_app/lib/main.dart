import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:provider/provider.dart';
import 'providers/notification_provider.dart';
import 'services/notification_service.dart';
import 'models/notification_data.dart';
import 'screens/home_screen.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Request notification permission (Android 13+)
  if (Platform.isAndroid) {
    await FlutterLocalNotificationsPlugin()
        .resolvePlatformSpecificImplementation<
            AndroidFlutterLocalNotificationsPlugin>()
        ?.requestNotificationsPermission();
  }

  final notificationService = NotificationService();
  await notificationService.initialize();

  // Self-test via service
  await notificationService.showNotification(NotificationData(
    id: 'init', title: '服务自检', body: 'NotificationService 初始化完成',
    appName: 'SelfTest', timestamp: DateTime.now(),
  ));

  runApp(
    MultiProvider(
      providers: [
        Provider<NotificationService>.value(value: notificationService),
        ChangeNotifierProvider<NotificationProvider>(
          create: (_) => NotificationProvider(notificationService),
        ),
      ],
      child: const NotifForwardApp(),
    ),
  );
}

class NotifForwardApp extends StatelessWidget {
  const NotifForwardApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorSchemeSeed: Colors.blue, useMaterial3: true),
      home: const HomeScreen(),
    );
  }
}
