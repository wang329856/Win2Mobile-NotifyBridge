package com.notifforward.app

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable fun BridgeScreen(model: BridgeViewModel, onScan: (String?) -> Unit, onPermissions: () -> Unit, onSettings: (String) -> Unit, initialTab: Int = 0) {
    val computers by model.computers.collectAsStateWithLifecycle()
    val total by model.total.collectAsStateWithLifecycle()
    val apps by model.apps.collectAsStateWithLifecycle()
    val active by model.serviceActive.collectAsStateWithLifecycle()
    val preferences by model.preferences.collectAsStateWithLifecycle()
    val pairing by model.pairing.collectAsStateWithLifecycle()
    val selected by model.selected.collectAsStateWithLifecycle()
    val notices by model.notificationsEnabled.collectAsStateWithLifecycle()
    val battery by model.batteryUnrestricted.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var limit by rememberSaveable { mutableIntStateOf(200) }
    var paginationQuery by rememberSaveable { mutableStateOf(query) }
    var paginationFilter by rememberSaveable { mutableStateOf(filter) }
    val history by remember(query, filter, limit) { model.history(query, filter, limit) }.collectAsStateWithLifecycle(emptyList())
    val matches by remember(query, filter) { model.matchCount(query, filter) }.collectAsStateWithLifecycle(0)
    val listState = rememberLazyListState()
    val computerListState = rememberLazyListState()
    val settingsState = rememberScrollState()
    var filterMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var paste by rememberSaveable { mutableStateOf(false) }
    var pasteText by rememberSaveable { mutableStateOf("") }
    var computerId by rememberSaveable { mutableStateOf<String?>(null) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var addressText by rememberSaveable { mutableStateOf("") }
    var addressError by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
    var unseen by remember(query, filter) { mutableIntStateOf(0) }
    var head by remember(query, filter) { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun delete(item: SavedNotification) { model.delete(item) { ticket -> if (ticket != null) scope.launch {
        if (snackbar.showSnackbar("通知已删除", "撤销", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) model.undo(ticket)
    } } }
    LaunchedEffect(model) { for (message in model.messages) snackbar.showSnackbar(message) }
    LaunchedEffect(query, filter) {
        if (query != paginationQuery || filter != paginationFilter) {
            limit = 200; unseen = 0; paginationQuery = query; paginationFilter = filter
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(selected?.eventId) { if (selected != null) tab = 0 }
    LaunchedEffect(pairing.message) { if (pairing.message.startsWith("配对成功")) { tab = 0; onPermissions() } }
    LaunchedEffect(history.firstOrNull()?.eventId, query, filter) {
        val top = history.firstOrNull()?.let { it.serverId + it.eventId }
        val previous = head
        if (top != previous && previous != null && listState.firstVisibleItemIndex > 1) {
            val index = history.indexOfFirst { it.serverId + it.eventId == previous }
            if (index > 0) unseen += index
        }
        head = top
    }
    LaunchedEffect(listState, history.size, matches, query, filter) {
        snapshotFlow { val info = listState.layoutInfo; info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4 }
            .distinctUntilChanged().collect { nearEnd -> if (nearEnd && history.isNotEmpty() && history.size < matches) limit += 200 }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 600.dp
        val splitDetail = maxWidth >= 840.dp
        val detailHeight = maxHeight * 0.85f
        Row(Modifier.fillMaxSize()) {
            val labels = listOf("通知", "电脑", "设置")
            val icons = listOf(R.drawable.nav_history, R.drawable.nav_computers, R.drawable.nav_settings)
            if (wide) NavigationRail(Modifier.fillMaxHeight().width(88.dp), containerColor = MaterialTheme.colorScheme.surface) {
                Spacer(Modifier.height(32.dp))
                labels.forEachIndexed { index, text -> NavigationRailItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(painterResource(icons[index]), null) }, label = { Text(text) }) }
            }
            Scaffold(Modifier.weight(1f), containerColor = MaterialTheme.colorScheme.background,
                topBar = { TopAppBar(title = { Text("Win2Mobile", style = MaterialTheme.typography.titleLarge) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    actions = {
                        if (tab == 1) TextButton(onClick = { onScan(null) }, enabled = !pairing.busy) { Text("＋ 连接") }
                        Box {
                            TextButton(onClick = { moreMenu = true }) { Text("更多") }
                            DropdownMenu(moreMenu, { moreMenu = false }) {
                                DropdownMenuItem(text = { Text("粘贴配对信息") }, onClick = { moreMenu = false; paste = true }, enabled = !pairing.busy)
                                DropdownMenuItem(text = { Text("开始新一轮接收") }, onClick = { moreMenu = false; confirmation = "new" }, enabled = computers.isNotEmpty())
                                DropdownMenuItem(text = { Text("清空本次消息") }, onClick = { moreMenu = false; confirmation = "clear" }, enabled = total > 0)
                            }
                        }
                    }) },
                bottomBar = { if (!wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    labels.forEachIndexed { index, text -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(painterResource(icons[index]), null) }, label = { Text(text) }) }
                } }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = if (wide) 24.dp else 18.dp)) {
                    AnimatedVisibility(pairing.message.isNotBlank(), enter = fadeIn(tween(220)) + expandVertically(), exit = fadeOut(tween(100)) + shrinkVertically()) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (pairing.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Text(pairing.message, Modifier.weight(1f).padding(start = if (pairing.busy) 12.dp else 0.dp), style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = model::dismissPairing) { Text(if (pairing.busy) "取消" else "关闭") }
                                }
                                Regex("(?<![0-9])[0-9]{6}(?![0-9])").find(pairing.message)?.let { Text(it.value, style = MaterialTheme.typography.headlineLarge) }
                            }
                        }
                    }
                    AnimatedContent(tab, transitionSpec = {
                        (fadeIn(tween(220)) + slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 600f)) { it / 24 }) togetherWith fadeOut(tween(100))
                    }, label = "页面切换", modifier = Modifier.weight(1f)) { page ->
                        when (page) {
                            0 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                Column(Modifier.weight(1f)) {
                                    if (unseen > 0) FilledTonalButton(onClick = { unseen = 0; scope.launch { listState.animateScrollToItem(0) } }, modifier = Modifier.fillMaxWidth()) { Text("有 " + unseen + " 条新消息 · 回到顶部") }
                                    LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                                        item("receiving") { ReceivingCard(computers, active, preferences.requestedReceiving, { if (active) model.pause() else { onPermissions(); model.resume() } }, { onScan(null) }) }
                                        if (!notices && computers.isNotEmpty()) item("permission") { Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Hint("消息可保存，但系统通知未开启", Modifier.weight(1f)); TextButton(onClick = { onSettings("notifications") }) { Text("开启通知") } } }
                                        item("search") {
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("搜索应用、标题或正文") }, shape = MaterialTheme.shapes.large,
                                                    trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除") } })
                                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                    Text("本次消息 · " + total, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                                    Box {
                                                        TextButton(onClick = { filterMenu = true }) { Text(apps.firstOrNull { it.appId == filter }?.appName ?: "全部应用 ▾") }
                                                        DropdownMenu(filterMenu, { filterMenu = false }) {
                                                            DropdownMenuItem(text = { Text("全部应用") }, onClick = { filter = null; filterMenu = false })
                                                            apps.forEach { option -> DropdownMenuItem(text = { Text(option.appName) }, onClick = { filter = option.appId; filterMenu = false }) }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        if (history.isEmpty()) item("empty") { EmptyNotifications(computers.isNotEmpty(), query.isNotBlank() || filter != null, { onScan(null) }) }
                                        history.groupBy { notificationDate(it.occurredAt) }.forEach { (date, events) ->
                                            item("date:" + date) { Text(date, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
                                            items(events, key = { it.serverId + ":" + it.eventId }) { item ->
                                                val dismiss = rememberSwipeToDismissBoxState()
                                                LaunchedEffect(dismiss.settledValue) {
                                                    if (dismiss.settledValue == SwipeToDismissBoxValue.EndToStart) {
                                                        // Reset saved swipe state before removal. This job must outlive
                                                        // the row effect whose key changes when snapTo settles it.
                                                        scope.launch { dismiss.snapTo(SwipeToDismissBoxValue.Settled); delete(item) }
                                                    }
                                                }
                                                SwipeToDismissBox(dismiss, enableDismissFromStartToEnd = false, backgroundContent = {
                                                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.large).padding(24.dp), contentAlignment = Alignment.CenterEnd) { Text("删除", color = MaterialTheme.colorScheme.onErrorContainer) }
                                                }, modifier = Modifier.animateItem()) { NotificationSummary(item, computers.firstOrNull { it.serverId == item.serverId }?.serverName ?: "电脑", { model.selected.value = item },
                                                    Modifier.semantics { customActions = listOf(CustomAccessibilityAction("删除通知") { delete(item); true }) }) }
                                            }
                                        }
                                        if (history.size < matches && history.isNotEmpty()) item("loading") { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Hint("正在加载更多", Modifier.padding(start = 12.dp)) } }
                                    }
                                }
                                if (splitDetail && selected != null) Card(Modifier.width(320.dp).fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                                    NotificationDetail(selected!!, { delete(selected!!) }, { model.selected.value = null }, Modifier.fillMaxSize().padding(top = 20.dp))
                                }
                            }
                            1 -> LazyColumn(Modifier.fillMaxSize(), state = computerListState, verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                                item { Text("你的电脑", style = MaterialTheme.typography.headlineLarge); Hint("连一次，以后更轻松。", Modifier.padding(top = 8.dp, bottom = 4.dp)) }
                                item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("同网直连，跨网也能接收", style = MaterialTheme.typography.titleLarge); Hint("自动选择已授权的通道，回到同网后自动切回。配对时请核对两端信息。"); Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = { onScan(null) }, enabled = !pairing.busy) { Text("扫码连接") }; TextButton(onClick = { paste = true }, enabled = !pairing.busy) { Text("粘贴信息") } } } } }
                                items(computers, key = { it.serverId }) { pc -> ComputerCard(pc, active, { computerId = pc.serverId }, { onPermissions(); model.reconnect(pc.serverId) }) }
                                if (computers.isEmpty()) item { Hint("打开电脑端，点击“连接手机”，再扫描二维码。") }
                            }
                            else -> Column(Modifier.fillMaxSize().verticalScroll(settingsState), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("按你的习惯", style = MaterialTheme.typography.headlineLarge)
                                Hint("少一些操作，多一些自在。")
                                SettingsCard("外观与声音") {
                                    Text("主题", style = MaterialTheme.typography.titleSmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("System" to "系统", "Light" to "浅色", "Dark" to "深色").forEach { (value, label) -> FilterChip(selected = preferences.theme == value, onClick = { model.theme(value) }, label = { Text(label) }) } }
                                    Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("新通知声音"); Hint("补收保持静默，系统设置可覆盖声音") }; Switch(preferences.sound, model::sound) }
                                }
                                SettingsCard("接收与后台") {
                                    Text(if (active) "● 接收服务正在运行" else "Ⅱ 接收服务未运行", color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Hint("暂停和继续保留本次消息。开始新一轮会清空列表，只接收首次连接后的新消息。")
                                    TextButton(onClick = { confirmation = "new" }, enabled = computers.isNotEmpty()) { Text("开始新一轮接收") }
                                    HorizontalDivider()
                                    Text("系统通知：" + if (notices) "已开启" else "未开启")
                                    Text("电池优化：" + if (battery) "不受优化" else "由系统管理")
                                    Hint("息屏或省电策略可能延迟接收；恢复后补收本次仍在短期队列中的消息。")
                                    TextButton(onClick = { onSettings("notifications") }) { Text("系统通知设置") }
                                    TextButton(onClick = { onSettings("battery") }) { Text("电池后台设置") }
                                    TextButton(onClick = { onSettings("app") }) { Text("权限与自启动设置") }
                                }
                                SettingsCard("本次消息") { Text(total.toString() + " 条已保存"); TextButton(onClick = { confirmation = "clear" }, enabled = total > 0, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清空本次消息") } }
                                Hint("Win2Mobile 3.1.0 · 协议 v1\n开机后恢复已开启的接收；系统强制停止后需手动打开。", Modifier.padding(bottom = 24.dp))
                            }
                        }
                    }
                }
            }
        }
        if (selected != null && !splitDetail) ModalBottomSheet(onDismissRequest = { model.selected.value = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            NotificationDetail(selected!!, { delete(selected!!) }, { model.selected.value = null }, Modifier.fillMaxWidth().heightIn(max = detailHeight))
        }
    }
    if (paste) ModalBottomSheet(onDismissRequest = { paste = false }) {
        Column(Modifier.padding(24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("粘贴电脑配对信息", style = MaterialTheme.typography.headlineSmall)
            Hint("在电脑复制配对信息，通过可信渠道传到自己的手机。")
            OutlinedTextField(pasteText, { pasteText = it }, modifier = Modifier.fillMaxWidth(), label = { Text("配对信息") }, minLines = 3, maxLines = 6)
            Button(onClick = { model.pair(pasteText); pasteText = ""; paste = false }, enabled = pasteText.isNotBlank() && !pairing.busy, modifier = Modifier.fillMaxWidth()) { Text("安全连接") }
            Spacer(Modifier.height(12.dp))
        }
    }
    val computer = computers.firstOrNull { it.serverId == computerId }
    if (computer != null) ModalBottomSheet(onDismissRequest = { computerId = null }) {
        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(computer.serverName, style = MaterialTheme.typography.headlineSmall)
            Hint(computer.error.ifBlank { "自动模式优先局域网，直连不可用时使用已授权的中转。" })
            Text("接收方式", style = MaterialTheme.typography.titleSmall)
            ConnectionMode.entries.forEach { mode -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = computer.connectionMode == mode.name, role = Role.RadioButton, onClick = { model.mode(computer.serverId, mode) }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = computer.connectionMode == mode.name, onClick = null); Text(mode.label, Modifier.padding(start = 12.dp))
            } }
            HorizontalDivider()
            if (!computer.remoteEnabled) {
                Hint("此电脑尚未提供跨网络凭据。先在电脑设置启用跨网络推送，再扫描它的新二维码获取授权。")
                TextButton(onClick = { confirmation = "pair:" + computer.serverId }) { Text("启用跨网络 · 重新扫码") }
            }
            Hint("局域网地址：" + computer.baseUrl)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { computerId = null; onScan(computer.serverId) }) { Text("扫码更新地址") }
                TextButton(onClick = { editId = computer.serverId; addressText = computer.baseUrl; addressError = null; computerId = null }) { Text("手动编辑") }
            }
            Hint("保存进度：" + computer.cursor + " · 中转进度：" + computer.relaySequence)
            if (computer.relayGapUntil > computer.cursor) Hint("本次消息有缺口，局域网可达后会补收仍在队列中的通知。")
            TextButton(onClick = { confirmation = "pair:" + computer.serverId }) { Text("重新配对") }
            TextButton(onClick = { confirmation = "remove:" + computer.serverId }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("移除电脑") }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (editId != null) AlertDialog(onDismissRequest = { editId = null }, title = { Text("更新局域网地址") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Hint("填写电脑配对面板中的当前地址，原授权和本次消息会保留。")
        OutlinedTextField(addressText, { addressText = it; addressError = null }, label = { Text("电脑地址") }, singleLine = true, isError = addressError != null)
        addressError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton(onClick = { val id = editId ?: return@TextButton; model.updateAddress(id, addressText) { error -> if (error == null) editId = null else addressError = error } }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editId = null }) { Text("取消") } })
    confirmation?.let { action ->
        val title = when { action == "new" -> "开始新一轮接收？"; action == "clear" -> "清空本次消息？"; action.startsWith("pair:") -> "重新配对这台电脑？"; else -> "移除这台电脑？" }
        val body = when { action == "new" -> "清空所有电脑的本次消息，并从首次连接后接收新通知。原设备授权保留。"; action == "clear" -> "清空列表，保留接收进度和删除标记；重连不会恢复这些消息。"; action.startsWith("pair:") -> "将替换这台电脑的凭据并清空它的本次消息。其他电脑保留。"; else -> "删除这台电脑的手机凭据和本地消息。电脑端授权需在电脑设备页撤销。" }
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(title) }, text = { Text(body) }, confirmButton = { TextButton(onClick = {
            when { action == "new" -> { onPermissions(); model.newSession() }; action == "clear" -> model.clear(); action.startsWith("pair:") -> { computerId = null; onScan(null) }; else -> { model.remove(action.substringAfter(':')); computerId = null } }
            confirmation = null
        }) { Text("确认") } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } })
    }
}
