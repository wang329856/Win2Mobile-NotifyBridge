package com.notifforward.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter

fun notificationTime(value: String): String = runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault(value)
fun notificationDate(value: String): String = runCatching {
    val date = Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate()
    when (date) { LocalDate.now() -> "今天"; LocalDate.now().minusDays(1) -> "昨天"; else -> date.format(DateTimeFormatter.ofPattern("M 月 d 日")) }
}.getOrDefault("通知")
@Composable fun Hint(text: String, modifier: Modifier = Modifier) { Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable fun StatusPill(text: String, warning: Boolean = false) {
    Surface(color = if (warning) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium,
            color = if (warning) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
@Composable fun ReceivingCard(computers: List<Computer>, active: Boolean, requested: Boolean, onControl: () -> Unit, onScan: () -> Unit) {
    val lan = computers.count { it.state == "已连接" }
    val relay = computers.count { it.state == "中转已连接" }
    val title = when {
        computers.isEmpty() -> "让电脑通知，\n随你而行"
        !active && requested -> "接收暂未运行"
        !active -> "给通知，留一点空闲"
        lan > 0 -> "重要消息，正在同步"
        relay > 0 -> "离开 Wi-Fi，也保持连接"
        else -> "接收已开启，正在连接"
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("WIN2MOBILE  /  私人通知桥", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(when {
                computers.isEmpty() -> "扫描电脑二维码，连接你的第一台电脑。"
                !active -> "本次消息和接收进度都保留。继续后补收可用队列。"
                lan > 0 -> lan.toString() + " 台电脑直连" + if (relay > 0) " · " + relay + " 台使用中转" else " · 安全传输"
                relay > 0 -> "中转已连通；电脑仍需运行并开启跨网络推送。"
                else -> "正在选择可用通道；无需手动来回切换。"
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Button(onClick = if (computers.isEmpty()) onScan else onControl, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (computers.isEmpty()) "＋ 扫码连接电脑" else if (active) "Ⅱ  暂停接收" else "▶  继续接收")
            }
        }
    }
}
@Composable fun EmptyNotifications(hasComputers: Boolean, searching: Boolean, onScan: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraLarge) { Icon(painterResource(R.drawable.nav_history), null, Modifier.padding(22.dp).size(34.dp), tint = MaterialTheme.colorScheme.primary) }
        Text(if (searching) "没有匹配的消息" else "为新的消息，留个位置", style = MaterialTheme.typography.titleLarge)
        Hint(if (searching) "试试其他关键词，或切回全部应用。" else "本次接收的新通知会显示在这里，删除后不会重新出现。")
        if (!hasComputers) FilledTonalButton(onClick = onScan) { Text("扫码连接电脑") }
    }
}
@Composable fun NotificationSummary(item: SavedNotification, computer: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick, modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) { Text(item.appName.take(1), Modifier.padding(horizontal = 10.dp, vertical = 7.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                Text(item.appName, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Hint(notificationTime(item.occurredAt))
            }
            Text(item.title.ifBlank { "无标题" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.body.isNotBlank()) Text(item.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Hint(computer)
        }
    }
}
@Composable fun NotificationDetail(item: SavedNotification, onDelete: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    Column(modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { StatusPill(item.appName); TextButton(onClose) { Text("关闭") } }
        SelectionContainer(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(item.title.ifBlank { "通知详情" }, style = MaterialTheme.typography.headlineSmall)
                Hint(notificationDate(item.occurredAt) + " · " + notificationTime(item.occurredAt))
                Text(item.body, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { clipboard.setPrimaryClip(ClipData.newPlainText("Win2Mobile 通知", item.title + "\n\n" + item.body)) }, modifier = Modifier.weight(1f)) { Text("复制完整内容") }
            TextButton(onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
        }
    }
}
@Composable fun ComputerCard(computer: Computer, active: Boolean, onOpen: () -> Unit, onReconnect: () -> Unit) {
    val status = ConnectionStatus.from(computer)
    val label = if (!active && computer.enabled) "接收已暂停" else when (status.phase) { ConnectionPhase.LAN_CONNECTED -> "局域网直连"; ConnectionPhase.RELAY_CONNECTED -> "中转已连接"; else -> status.label }
    Card(onClick = onOpen, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.nav_computers), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Text(computer.serverName, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
                Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill(label, !computer.enabled || status.phase == ConnectionPhase.RETRYING)
            Hint(ConnectionMode.parse(computer.connectionMode).label)
            if (!computer.enabled || computer.relayGapUntil > computer.cursor) Hint(if (computer.relayGapUntil > computer.cursor) "本次消息可能有缺失，直连后会补收可用队列。" else computer.error)
            if (computer.enabled && status.phase == ConnectionPhase.RETRYING) TextButton(onReconnect) { Text("立即重试") }
        }
    }
}
@Composable fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = MaterialTheme.typography.titleLarge); content() }
    }
}
