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
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import javax.net.ssl.SSLException

class BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app get() = application as BridgeApplication
    private val dao get() = app.db.dao()
    private val restarts = Channel<Unit>(Channel.CONFLATED)
    private val newSessionRequested = AtomicBoolean(false)
    private val owners = mutableMapOf<String, Owner>()
    private var coordinator: Job? = null
    private var networkDebounce: Job? = null
    private var networkKey = ""
    private val network get() = getSystemService(ConnectivityManager::class.java)
    private val notices get() = getSystemService(NotificationManager::class.java)
    private var registered = false
    private var ready = false
    @Volatile private var lastStartId = 0
    private data class Owner(val key: List<String>, val changes: Channel<Unit>, val job: Job)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged()
        override fun onLost(network: Network) = networkChanged()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = networkChanged()
    }
    private fun networkChanged() {
        synchronized(this) {
            networkDebounce?.cancel()
            networkDebounce = scope.launch {
                delay(500)
                val key = LanNetwork.local(this@BridgeService).toString() + ":" + network.activeNetwork.toString()
                if (key != networkKey) {
                    networkKey = key
                    // The coordinator owns the map. A network signal wakes its probes without replacing receivers.
                    restarts.trySend(Unit)
                }
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); createChannels()
        coordinator = scope.launch {
            app.initialized.await()
            try {
                for (signal in restarts) {
                    if (newSessionRequested.getAndSet(false)) {
                        owners.values.forEach { it.job.cancel() }
                        owners.values.forEach { it.job.join(); it.changes.close() }; owners.clear()
                        app.notificationRetention.clear { dao.beginSession() }
                    }
                    if (!app.preferences.getBoolean("running", false)) {
                        val stoppingId = lastStartId
                        owners.values.forEach { it.job.cancel() }
                        owners.values.forEach { it.job.join(); it.changes.close() }; owners.clear()
                        dao.computers().forEach { dao.state(it.serverId, "已暂停") }
                        if (app.preferences.getBoolean("running", false)) continue
                        if (stopSelfResult(stoppingId)) { app.serviceActive.value = false; break }
                        continue
                    }
                    val computers = dao.computers().filter { it.enabled }
                    val ids = computers.map { it.serverId }.toSet()
                    owners.keys.filter { it !in ids }.toList().forEach { id -> owners.remove(id)?.let { it.job.cancelAndJoin(); it.changes.close() } }
                    computers.forEach { pc ->
                        val key = listOf(pc.connectionMode, pc.baseUrl, pc.certificateSha256, pc.deviceId)
                        val old = owners[pc.serverId]
                        if (old == null || old.key != key || old.job.isCompleted) {
                            old?.let { it.job.cancelAndJoin(); it.changes.close() }
                            val changes = Channel<Unit>(Channel.CONFLATED)
                            owners[pc.serverId] = Owner(key, changes, scope.launch { receive(pc.serverId, changes) })
                        } else old.changes.trySend(Unit)
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    owners.values.forEach { it.job.cancel() }
                    owners.values.forEach { it.job.join(); it.changes.close() }; owners.clear()
                }
            }
        }
        try {
            val note = statusNotification("接收已开启 · 正在选择连接方式")
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(1, note)
            val request = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            network.registerNetworkCallback(request, callback); registered = true; ready = true
            app.serviceActive.value = true
        } catch (_: SecurityException) {
            coordinator?.cancel(); app.serviceActive.value = false
            scope.launch { withContext(NonCancellable) { app.initialized.await(); dao.computers().forEach { dao.state(it.serverId, "后台权限受限", "请打开应用并检查系统权限") }; stopSelf() } }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ReceiveCommands.PAUSE) {
            app.preferences.edit().putBoolean("running", false).apply(); restarts.trySend(Unit); return START_NOT_STICKY
        }
        if (intent?.action == ReceiveCommands.NEW_SESSION) newSessionRequested.set(true)
        if (!ready) return START_NOT_STICKY
        if (!app.preferences.getBoolean("running", false)) { stopSelf(); return START_NOT_STICKY }
        restarts.trySend(Unit)
        return START_STICKY
    }
    private suspend fun relayCredentials(id: String): NtfyCredentials? = runCatching {
        app.tokens.get(id + ":ntfy")?.let { wireJson.decodeFromString<NtfyCredentials>(it).validate() }
    }.getOrNull()
    private suspend fun receive(id: String, changes: Channel<Unit>) {
        try {
            val pc = dao.computer(id) ?: return
            ConnectionCoordinator().run(ConnectionMode.parse(pc.connectionMode), object : ConnectionSession {
                override fun hasLan() = LanNetwork.local(this@BridgeService) != null
                override fun lanRouteKey() = LanNetwork.local(this@BridgeService).toString()
                override suspend fun hasRelay() = relayCredentials(id) != null
                override suspend fun probe() {
                    val current = dao.computer(id) ?: throw CancellationException("电脑已移除")
                    val token = app.tokens.get(id) ?: throw AuthorizationRequired()
                    val client = app.transports.create(current.baseUrl, current.certificateSha256)
                    try { client.probe(id, token) } finally { client.close() }
                }
                override suspend fun receive(transport: ActiveTransport, connected: () -> Unit) {
                    if (transport == ActiveTransport.LAN) receiveLan(id, connected) else receiveRelay(id, connected)
                }
                override suspend fun waiting(detail: String) { dao.state(id, "等待重连", detail) }
                override suspend fun waitForNetworkChange(timeoutMs: Long) { withTimeoutOrNull(timeoutMs) { changes.receive() } }
            })
        } catch (e: CancellationException) { throw e }
        catch (_: AuthorizationRequired) { dao.enabled(id, false, "需要重新授权"); dao.state(id, "需要重新授权", "电脑授权已撤销或凭据不可用，请重新配对") }
        catch (_: SSLException) { dao.enabled(id, false, "需要核验"); dao.state(id, "需要核验", "电脑证书校验失败，请检查时间、更新地址或重新配对") }
        catch (_: IdentityMismatch) { dao.enabled(id, false, "需要核验"); dao.state(id, "需要核验", "电脑身份与原授权不同，请核验地址并重新配对") }
        catch (_: Exception) { dao.state(id, "连接异常", "请检查电脑与网络后重试") }
        finally {
            withContext(NonCancellable) {
                dao.computer(id)?.let { if (it.state in listOf("已连接", "中转已连接", "正在连接", "正在连接中转")) dao.state(id, "等待连接") }
            }
        }
    }
    private suspend fun receiveLan(id: String, connected: () -> Unit) {
        val pc = dao.computer(id) ?: return
        val token = app.tokens.get(id) ?: throw AuthorizationRequired()
        val client = app.transports.create(pc.baseUrl, pc.certificateSha256)
        var socket: WebSocket? = null
        try {
            dao.state(id, "正在连接")
            coroutineScope {
                val frames = Channel<String>(256)
                val lastFrame = AtomicLong(SystemClock.elapsedRealtime())
                socket = client.stream(pc.cursor, token, object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        lastFrame.set(SystemClock.elapsedRealtime())
                        if (frames.trySend(text).isFailure) { frames.close(java.io.IOException("消息过多，正在重新同步")); webSocket.cancel() }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { frames.close(StreamFailures.handshake(response?.code, t)) }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason); frames.close(StreamFailures.closed(code)) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { frames.close(StreamFailures.closed(code)) }
                }, liveOnly = pc.sessionPending)
                val watchdog = launch {
                    while (isActive) {
                        delay(10_000)
                        if (SystemClock.elapsedRealtime() - lastFrame.get() > 60_000) { frames.close(java.io.IOException("电脑心跳超时")); socket?.cancel(); break }
                    }
                }
                try {
                    val state = StreamState(id, pc.cursor)
                    for (text in frames) {
                        val frame = wireJson.decodeFromString<StreamFrame>(text)
                        if (frame.kind == "hello" && (frame.serverId != id || frame.protocolVersion != 1)) throw IdentityMismatch()
                        state.accept(frame)
                        when (frame.kind) {
                            "hello" -> {
                                dao.sessionConnected(id, Instant.now().toString()); dao.alignCursor(id, state.resumeCursor)
                                client.ack(dao.computer(id)?.cursor ?: error("电脑已移除"), token)
                                dao.state(id, "已连接"); connected()
                            }
                            "checkpoint" -> { dao.alignCursor(id, state.resumeCursor); client.ack(dao.computer(id)?.cursor ?: error("电脑已移除"), token) }
                            "event" -> {
                                val event = frame.event!!
                                app.notificationRetention.deliver(state.isReplay(event.sequence), save = { dao.accept(id, event) }, display = { showEvent(id, event) },
                                    acknowledge = { client.ack(dao.computer(id)?.cursor ?: error("电脑已移除"), token) })
                            }
                        }
                    }
                    throw java.io.IOException("连接结束")
                } finally { watchdog.cancelAndJoin(); frames.cancel() }
            }
        } finally { socket?.cancel(); client.close() }
    }
    private suspend fun receiveRelay(id: String, connected: () -> Unit) {
        val pc = dao.computer(id) ?: return
        val credentials = relayCredentials(id) ?: throw java.io.IOException("无中转凭据")
        val client = NtfyClient(credentials)
        val assembler = NtfyAssembler(id, pc.deviceId, credentials)
        val connectedAt = Instant.now()
        try {
            dao.state(id, "正在连接中转")
            client.receive(pc.relayMessageId,
                connected = { dao.sessionConnected(id, Instant.now().toString()); dao.state(id, "中转已连接", "中转连通不代表电脑在线"); connected() },
                accept = { message ->
                    val event = runCatching { message.message?.let { assembler.accept(it) } }.getOrNull()
                    if (event != null) app.notificationRetention.deliver(Instant.parse(event.occurredAt).isBefore(connectedAt),
                        save = { dao.acceptRelay(id, event, message.id) }, display = { showEvent(id, event) }, acknowledge = {})
                }, liveOnly = pc.sessionPending)
        } finally { client.close() }
    }
    private fun createChannels() {
        notices.createNotificationChannel(NotificationChannel("bridge_status", "后台连接状态", NotificationManager.IMPORTANCE_LOW))
        notices.createNotificationChannel(NotificationChannel("bridge_messages", "电脑通知（有声）", NotificationManager.IMPORTANCE_DEFAULT))
        notices.createNotificationChannel(NotificationChannel("bridge_silent", "电脑通知（静音）", NotificationManager.IMPORTANCE_LOW))
    }
    private fun contentIntent(id: String? = null, eventId: String? = null): PendingIntent = PendingIntent.getActivity(this,
        (id.orEmpty() + eventId.orEmpty()).hashCode(), Intent(this, MainActivity::class.java).putExtra("serverId", id).putExtra("eventId", eventId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun statusNotification(text: String): Notification = NotificationCompat.Builder(this, "bridge_status")
        .setSmallIcon(R.drawable.ic_notification).setContentTitle("Win2Mobile · 接收已开启").setContentText(text)
        .setContentIntent(contentIntent()).setOngoing(true).setOnlyAlertOnce(true)
        .addAction(0, "暂停接收", PendingIntent.getService(this, 0, Intent(this, BridgeService::class.java).setAction(ReceiveCommands.PAUSE), PendingIntent.FLAG_IMMUTABLE)).build()
    private fun showEvent(id: String, event: BridgeEvent) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val channel = if (app.preferences.getBoolean("sound", true)) "bridge_messages" else "bridge_silent"
        val source = NotificationPresentation.source(event.appName, event.appId)
        notices.notify(id + ":" + event.eventId, AndroidMessageNotifications.MESSAGE_ID, NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(NotificationPresentation.title(event.appName, event.appId, event.title))
            .setContentText(event.body).setSubText("来自电脑 · $source")
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.body)).setAutoCancel(true).setContentIntent(contentIntent(id, event.eventId)).build())
    }
    override fun onDestroy() {
        if (registered) network.unregisterNetworkCallback(callback)
        app.serviceActive.value = false; restarts.close(); scope.cancel(); super.onDestroy()
    }
    companion object {
        fun start(context: Context, newSession: Boolean = false) {
            val app = context.applicationContext as BridgeApplication
            app.preferences.edit().putBoolean("running", true).apply()
            try { context.startForegroundService(Intent(context, BridgeService::class.java).setAction(ReceiveCommands.startAction(newSession))) }
            catch (_: RuntimeException) {
                app.serviceActive.value = false
                CoroutineScope(Dispatchers.IO).launch { app.initialized.await(); app.db.dao().computers().forEach { app.db.dao().state(it.serverId, "后台启动受限", "请打开应用后继续接收") } }
            }
        }
        fun pause(context: Context) {
            val app = context.applicationContext as BridgeApplication
            app.preferences.edit().putBoolean("running", false).apply()
            if (app.serviceActive.value) context.startService(Intent(context, BridgeService::class.java).setAction(ReceiveCommands.PAUSE))
        }
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && (context.applicationContext as BridgeApplication).preferences.getBoolean("running", false)) BridgeService.start(context)
    }
}
