package com.notifforward.app

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import android.os.Build

 data class PairingUiState(val busy: Boolean = false, val message: String = "")
 data class AppearancePreferences(val theme: String = "System", val sound: Boolean = true, val requestedReceiving: Boolean = false)
 class BridgeViewModel @JvmOverloads constructor(application: Application, private val database: BridgeDatabase = (application as BridgeApplication).db, private val preview: Boolean = false) : AndroidViewModel(application) {
    private val app = application as BridgeApplication
    private val dao get() = database.dao()
    private val _pairing = MutableStateFlow(PairingUiState())
    val pairing = _pairing.asStateFlow()
    val computers = dao.observeComputers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val total = dao.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val apps = dao.observeApps().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val receiving = if (preview) MutableStateFlow(true) else app.serviceActive
    val serviceActive = receiving.asStateFlow()
    val selected = MutableStateFlow<SavedNotification?>(null)
    val notificationsEnabled = MutableStateFlow(false)
    val batteryUnrestricted = MutableStateFlow(false)
    private fun readPreferences() = AppearancePreferences(app.preferences.getString("theme", "System") ?: "System", app.preferences.getBoolean("sound", true), app.preferences.getBoolean("running", false))
    private val _preferences = MutableStateFlow(if (preview) AppearancePreferences(requestedReceiving = true) else readPreferences())
    val preferences = _preferences.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _preferences.value = readPreferences() }
    val messages = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
    var scanTarget: String? = null
    private var pairJob: Job? = null
    init { if (!preview) app.preferences.registerOnSharedPreferenceChangeListener(listener) }
    fun history(query: String, filter: String?, limit: Int) = dao.observeNotifications(query.trim(), filter, limit)
    fun matchCount(query: String, filter: String?) = dao.observeMatchCount(query.trim(), filter)
    fun theme(value: String) { if (preview) _preferences.value = _preferences.value.copy(theme = value) else app.preferences.edit().putString("theme", value).apply() }
    fun sound(value: Boolean) { if (preview) _preferences.value = _preferences.value.copy(sound = value) else app.preferences.edit().putBoolean("sound", value).apply() }
    fun resume() { if (preview) receiving.value = true else BridgeService.start(app) }
    fun pause() { if (preview) receiving.value = false else BridgeService.pause(app) }
    fun newSession() { selected.value = null; if (preview) viewModelScope.launch { dao.beginSession(); receiving.value = true } else BridgeService.start(app, newSession = true) }
    fun clear() = viewModelScope.launch { dao.clearHistory(); selected.value = null; messages.send("本次消息已清空，接收进度保留") }
    fun focus(serverId: String, eventId: String) = viewModelScope.launch {
        app.initialized.await(); selected.value = dao.savedEvent(serverId, eventId)
        if (selected.value == null) messages.send("这条通知已删除或不在本次列表中")
    }
    fun delete(item: SavedNotification, complete: (DeletedNotification?) -> Unit) = viewModelScope.launch {
        val ticket = dao.deleteWithUndo(item); if (selected.value == item) selected.value = null; complete(ticket)
    }
    fun undo(ticket: DeletedNotification) = viewModelScope.launch {
        if (!dao.restoreNotification(ticket)) messages.send("本次接收已变化，无法恢复这条消息")
    }
    fun mode(id: String, mode: ConnectionMode) = viewModelScope.launch {
        if (!preview && mode == ConnectionMode.RELAY && app.tokens.get(id + ":ntfy") == null) { messages.send("请先在电脑开启跨网络推送，再重新配对获取密钥"); return@launch }
        dao.connectionMode(id, mode.name)
        if (!preview && app.preferences.getBoolean("running", false)) BridgeService.start(app)
    }
    fun reconnect(id: String) = viewModelScope.launch {
        val pc = dao.computer(id) ?: return@launch
        if (!pc.enabled) { messages.send("该电脑需要重新授权或核验，请先更新地址或重新配对"); return@launch }
        resume()
    }
    fun updateAddress(id: String, value: String, complete: (String?) -> Unit) = viewModelScope.launch {
        try {
            dao.updateLanAddress(id, value)
            val pc = dao.computer(id)
            if (pc?.state == "需要核验") dao.enabled(id, true, "等待连接")
            if (app.preferences.getBoolean("running", false)) resume()
            complete(null); messages.send("局域网地址已更新，授权与消息保留")
        } catch (e: Exception) { complete(e.message ?: "地址无效") }
    }
    fun remove(id: String) = viewModelScope.launch {
        dao.removeComputer(id); if (!preview) { app.tokens.remove(id); app.tokens.remove(id + ":ntfy") }
        if (app.preferences.getBoolean("running", false)) resume()
        messages.send("电脑已移除；电脑端授权可在设备页撤销")
    }
    fun dismissPairing() {
        if (_pairing.value.busy) pairJob?.cancel() else _pairing.value = PairingUiState()
    }
    fun report(message: String) { viewModelScope.launch { messages.send(message) } }
    fun scanResult(text: String) {
        val target = scanTarget; scanTarget = null
        if (target != null) {
            viewModelScope.launch {
                try {
                    val payload = PairPayload.parse(text)
                    val pc = dao.computer(target) ?: error("电脑已移除")
                    require(payload.serverId == pc.serverId && payload.certificateSha256.equals(pc.certificateSha256, true)) { "二维码不是此电脑的原授权身份，请核验后重新配对" }
                    updateAddress(target, payload.baseUrl) { error -> if (error != null) report(error) }
                } catch (e: Exception) { messages.send(e.message ?: "地址二维码无效") }
            }
        } else pair(text)
    }
    fun pair(text: String) {
        if (preview) { report("设计预览不会发起实际配对"); return }
        if (pairJob?.isActive == true) return
        val remote = runCatching {
            if (wireJson.parseToJsonElement(text).jsonObject["schema"]?.jsonPrimitive?.content == "win2mobile-ntfy-pair") wireJson.decodeFromString<RemotePairPayload>(text).validate() else null
        }.getOrElse { _pairing.value = PairingUiState(message = it.message ?: "配对信息无效"); return }
        val payload = runCatching { remote?.metadata() ?: PairPayload.parse(text) }.getOrElse { _pairing.value = PairingUiState(message = it.message ?: "配对信息无效"); return }
        _pairing.value = PairingUiState(true, "正在安全连接 " + payload.serverName)
        pairJob = viewModelScope.launch {
            val client = if (remote == null) app.transports.create(payload.baseUrl, payload.certificateSha256) else null
            val remoteClient = remote?.let { RemotePairClient(it) }
            try {
                app.initialized.await()
                val name = Build.MANUFACTURER + " " + Build.MODEL
                val waiting: (String) -> Unit = { _pairing.value = PairingUiState(true, it) }
                val result = remoteClient?.pair(name, waiting) ?: client!!.pair(payload, name, waiting)
                val relay = result.ntfy?.validate()
                if (relay != null) app.tokens.put(payload.serverId + ":ntfy", wireJson.encodeToString(relay)) else app.tokens.remove(payload.serverId + ":ntfy")
                app.tokens.put(payload.serverId, result.accessToken!!)
                dao.savePairing(Computer(payload.serverId, result.serverName, payload.baseUrl.trimEnd('/'), payload.certificateSha256, result.deviceId!!,
                    cursor = result.startSequence.also { require(it >= 0) }, remoteEnabled = relay != null, relaySequence = relay?.startSequence ?: 0))
                _pairing.value = PairingUiState(message = "配对成功，正在接收 " + result.serverName + " 的新通知")
                resume()
            } catch (_: TimeoutCancellationException) { _pairing.value = PairingUiState(message = "配对超时，请刷新电脑二维码后重试") }
            catch (e: CancellationException) { _pairing.value = PairingUiState(message = "配对已取消"); throw e }
            catch (e: Exception) {
                _pairing.value = PairingUiState(message = when (e) {
                    is javax.net.ssl.SSLException -> "电脑证书校验失败，请检查两端时间并重新扫码"
                    is java.io.IOException -> if (remote != null) "远程配对失败，请检查互联网和中转服务" else "无法连接电脑，请确认同一局域网和电脑服务"
                    else -> e.message ?: "配对失败，请重试"
                })
            } finally { client?.close(); remoteClient?.close() }
        }
    }
    override fun onCleared() { app.preferences.unregisterOnSharedPreferenceChangeListener(listener); super.onCleared() }
 }
