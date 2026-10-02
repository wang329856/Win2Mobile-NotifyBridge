package com.notifforward.app

import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

interface MessageNotificationSink {
    fun activeMessages(): List<Pair<String, String>>
    fun cancelEvent(serverId: String, eventId: String)
    fun cancelComputer(serverId: String)
    fun cancelMessages()
}

/** Owns message notifications only; the ongoing receiver notification (ID 1) survives. */
class AndroidMessageNotifications(context: Context) : MessageNotificationSink {
    private val manager = context.getSystemService(NotificationManager::class.java)
    override fun cancelEvent(serverId: String, eventId: String) = manager.cancel("$serverId:$eventId", MESSAGE_ID)
    override fun cancelComputer(serverId: String) = cancelMatching { it.startsWith("$serverId:") }
    override fun cancelMessages() = cancelMatching { true }
    override fun activeMessages(): List<Pair<String, String>> = manager.activeNotifications
        .filter { it.id == MESSAGE_ID }
        .mapNotNull { note -> note.tag?.split(':', limit = 2)?.takeIf { it.size == 2 }?.let { it[0] to it[1] } }
    private fun cancelMatching(matches: (String) -> Boolean) {
        manager.activeNotifications.filter { it.id == MESSAGE_ID && it.tag?.let(matches) == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }
    companion object { const val MESSAGE_ID = 2 }
}

/** One application-wide lock covers persistence/display and all content-removal paths.
 * Network acknowledgement stays outside the lock, so slow networks cannot block deletion.
 */
class NotificationRetention(private val sink: MessageNotificationSink) {
    private val mutex = Mutex()
    suspend fun reconcile(exists: suspend (String, String) -> Boolean) = mutex.withLock {
        // Also remove stale notifications left by versions predating this coordinator.
        sink.activeMessages().forEach { (server, event) -> if (!exists(server, event)) sink.cancelEvent(server, event) }
    }
    suspend fun deliver(isReplay: Boolean, save: suspend () -> Boolean, display: () -> Unit, acknowledge: suspend () -> Unit) {
        mutex.withLock { EventDelivery.deliver(isReplay, save, display, acknowledge = {}) }
        acknowledge()
    }
    suspend fun <T> delete(serverId: String, eventId: String, change: suspend () -> T): T =
        mutate(change) { sink.cancelEvent(serverId, eventId) }
    suspend fun <T> clear(change: suspend () -> T): T = mutate(change) { sink.cancelMessages() }
    suspend fun <T> replaceComputer(serverId: String, change: suspend () -> T): T =
        mutate(change) { sink.cancelComputer(serverId) }
    suspend fun removeComputer(serverId: String, removeContent: suspend () -> Unit, removeCredentials: () -> Unit): Result<Unit> {
        // Credential persistence can fail after Room has committed. Withdraw first,
        // so that failure cannot strand plaintext notifications from a removed PC.
        replaceComputer(serverId, removeContent)
        return runCatching { removeCredentials() }
    }
    private suspend fun <T> mutate(change: suspend () -> T, withdraw: () -> Unit): T = mutex.withLock {
        // Once deletion starts, finish both the committed DB change and withdrawal,
        // even if the ViewModel is destroyed while Room is resuming its coroutine.
        withContext(NonCancellable) {
            val result = change()
            withdraw()
            result
        }
    }
    suspend fun <T> restore(change: suspend () -> T): T = mutex.withLock { change() }
}
