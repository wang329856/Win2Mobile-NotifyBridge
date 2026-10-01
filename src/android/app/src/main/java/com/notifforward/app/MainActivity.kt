package com.notifforward.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val app get() = application as BridgeApplication
    private var pairing by mutableStateOf(false)
    private var pairMessage by mutableStateOf("")
    private var pairJob: Job? = null
    private var notificationsEnabled by mutableStateOf(false)
    private var batteryUnrestricted by mutableStateOf(false)
    private var focusEvent by mutableStateOf<Pair<String, String>?>(null)
    private val scanner = registerForActivityResult(ScanContract()) { result -> result.contents?.let { pair(it) } }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scan() else { pairMessage = "未获得相机权限，可使用粘贴配对信息" }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) pairMessage = "系统通知权限未开启，消息仍会保存到通知历史，可在设置中开启"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readFocus(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { BridgeScreen() }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        notificationsEnabled = getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
        batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readFocus(intent) }
    private fun readFocus(intent: Intent) {
        val server = intent.getStringExtra("serverId"); val event = intent.getStringExtra("eventId")
        if (server != null && event != null) focusEvent = server to event
    }
    private fun scan() { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描电脑端配对二维码").setBeepEnabled(false).setOrientationLocked(false)) }
    private fun requestScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scan()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    private fun requestNotices() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun pair(text: String) {
        if (pairing) return
        val remote = runCatching {
            if (wireJson.parseToJsonElement(text).jsonObject["schema"]?.jsonPrimitive?.content == "win2mobile-ntfy-pair")
                wireJson.decodeFromString<RemotePairPayload>(text).validate() else null
        }.getOrElse { pairMessage = it.message ?: "配对信息无效"; return }
        val payload = runCatching { remote?.metadata() ?: PairPayload.parse(text) }.getOrElse { pairMessage = it.message ?: "配对信息无效"; return }
        pairing = true; pairMessage = "正在安全连接 ${payload.serverName}"
        pairJob = lifecycleScope.launch {
            val client = if (remote == null) app.transports.create(payload.baseUrl, payload.certificateSha256) else null
            val remoteClient = remote?.let { RemotePairClient(it) }
            try {
                app.initialized.await()
                val name = "${Build.MANUFACTURER} ${Build.MODEL}"
                val result = remoteClient?.pair(name) { pairMessage = it } ?: client!!.pair(payload, name) { pairMessage = it }
                val relay = result.ntfy?.validate()
                if (relay != null) app.tokens.put(payload.serverId + ":ntfy", wireJson.encodeToString(relay))
                else app.tokens.remove(payload.serverId + ":ntfy")
                app.tokens.put(payload.serverId, result.accessToken!!)
                app.db.dao().savePairing(Computer(payload.serverId, result.serverName, payload.baseUrl.trimEnd('/'), payload.certificateSha256, result.deviceId!!,
                    cursor = result.startSequence.also { require(it >= 0) }, remoteEnabled = relay != null, relaySequence = relay?.startSequence ?: 0))
                pairMessage = "配对成功，正在接收 ${result.serverName} 的新通知"
                requestNotices(); BridgeService.start(this@MainActivity)
            } catch (e: TimeoutCancellationException) { pairMessage = "远程配对超时，请重新生成二维码并核对校验码"
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                pairMessage = when (e) {
                    is javax.net.ssl.SSLException -> "证书校验失败，请核对电脑与手机时间，并重新扫描二维码"
                    is java.io.IOException -> if (remote != null) "无法完成远程配对，请检查互联网与 ntfy 服务，并重新生成二维码" else "无法连接电脑，请确认两端位于同一局域网且电脑服务已启动"
                    else -> e.message ?: "配对失败，请重新扫码"
                }
            } finally { client?.close(); remoteClient?.close(); pairing = false }
        }
    }
    private fun launchSettings(action: String, packageUri: Boolean = false) {
        runCatching { startActivity(Intent(action).apply { if (packageUri) data = Uri.parse("package:$packageName") }) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun BridgeScreen() {
        var tab by remember { mutableIntStateOf(0) }
        var query by remember { mutableStateOf("") }
        var filter by remember { mutableStateOf<String?>(null) }
        var historyLimit by remember { mutableIntStateOf(200) }
        LaunchedEffect(query, filter) { historyLimit = 200 }
        val computers by remember { app.db.dao().observeComputers() }.collectAsStateWithLifecycle(emptyList())
        val history by remember(query, filter, historyLimit) { app.db.dao().observeNotifications(query.trim(), filter, historyLimit) }.collectAsStateWithLifecycle(emptyList())
        val total by remember { app.db.dao().observeCount() }.collectAsStateWithLifecycle(0)
        val matchCount by remember(query, filter) { app.db.dao().observeMatchCount(query.trim(), filter) }.collectAsStateWithLifecycle(0)
        val apps by remember { app.db.dao().observeApps() }.collectAsStateWithLifecycle(emptyList())
        var filterMenu by remember { mutableStateOf(false) }
        var paste by remember { mutableStateOf(false) }
        var pasteText by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf<SavedNotification?>(null) }
        var deleting by remember { mutableStateOf<Computer?>(null) }
        var editingLan by remember { mutableStateOf<Computer?>(null) }
        var lanAddressText by remember { mutableStateOf("") }
        var lanAddressError by remember { mutableStateOf("") }
        var switchAfterAddress by remember { mutableStateOf(false) }
        var clear by remember { mutableStateOf(false) }
        var sound by remember { mutableStateOf(app.preferences.getBoolean("sound", true)) }
        val focused = focusEvent
        LaunchedEffect(focused) { focused?.let { key -> app.db.dao().savedEvent(key.first, key.second)?.let { selected = it; focusEvent = null; tab = 0 } } }
        Scaffold(topBar = { TopAppBar(title = { Column { Text("Win2Mobile"); Text("电脑通知 · 安全同步", style = MaterialTheme.typography.labelMedium) } }) },
            bottomBar = { NavigationBar { listOf("通知", "电脑", "设置").forEachIndexed { index, label -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(painterResource(listOf(R.drawable.nav_history, R.drawable.nav_computers, R.drawable.nav_settings)[index]), contentDescription = null) }, label = { Text(label) }) } } }
        ) { padding ->
            Column(Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize()) {
                if (pairMessage.isNotBlank()) {
                    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pairMessage, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { if (pairing) { pairJob?.cancel(); pairMessage = "配对已取消" } else pairMessage = "" }) { Text(if (pairing) "取消" else "关闭") }
                    } }
                }
                when (tab) {
                    0 -> {
                        OutlinedTextField(query, { query = it }, label = { Text("搜索标题、正文或应用") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${total} 条已保存", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelMedium)
                            Box { TextButton(onClick = { filterMenu = true }) { Text(filter?.let { id -> apps.firstOrNull { it.appId == id }?.appName } ?: "全部应用 ▾") }
                                DropdownMenu(filterMenu, { filterMenu = false }) {
                                    DropdownMenuItem(text = { Text("全部应用") }, onClick = { filter = null; filterMenu = false })
                                    apps.forEach { item -> DropdownMenuItem(text = { Text(item.appName) }, onClick = { filter = item.appId; filterMenu = false }) }
                                }
                            }
                        }
                        val matches = history
                        if (matches.isEmpty()) EmptyState(if (total == 0) "本次接收的通知会显示在这里" else "没有匹配的通知", if (computers.isEmpty()) "在电脑页扫描二维码，连接你的电脑" else "只显示本次会话的新通知，删除后不会重新出现")
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(matches, key = { it.serverId + it.eventId }) { item ->
                            Card(onClick = { selected = item }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${item.appName} · ${formatTime(item.occurredAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(item.title.ifBlank { "无标题" }, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                                    Text(item.body, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                                    Text(computers.firstOrNull { it.serverId == item.serverId }?.serverName ?: "电脑", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                            if (history.size < matchCount) item {
                                OutlinedButton(onClick = { historyLimit += 200 }, modifier = Modifier.fillMaxWidth()) { Text("加载更多（已显示 ${history.size} / $matchCount）") }
                            }
                        }
                    }
                    1 -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { requestScan() }, enabled = !pairing, modifier = Modifier.weight(1f)) { Text("扫码连接电脑") }
                            OutlinedButton(onClick = { paste = true }, enabled = !pairing) { Text("粘贴信息") }
                        }
                        Text("支持局域网与远程配对二维码；远程配对需核对两端六位校验码，再在电脑批准。", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
                        if (computers.isEmpty()) EmptyState("连接你的第一台电脑", "电脑可生成远程二维码，两端有互联网即可配对；密钥由 Android Keystore 保护")
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) { items(computers, key = { it.serverId }) { computer ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(computer.serverName, style = MaterialTheme.typography.titleLarge)
                                Text(computer.state, color = if (computer.state == "已连接") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (computer.error.isNotBlank()) Text(computer.error, style = MaterialTheme.typography.bodySmall)
                                if (computer.relayGapUntil > computer.cursor) Text("本次通知可能有缺失，可切换局域网补收短期队列中的通知", style = MaterialTheme.typography.bodySmall)
                                Text("局域网地址：${computer.baseUrl}", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { editingLan = computer; lanAddressText = computer.baseUrl; lanAddressError = ""; switchAfterAddress = false }) { Text("更新局域网地址") }
                                Text("已保存游标 ${computer.cursor}", style = MaterialTheme.typography.labelSmall)
                                Text(if (computer.remoteEnabled) "跨网络接收 · 仅保留本次会话通知" else "局域网接收 · 断网后补收本次会话通知", style = MaterialTheme.typography.labelSmall)
                                TextButton(onClick = { lifecycleScope.launch {
                                    if (!computer.remoteEnabled && app.tokens.get(computer.serverId + ":ntfy") == null) {
                                        pairMessage = "请先在电脑开启跨网络功能，然后重新扫码配对"
                                    } else if (computer.remoteEnabled && runCatching { LanAddress.normalize(computer.baseUrl) }.isFailure) {
                                        editingLan = computer; lanAddressText = computer.baseUrl; lanAddressError = "请填写电脑当前局域网地址后切换"; switchAfterAddress = true
                                    } else { app.db.dao().remote(computer.serverId, !computer.remoteEnabled); BridgeService.start(this@MainActivity) }
                                } }) { Text(if (computer.remoteEnabled) "切换局域网接收" else "切换跨网络接收") }
                                Row { TextButton(onClick = { lifecycleScope.launch { app.db.dao().enabled(computer.serverId, true, "等待连接"); requestNotices(); BridgeService.start(this@MainActivity) } }) { Text("重新连接") }
                                    TextButton(onClick = { requestScan() }, enabled = !pairing) { Text("重新配对") }
                                    TextButton(onClick = { deleting = computer }) { Text("移除") }
                                }
                            } }
                        } }
                    }
                    2 -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("接收与后台", style = MaterialTheme.typography.titleLarge)
                        Text("开始本次接收会清空旧列表，并从首次连接后接收新通知。短暂断网重连、切换接收方式不会清空本次消息。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { requestNotices(); BridgeService.start(this@MainActivity, newSession = true) }, enabled = computers.isNotEmpty()) { Text("开始本次接收") }
                            OutlinedButton(onClick = { startService(Intent(this@MainActivity, BridgeService::class.java).setAction("STOP")) }) { Text("停止接收") }
                        }
                        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("后台诊断", style = MaterialTheme.typography.titleMedium)
                            Text("系统通知：${if (notificationsEnabled) "已开启" else "未开启"}")
                            Text("电池优化：${if (batteryUnrestricted) "不受优化" else "由系统管理"}")
                            Text("连接状态以电脑页实际握手结果为准。息屏或省电策略可能延迟接收；恢复后补收本次会话内仍在临时队列中的通知。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }) { Text("系统通知设置") }
                            TextButton(onClick = { launchSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }) { Text("电池后台设置") }
                            TextButton(onClick = { launchSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) { Text("应用权限与自启动设置") }
                        } }
                        Card { Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) { Text("新通知声音"); Text("断网补收保持静默，系统设置可覆盖声音", style = MaterialTheme.typography.bodySmall) }
                            Switch(sound, { sound = it; app.preferences.edit().putBoolean("sound", it).apply() })
                        } }
                        OutlinedButton(onClick = { clear = true }) { Text("清空本次消息") }
                        Text("Win2Mobile 3.0.0 · 协议 v1\n正常开机后恢复已开启的接收。强制停止后需手动打开应用。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (paste) AlertDialog(onDismissRequest = { paste = false }, title = { Text("粘贴电脑配对信息") }, text = { OutlinedTextField(pasteText, { pasteText = it }, label = { Text("二维码 JSON 内容") }, minLines = 4, maxLines = 8) }, confirmButton = { TextButton(onClick = { pair(pasteText); pasteText = ""; paste = false }) { Text("请求配对") } }, dismissButton = { TextButton(onClick = { pasteText = ""; paste = false }) { Text("取消") } })
        editingLan?.let { computer -> AlertDialog(onDismissRequest = { editingLan = null }, title = { Text("更新 ${computer.serverName} 的局域网地址") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("填写电脑“配对手机”页的局域网地址。手机需能访问该局域网；远程中转地址与此地址不同。原授权与证书校验会保留。")
                OutlinedTextField(lanAddressText, { lanAddressText = it; lanAddressError = "" }, label = { Text("电脑 IP 或 HTTPS 地址") }, singleLine = true, isError = lanAddressError.isNotBlank())
                if (lanAddressError.isNotBlank()) Text(lanAddressError, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = {
            val address = runCatching { LanAddress.normalize(lanAddressText) }.getOrElse { lanAddressError = it.message ?: "地址无效"; return@TextButton }
            val shouldSwitch = switchAfterAddress
            lifecycleScope.launch {
                try {
                    app.db.dao().updateLanAddress(computer.serverId, address)
                    if (shouldSwitch) app.db.dao().remote(computer.serverId, false)
                    if (shouldSwitch || (!computer.remoteEnabled && app.preferences.getBoolean("running", false))) BridgeService.start(this@MainActivity)
                    pairMessage = "局域网地址已更新，原有授权和本次消息保留"
                    editingLan = null
                } catch (e: Exception) { lanAddressError = if (e is CancellationException) throw e else "地址保存失败，请重试" }
            }
        }) { Text(if (switchAfterAddress) "保存并切换局域网" else "保存地址") } }, dismissButton = { TextButton(onClick = { editingLan = null }) { Text("取消") } }) }
        selected?.let { item -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(item.title.ifBlank { "通知详情" }) }, text = { SelectionContainerCompat(item) }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } }, dismissButton = { TextButton(onClick = {
            lifecycleScope.launch { app.db.dao().deleteNotification(item.serverId, item.eventId); getSystemService(android.app.NotificationManager::class.java).cancel("${item.serverId}:${item.eventId}", 2) }; selected = null
        }) { Text("删除这条通知") } }) }
        deleting?.let { computer -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("移除 ${computer.serverName}？") }, text = { Text("将删除此电脑的手机凭据与本地历史。电脑端授权需在电脑设备列表撤销。") }, confirmButton = { TextButton(onClick = {
            lifecycleScope.launch { app.db.dao().removeComputer(computer.serverId); app.tokens.remove(computer.serverId); app.tokens.remove(computer.serverId + ":ntfy"); if (app.preferences.getBoolean("running", false)) BridgeService.start(this@MainActivity) }; deleting = null
        }) { Text("移除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
        if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清空本次消息？") }, text = { Text("清空手机消息列表，保留接收进度和删除标记；重连及切换接收方式不会重新显示这些消息。") }, confirmButton = { TextButton(onClick = { lifecycleScope.launch { app.db.dao().clearHistory() }; clear = false }) { Text("清空") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
    }
    @Composable private fun SelectionContainerCompat(item: SavedNotification) {
        androidx.compose.foundation.text.selection.SelectionContainer { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${item.appName} · ${formatTime(item.occurredAt)}", style = MaterialTheme.typography.labelMedium)
            Text(item.body.ifBlank { "没有正文" })
        } }
    }
    @Composable private fun EmptyState(title: String, body: String) { Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleLarge); Text(body, style = MaterialTheme.typography.bodyMedium) } }
    private fun formatTime(value: String): String = runCatching { DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value)) }.getOrDefault(value)
}
