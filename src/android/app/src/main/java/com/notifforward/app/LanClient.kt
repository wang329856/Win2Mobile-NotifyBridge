package com.notifforward.app

import android.net.Network
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.encodeToString
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class AuthorizationRequired : Exception("电脑已撤销授权，请重新扫码配对")
class LanClient(baseUrl: String, fingerprint: String, network: Network? = null) : BridgeTransport {
    private val base = baseUrl.trimEnd('/')
    private val manager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException("不接受客户端证书") }
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty()) throw CertificateException("服务器没有证书")
            try { CertificatePin.verify(chain[0], fingerprint) } catch (e: Exception) { throw CertificateException(e.message, e) }
        }
    }
    private val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), SecureRandom()) }
    val http = OkHttpClient.Builder().apply {
        network?.let { route ->
            socketFactory(route.socketFactory)
            dns(object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> = route.getAllByName(hostname).toList()
            })
        }
    }.sslSocketFactory(tls.socketFactory, manager)
        // The QR code authenticates the entire DER certificate; LAN IP addresses can change.
        .hostnameVerifier { _, session ->
            runCatching { CertificatePin.verify(session.peerCertificates[0] as X509Certificate, fingerprint) }.isSuccess
        }.followRedirects(false).followSslRedirects(false).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    init { require(base.startsWith("https://")) }
    private suspend fun request(path: String, body: String? = null, token: String? = null, secret: String? = null): String = suspendCancellableCoroutine { continuation ->
        val builder = Request.Builder().url(base + path)
        token?.let { builder.header("Authorization", "Bearer $it") }
        secret?.let { builder.header("X-Pairing-Secret", it) }
        body?.let { builder.post(it.toRequestBody("application/json".toMediaType())) }
        val call = http.newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use {
                    if (it.code == 401 || it.code == 403) throw AuthorizationRequired()
                    check(it.isSuccessful) { "电脑返回错误 ${it.code}" }
                    it.body?.string() ?: error("电脑返回空响应")
                } }
                if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
            }
        })
    }
    override suspend fun pair(payload: PairPayload, deviceName: String, waiting: (String) -> Unit): PairResult {
        val pending = wireJson.decodeFromString<PairPending>(request("/v1/pairing/requests", wireJson.encodeToString(PairRequest(payload.pairingCode, deviceName))))
        require(pending.status == "pending") { "配对请求状态无效" }
        val expiry = minOf(Instant.parse(pending.expiresAt), Instant.now().plusSeconds(120))
        waiting("请在电脑上批准此手机的配对请求")
        while (Instant.now().isBefore(expiry)) {
            delay(1000)
            val result = wireJson.decodeFromString<PairResult>(request("/v1/pairing/requests/${pending.requestId}", secret = pending.requestSecret))
            require(result.serverId == payload.serverId) { "电脑身份与二维码不一致" }
            when (result.status) {
                "approved" -> { require(!result.deviceId.isNullOrBlank() && !result.accessToken.isNullOrBlank()); return result }
                "denied" -> error("电脑拒绝了配对请求")
                "expired" -> error("配对已过期，请重新扫描电脑二维码")
                "pending" -> Unit
                else -> error("未知配对状态")
            }
        }
        error("配对已过期，请重新扫描电脑二维码")
    }
    override fun stream(cursor: Long, token: String, listener: WebSocketListener, liveOnly: Boolean): WebSocket = http.newWebSocket(
        Request.Builder().url(base.replaceFirst("https://", "wss://") + "/v1/events/stream?after=$cursor" + if (liveOnly) "&live=true" else "")
            .header("Authorization", "Bearer $token").build(), listener)
    override suspend fun ack(sequence: Long, token: String) {
        val result = wireJson.decodeFromString<AckResponse>(request("/v1/acks", wireJson.encodeToString(AckRequest(sequence)), token))
        require(result.acknowledgedSequence >= sequence) { "电脑未确认保存游标" }
    }
    override fun close() { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}
