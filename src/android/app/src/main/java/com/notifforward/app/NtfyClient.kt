package com.notifforward.app

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

object NtfyFailure {
    fun describe(error: Throwable, status: Int? = null): String = when {
        status == 429 -> "中转限流，请稍后重试"
        status != null -> "中转拒绝连接（HTTP $status）"
        error is javax.net.ssl.SSLException -> "中转 TLS 握手或证书验证失败，请检查网络与手机时间"
        error is java.net.UnknownHostException -> "无法解析中转地址，请检查 DNS 或手机网络"
        error is java.net.SocketTimeoutException -> "中转连接超时，请检查手机网络或代理"
        else -> "无法连接中转，请检查手机网络或代理"
    }
}

class NtfyClient(private val credentials: NtfyCredentials, client: OkHttpClient? = null) : AutoCloseable {
    // Default network, public CA trust and hostname validation. Never reuse the LAN trust manager.
    private val http = client ?: OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(70, TimeUnit.SECONDS).pingInterval(25, TimeUnit.SECONDS).build()
    init { credentials.validate() }
    suspend fun publish(topic: String, text: String): Unit = suspendCancellableCoroutine { continuation ->
        require(topic.matches(Regex("w2m_[a-f0-9]{60}")) && text.toByteArray(Charsets.UTF_8).size <= 4096)
        val call = http.newCall(Request.Builder().url(credentials.serverUrl.trimEnd('/') + "/" + topic)
            .header("Firebase", "no").post(text.toRequestBody("text/plain; charset=utf-8".toMediaType())).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException(NtfyFailure.describe(e), e)) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (continuation.isActive) {
                        if (it.isSuccessful) continuation.resume(Unit) else continuation.resumeWithException(IOException("远程配对发布失败（HTTP ${it.code}）"))
                    }
                }
            }
        })
    }
    suspend fun receive(lastMessageId: String, connected: suspend () -> Unit, accept: suspend (NtfyMessage) -> Unit, liveOnly: Boolean = false): Unit = coroutineScope {
        require(lastMessageId.isEmpty() || lastMessageId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val frames = Channel<String>(256)
        val since = lastMessageId.ifEmpty { "all" }
        val url = credentials.serverUrl.trimEnd('/').replaceFirst("https://", "wss://") + "/${credentials.topic}/ws" + if (liveOnly) "" else "?since=$since"
        val socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 16384 || frames.trySend(text).isFailure) {
                    frames.close(IOException("中转流量过多，正在恢复")); webSocket.cancel()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // A relay HTTP rejection is not proof that the PC revoked this device.
                frames.close(IOException(NtfyFailure.describe(t, response?.code), t))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); frames.close(IOException("中转连接已关闭")) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { frames.close(IOException("中转连接已关闭")) }
        })
        try {
            for (text in frames) {
                if (!currentCoroutineContext().isActive) break
                // Malformed/foreign broker frames are discarded without exposing content in diagnostics.
                val message = runCatching { wireJson.decodeFromString<NtfyMessage>(text) }.getOrNull() ?: continue
                if (message.topic != credentials.topic || !message.id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) continue
                when (message.event) { "open" -> connected(); "message" -> accept(message); "keepalive" -> Unit }
            }
            throw IOException("中转连接结束")
        } finally { socket.cancel(); frames.cancel() }
    }
    override fun close() { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}
