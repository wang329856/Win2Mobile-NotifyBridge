package com.notifforward.app

import android.content.Context
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException

/** Stable boundary for future transports. This version supplies only the LAN implementation. */
interface BridgeTransport : AutoCloseable {
    suspend fun pair(payload: PairPayload, deviceName: String, waiting: (String) -> Unit): PairResult
    fun stream(cursor: Long, token: String, listener: WebSocketListener): WebSocket
    suspend fun ack(sequence: Long, token: String)
    override fun close()
}

interface BridgeTransportFactory {
    fun create(baseUrl: String, certificateSha256: String): BridgeTransport
}

class LanTransportFactory(private val context: Context) : BridgeTransportFactory {
    override fun create(baseUrl: String, certificateSha256: String): BridgeTransport =
        LanClient(baseUrl, certificateSha256, LanNetwork.select(context))
}

object StreamFailures {
    fun handshake(status: Int?, cause: Throwable): Throwable =
        if (status == 401 || status == 403) AuthorizationRequired() else cause
    // A normal host stop can also use policy-violation code 1008. Only authenticated HTTP rejection revokes access.
    fun closed(code: Int): Throwable = IOException("连接已关闭（$code）")
}
