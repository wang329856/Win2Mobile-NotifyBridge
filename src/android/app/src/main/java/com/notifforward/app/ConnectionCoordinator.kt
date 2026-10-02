package com.notifforward.app

import kotlinx.coroutines.*
import java.io.IOException
import javax.net.ssl.SSLException

enum class ConnectionMode(val label: String) {
    AUTO("自动 · 局域网优先"), LAN("仅局域网"), RELAY("仅跨网络");
    companion object { fun parse(value: String) = entries.firstOrNull { it.name == value } ?: AUTO }
}
enum class ActiveTransport { LAN, RELAY }
enum class ConnectionPhase { PAUSED, CONNECTING, LAN_CONNECTED, RELAY_CONNECTED, RETRYING, NEEDS_AUTHORIZATION, NEEDS_VERIFICATION }
data class ConnectionStatus(val phase: ConnectionPhase, val label: String, val detail: String = "") {
    companion object {
        fun from(computer: Computer): ConnectionStatus = ConnectionStatus(when (computer.state) {
            "已停止", "已暂停" -> ConnectionPhase.PAUSED
            "已连接" -> ConnectionPhase.LAN_CONNECTED
            "中转已连接" -> ConnectionPhase.RELAY_CONNECTED
            "需要重新授权", "需要重新配对" -> ConnectionPhase.NEEDS_AUTHORIZATION
            "需要核验" -> ConnectionPhase.NEEDS_VERIFICATION
            "正在连接", "正在连接中转", "正在检查局域网" -> ConnectionPhase.CONNECTING
            else -> ConnectionPhase.RETRYING
        }, computer.state, computer.error)
    }
}
class IdentityMismatch : IOException("电脑身份与已保存的授权不一致")
interface ConnectionSession {
    fun hasLan(): Boolean
    fun lanRouteKey(): String = hasLan().toString()
    suspend fun hasRelay(): Boolean
    suspend fun probe()
    suspend fun receive(transport: ActiveTransport, connected: () -> Unit)
    suspend fun waiting(detail: String)
    suspend fun waitForNetworkChange(timeoutMs: Long) { delay(timeoutMs) }
}

/** One owner per computer; a read-only probe can overlap a receiver, never another event stream. */
class ConnectionCoordinator(
    private val probeTimeoutMs: Long = 3_000,
    private val checkIntervalMs: Long = 30_000,
    private val confirmationDelayMs: Long = 2_000,
    private val minimumRelayMs: Long = 30_000,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private suspend fun reachable(session: ConnectionSession): Boolean {
        if (!session.hasLan()) return false
        return try { withTimeoutOrNull(probeTimeoutMs) { session.probe(); true } ?: false }
        catch (e: CancellationException) { throw e }
        catch (e: AuthorizationRequired) { throw e }
        catch (e: SSLException) { throw e }
        catch (e: IdentityMismatch) { throw e }
        catch (_: IOException) { false }
    }
    suspend fun run(mode: ConnectionMode, session: ConnectionSession) {
        var failures = 0
        var preferRelayOnce = false
        while (currentCoroutineContext().isActive) {
            val transport = when (mode) {
                ConnectionMode.LAN -> ActiveTransport.LAN
                ConnectionMode.RELAY -> ActiveTransport.RELAY
                ConnectionMode.AUTO -> if (preferRelayOnce && session.hasRelay()) ActiveTransport.RELAY else if (reachable(session)) ActiveTransport.LAN else if (session.hasRelay()) ActiveTransport.RELAY else null
            }
            preferRelayOnce = false
            if (transport == null || (transport == ActiveTransport.LAN && !session.hasLan()) ||
                (transport == ActiveTransport.RELAY && !session.hasRelay())) {
                session.waiting(if (mode == ConnectionMode.RELAY) "尚无跨网络凭据，请在电脑启用后重新配对" else "等待可用局域网；可启用跨网络作为备用")
                session.waitForNetworkChange(checkIntervalMs)
                continue
            }
            try {
                if (mode == ConnectionMode.AUTO && transport == ActiveTransport.RELAY) {
                    coroutineScope {
                        var connectedAt: Long? = null
                        val receiver = launch { session.receive(transport) { failures = 0; connectedAt = now() } }
                        try {
                            while (receiver.isActive) {
                                session.waitForNetworkChange(checkIntervalMs)
                                val started = connectedAt ?: continue
                                if (now() - started >= minimumRelayMs && reachable(session)) {
                                    delay(confirmationDelayMs)
                                    if (reachable(session)) break
                                }
                            }
                        } finally { receiver.cancelAndJoin() }
                    }
                } else if (transport == ActiveTransport.LAN) {
                    coroutineScope {
                        val route = session.lanRouteKey()
                        val receiver = launch { session.receive(transport) { failures = 0 } }
                        try {
                            while (receiver.isActive) {
                                session.waitForNetworkChange(checkIntervalMs)
                                if (session.lanRouteKey() != route || !session.hasLan()) break
                            }
                        } finally { receiver.cancelAndJoin() }
                    }
                } else session.receive(transport) { failures = 0 }
            } catch (e: CancellationException) { throw e }
            catch (e: AuthorizationRequired) { throw e }
            catch (e: SSLException) { throw e }
            catch (e: IdentityMismatch) { throw e }
            catch (e: Exception) {
                session.waiting(if (transport == ActiveTransport.RELAY) "中转暂时不可用，正在重试" else "直连已断开，正在选择可用通道")
                // Automatic LAN failure falls back immediately after a fresh trusted check.
                preferRelayOnce = mode == ConnectionMode.AUTO && transport == ActiveTransport.LAN && session.hasRelay()
                if (!preferRelayOnce)
                    delay((1_000L shl minOf(failures++, 6)) + kotlin.random.Random.nextLong(500))
            }
        }
    }
}

object ReceiveCommands {
    const val RESUME = "RESUME"
    const val PAUSE = "STOP"
    const val NEW_SESSION = "BEGIN"
    fun startAction(newSession: Boolean): String = if (newSession) NEW_SESSION else RESUME
}
