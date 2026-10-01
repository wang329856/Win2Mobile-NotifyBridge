package com.notifforward.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.*
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import java.util.concurrent.atomic.AtomicLong

class BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app get() = application as BridgeApplication
    private val dao get() = app.db.dao()
    private val restarts = Channel<Unit>(Channel.CONFLATED)
    private var coordinator: Job? = null
    private val network get() = getSystemService(ConnectivityManager::class.java)
    private val notices get() = getSystemService(NotificationManager::class.java)
    private var registered = false
    private var ready = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { restart() }
        override fun onLost(network: Network) { restart() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { restart() }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); createChannels()
        coordinator = scope.launch {
            app.initialized.await()
            var receivers: Job? = null
            try {
                for (signal in restarts) {
                    // One owner performs cancel AND join before creating any replacement connections.
                    receivers?.cancelAndJoin()
                    receivers = null
                    if (!app.preferences.getBoolean("running", false)) {
                        dao.computers().forEach { dao.state(it.serverId, "已停止") }
                        stopSelf()
                        break
                    }
                    val computers = dao.computers().filter { it.enabled }
                    receivers = launch {
                        supervisorScope { computers.forEach { computer -> launch { receive(computer.serverId) } } }
                    }
                }
            } finally { withContext(NonCancellable) { receivers?.cancelAndJoin() } }
        }
        try {
            val note = statusNotification("后台接收已开启 · 具体连接见电脑页")
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(1, note)
            val request = NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            network.registerNetworkCallback(request, callback); registered = true; ready = true
        } catch (e: SecurityException) {
            coordinator?.cancel()
            scope.launch { withContext(NonCancellable) { app.initialized.await(); dao.computers().forEach { dao.state(it.serverId, "后台权限受限", "请打开应用并检查系统权限") }; stopSelf() } }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            app.preferences.edit().putBoolean("running", false).apply()
            restart()
            return START_NOT_STICKY
        }
        if (!ready) return START_NOT_STICKY
        if (!app.preferences.getBoolean("running", false)) { stopSelf(); return START_NOT_STICKY }
        restart()
        return START_STICKY
    }
    private fun restart() { restarts.trySend(Unit) }
    private suspend fun receive(id: String) {
        var retries = 0
        try {
            while (currentCoroutineContext().isActive) {
                val computer = dao.computer(id) ?: return
                if (!computer.enabled) return
                val route = LanNetwork.select(this)
                if (route == null) {
                    dao.state(id, "等待网络", "请连接电脑所在的局域网")
                    delay(3000)
                    continue
                }
                val client = app.transports.create(computer.baseUrl, computer.certificateSha256)
                var socket: WebSocket? = null
                var retry = false
                try {
                    val token = runCatching { app.tokens.get(id) }.getOrNull() ?: throw AuthorizationRequired()
                    dao.state(id, "正在连接")
                    coroutineScope {
                        val frames = Channel<String>(256)
                        val lastFrame = AtomicLong(SystemClock.elapsedRealtime())
                        socket = client.stream(computer.cursor, token, object : WebSocketListener() {
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                lastFrame.set(SystemClock.elapsedRealtime())
                                if (frames.trySend(text).isFailure) {
                                    frames.close(IllegalStateException("消息过多，正在重新同步")); webSocket.cancel()
                                }
                            }
                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                frames.close(StreamFailures.handshake(response?.code, t))
                            }
                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                // Reply to the closing handshake immediately; do not wait for the watchdog.
                                webSocket.close(code, reason)
                                frames.close(StreamFailures.closed(code))
                            }
                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                                frames.close(StreamFailures.closed(code))
                            }
                        })
                        val watchdog = launch {
                            while (isActive) {
                                delay(10000)
                                if (SystemClock.elapsedRealtime() - lastFrame.get() > 60000) {
                                    frames.close(IllegalStateException("电脑心跳超时")); socket?.cancel(); break
                                }
                            }
                        }
                        try {
                            val state = StreamState(id, computer.cursor)
                            for (text in frames) {
                                val frame = wireJson.decodeFromString<StreamFrame>(text)
                                state.accept(frame)
                                when (frame.kind) {
                                    "hello" -> {
                                        retries = 0
                                        // Repair an earlier failed ACK even if no new events arrive.
                                        client.ack(dao.computer(id)?.cursor ?: error("电脑已移除"), token)
                                        dao.state(id, "已连接")
                                        notices.notify(1, statusNotification("后台接收已开启 · 具体连接见电脑页"))
                                    }
                                    "event" -> {
                                        val event = frame.event!!
                                        EventDelivery.deliver(state.isReplay(event.sequence),
                                            save = { dao.accept(id, event) },
                                            display = { showEvent(id, event) },
                                            acknowledge = { client.ack(dao.computer(id)?.cursor ?: error("电脑已移除"), token) })
                                    }
                                }
                            }
                            error("连接结束")
                        } finally { watchdog.cancelAndJoin(); frames.cancel() }
                    }
                } catch (e: CancellationException) { throw e
                } catch (e: AuthorizationRequired) {
                    dao.enabled(id, false, "需要重新授权")
                    dao.state(id, "需要重新授权", "电脑已撤销授权或手机凭据不可用，请重新扫码")
                    return
                } catch (e: Exception) {
                    // Never include server response bodies, pairing secrets, tokens or QR data in diagnostics.
                    dao.state(id, "等待重连", when (e) {
                        is javax.net.ssl.SSLException -> "证书校验失败，请检查电脑时间或重新扫码"
                        is java.net.ConnectException -> "无法连接电脑，请检查电脑服务与局域网"
                        is java.net.UnknownHostException -> "无法解析电脑地址"
                        else -> "同步中断，正在重试；可重新扫码更新电脑地址"
                    })
                    notices.notify(1, statusNotification("后台接收已开启 · 具体连接见电脑页"))
                    retry = true
                } finally { socket?.cancel(); client.close() }
                // Release the old socket before backoff, including ACK failures.
                if (retry) delay((1000L shl minOf(retries++, 6)) + kotlin.random.Random.nextLong(500))
            }
        } finally {
            withContext(NonCancellable) {
                dao.computer(id)?.let { computer ->
                    if (computer.state == "已连接" || computer.state == "正在连接") dao.state(id, "等待连接")
                }
            }
        }
    }
    private fun createChannels() {
        notices.createNotificationChannel(NotificationChannel("bridge_status", "后台连接状态", NotificationManager.IMPORTANCE_LOW))
        notices.createNotificationChannel(NotificationChannel("bridge_messages", "电脑通知（有声）", NotificationManager.IMPORTANCE_DEFAULT))
        notices.createNotificationChannel(NotificationChannel("bridge_silent", "电脑通知（静音）", NotificationManager.IMPORTANCE_LOW))
    }
    private fun contentIntent(id: String? = null, eventId: String? = null): PendingIntent = PendingIntent.getActivity(this,
        (id.orEmpty() + eventId.orEmpty()).hashCode(), Intent(this, MainActivity::class.java).putExtra("serverId", id).putExtra("eventId", eventId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun statusNotification(text: String): Notification = NotificationCompat.Builder(this, "bridge_status")
        .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Win2Mobile · 后台同步").setContentText(text)
        .setContentIntent(contentIntent()).setOngoing(true).setOnlyAlertOnce(true)
        .addAction(0, "停止接收", PendingIntent.getService(this, 0, Intent(this, BridgeService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)).build()
    private fun showEvent(id: String, event: BridgeEvent) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val channel = if (app.preferences.getBoolean("sound", true)) "bridge_messages" else "bridge_silent"
        notices.notify("$id:${event.eventId}", 2, NotificationCompat.Builder(this, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(event.title.ifBlank { event.appName }).setContentText(event.body).setSubText(event.appName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.body)).setAutoCancel(true)
            .setContentIntent(contentIntent(id, event.eventId)).build())
    }
    override fun onDestroy() {
        if (registered) network.unregisterNetworkCallback(callback)
        restarts.close(); scope.cancel(); super.onDestroy()
    }
    companion object {
        fun start(context: Context) {
            val app = context.applicationContext as BridgeApplication
            app.preferences.edit().putBoolean("running", true).apply()
            try { context.startForegroundService(Intent(context, BridgeService::class.java)) }
            catch (e: RuntimeException) {
                CoroutineScope(Dispatchers.IO).launch { app.initialized.await(); app.db.dao().computers().forEach { app.db.dao().state(it.serverId, "后台启动受限", "请打开应用后点击开始接收") } }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && (context.applicationContext as BridgeApplication).preferences.getBoolean("running", false)) BridgeService.start(context)
    }
}
