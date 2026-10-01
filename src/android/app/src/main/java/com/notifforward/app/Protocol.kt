package com.notifforward.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.UUID

val wireJson = Json { ignoreUnknownKeys = true }

@Serializable
data class PairPayload(val schema: String, val protocolVersion: Int, val baseUrl: String,
    val certificateSha256: String, val pairingCode: String, val serverId: String, val serverName: String) {
    fun validate(): PairPayload {
        require(schema == "win2mobile-pair" && protocolVersion == 1) { "不支持的配对协议" }
        val uri = URI(baseUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535) && (uri.path.isNullOrEmpty() || uri.path == "/")) { "配对地址必须是 HTTPS 服务器地址" }
        require(certificateSha256.matches(Regex("[0-9A-F]{64}"))) { "证书指纹格式错误" }
        require(pairingCode.length in 32..256 && pairingCode.isNotBlank() && serverName.isNotBlank()) { "配对信息不完整" }
        UUID.fromString(serverId)
        return this
    }
    companion object { fun parse(text: String) = wireJson.decodeFromString<PairPayload>(text.trim()).validate() }
}

@Serializable data class PairRequest(val pairingCode: String, val deviceName: String)
@Serializable data class PairPending(val requestId: String, val requestSecret: String, val status: String, val expiresAt: String)
@Serializable data class PairResult(val status: String, val serverId: String, val serverName: String,
    val deviceId: String? = null, val accessToken: String? = null)
@Serializable data class BridgeEvent(val sequence: Long, val eventId: String, val sourceDeviceId: String,
    val sourceNotificationId: String, val appId: String, val appName: String, val title: String,
    val body: String, val occurredAt: String) {
    fun validate(serverId: String): BridgeEvent {
        require(sequence > 0 && sourceDeviceId == serverId) { "事件来源或序号无效" }
        UUID.fromString(eventId); Instant.parse(occurredAt)
        require(appId.isNotBlank()) { "应用标识无效" }
        return this
    }
}
@Serializable data class StreamFrame(val kind: String, val protocolVersion: Int? = null, val serverId: String? = null,
    val highWatermark: Long? = null, val event: BridgeEvent? = null, val serverTime: String? = null)
@Serializable data class AckRequest(val sequence: Long)
@Serializable data class AckResponse(val acknowledgedSequence: Long)

object CertificatePin {
    fun fingerprint(der: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02X".format(it.toInt() and 255) }
    fun verify(certificate: X509Certificate, expected: String, now: java.util.Date = java.util.Date()) {
        certificate.checkValidity(now)
        require(MessageDigest.isEqual(fingerprint(certificate.encoded).toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))) { "电脑证书发生变化，请重新扫码配对" }
    }
}

object CursorPolicy {
    fun advance(current: Long, sequence: Long): Long {
        require(current >= 0 && sequence > 0)
        require(sequence <= current || sequence == current + 1) { "事件序号不连续，请重新同步" }
        return maxOf(current, sequence)
    }
}

/** Each replacement socket performs a fresh hello before accepting events or heartbeats. */
class StreamState(private val serverId: String, private val initialCursor: Long) {
    var watermark: Long? = null
        private set
    fun accept(frame: StreamFrame) {
        when (frame.kind) {
            "hello" -> {
                require(watermark == null && frame.protocolVersion == 1 && frame.serverId == serverId &&
                    (frame.highWatermark ?: -1) >= initialCursor) { "电脑协议或身份不一致" }
                watermark = frame.highWatermark
            }
            "event" -> { require(watermark != null) { "缺少服务器握手" }; (frame.event ?: error("事件为空")).validate(serverId) }
            "heartbeat" -> { require(watermark != null) { "缺少服务器握手" }; Instant.parse(frame.serverTime) }
            else -> error("未知消息类型")
        }
    }
    fun isReplay(sequence: Long): Boolean = sequence <= (watermark ?: error("缺少服务器握手"))
}

/** Persistence precedes display; transport acknowledgement must never suppress a saved live alert. */
object EventDelivery {
    suspend fun deliver(isReplay: Boolean, save: suspend () -> Boolean, display: () -> Unit, acknowledge: suspend () -> Unit) {
        val added = save()
        if (added && !isReplay) runCatching { display() }
        acknowledge()
    }
}
