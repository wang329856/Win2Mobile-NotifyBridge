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
        val payload = runCatching { PairPayload.parse(text) }.getOrElse { pairMessage = it.message ?: "配对信息无效"; return }
        pairing = true; pairMessage = "正在安全连接 ${payload.serverName}"
        pairJob = lifecycleScope.launch {
            val client = app.transports.create(payload.baseUrl, payload.certificateSha256)
            try {
                app.initialized.await()
                val result = client.pair(payload, "${Build.MANUFACTURER} ${Build.MODEL}") { pairMessage = it }
                app.tokens.put(payload.serverId, result.accessToken!!)
                app.db.dao().savePairing(Computer(payload.serverId, result.serverName, payload.baseUrl.trimEnd('/'), payload.certificateSha256, result.deviceId!!))
                pairMessage = "配对成功，正在同步 ${result.serverName}"
                requestNotices(); BridgeService.start(this@MainActivity)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                pairMessage = when (e) {
                    is javax.net.ssl.SSLException -> "证书校验失败，请核对电脑与手机时间，并重新扫描二维码"
                    is java.io.IOException -> "无法连接电脑，请确认两端位于同一局域网且电脑服务已启动"
                    else -> e.message ?: "配对失败，请重新扫码"
                }
            } finally { client.close(); pairing = false }
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
        var clear by remember { mutableStateOf(false) }
        var sound by remember { mutableStateOf(app.preferences.getBoolean("sound", true)) }
        val focused = focusEvent
        LaunchedEffect(focused) { focused?.let { key -> app.db.dao().savedEvent(key.first, key.second)?.let { selected = it; focusEvent = null; tab = 0 } } }
        Scaffold(topBar = { TopAppBar(title = { Column { Text("Win2Mobile"); Text("电脑通知 · 安全局域网同步", style = MaterialTheme.typography.labelMedium) } }) },
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
                        if (matches.isEmpty()) EmptyState(if (total == 0) "通知会保存在这里" else "没有匹配的通知", if (computers.isEmpty()) "在电脑页扫描二维码，连接你的电脑" else "电脑发送的新通知与历史消息将自动同步")
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
                        Text("在 Windows 客户端打开配对二维码，扫码后在电脑上批准。", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
                        if (computers.isEmpty()) EmptyState("连接你的第一台电脑", "只在同一局域网内同步；凭据由 Android Keystore 加密保存")
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) { items(computers, key = { it.serverId }) { computer ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(computer.serverName, style = MaterialTheme.typography.titleLarge)
                                Text(computer.state, color = if (computer.state == "已连接") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (computer.error.isNotBlank()) Text(computer.error, style = MaterialTheme.typography.bodySmall)
                                Text(computer.baseUrl, style = MaterialTheme.typography.bodySmall)
                                Text("已保存游标 ${computer.cursor}", style = MaterialTheme.typography.labelSmall)
                                Row { TextButton(onClick = { lifecycleScope.launch { app.db.dao().enabled(computer.serverId, true, "等待连接"); requestNotices(); BridgeService.start(this@MainActivity) } }) { Text("重新连接") }
                                    TextButton(onClick = { requestScan() }, enabled = !pairing) { Text("重新配对") }
                                    TextButton(onClick = { deleting = computer }) { Text("移除") }
                                }
                            } }
                        } }
                    }
                    2 -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("接收与后台", style = MaterialTheme.typography.titleLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { requestNotices(); BridgeService.start(this@MainActivity) }, enabled = computers.isNotEmpty()) { Text("开始接收") }
                            OutlinedButton(onClick = { startService(Intent(this@MainActivity, BridgeService::class.java).setAction("STOP")) }) { Text("停止接收") }
                        }
                        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("后台诊断", style = MaterialTheme.typography.titleMedium)
                            Text("系统通知：${if (notificationsEnabled) "已开启" else "未开启"}")
                            Text("电池优化：${if (batteryUnrestricted) "不受优化" else "由系统管理"}")
                            Text("连接状态以电脑页实际握手结果为准。前台服务不自动豁免 Doze；息屏或厂商省电策略可能延迟同步，恢复后会补取历史。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }) { Text("系统通知设置") }
                            TextButton(onClick = { launchSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }) { Text("电池后台设置") }
                            TextButton(onClick = { launchSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) { Text("应用权限与自启动设置") }
                        } }
                        Card { Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) { Text("新通知声音"); Text("历史重放保持静默，系统设置可覆盖声音", style = MaterialTheme.typography.bodySmall) }
                            Switch(sound, { sound = it; app.preferences.edit().putBoolean("sound", it).apply() })
                        } }
                        OutlinedButton(onClick = { clear = true }) { Text("清理手机通知历史") }
                        Text("Win2Mobile 3.0.0 · 协议 v1\n正常开机后恢复已开启的接收。强制停止后需手动打开应用。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (paste) AlertDialog(onDismissRequest = { paste = false }, title = { Text("粘贴电脑配对信息") }, text = { OutlinedTextField(pasteText, { pasteText = it }, label = { Text("二维码 JSON 内容") }, minLines = 4, maxLines = 8) }, confirmButton = { TextButton(onClick = { pair(pasteText); pasteText = ""; paste = false }) { Text("请求配对") } }, dismissButton = { TextButton(onClick = { pasteText = ""; paste = false }) { Text("取消") } })
        selected?.let { item -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(item.title.ifBlank { "通知详情" }) }, text = { SelectionContainerCompat(item) }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } }) }
        deleting?.let { computer -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("移除 ${computer.serverName}？") }, text = { Text("将删除此电脑的手机凭据与本地历史。电脑端授权需在电脑设备列表撤销。") }, confirmButton = { TextButton(onClick = {
            lifecycleScope.launch { app.db.dao().removeComputer(computer.serverId); app.tokens.remove(computer.serverId); if (app.preferences.getBoolean("running", false)) BridgeService.start(this@MainActivity) }; deleting = null
        }) { Text("移除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
        if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清理手机历史？") }, text = { Text("只清理当前手机的通知记录，保留同步游标，电脑历史不会再次成批下载。") }, confirmButton = { TextButton(onClick = { lifecycleScope.launch { app.db.dao().clearHistory() }; clear = false }) { Text("清理") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
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
