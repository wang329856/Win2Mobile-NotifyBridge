package com.notifforward.app

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable data class RemotePairPayload(val schema: String, val protocolVersion: Int, val serverUrl: String,
    val requestTopic: String, val responseTopic: String, val pairingKey: String, val serverPublicKey: String,
    val serverId: String, val serverName: String, val baseUrl: String, val certificateSha256: String, val expiresAt: String) {
    fun metadata() = PairPayload("win2mobile-pair", 1, baseUrl, certificateSha256, pairingKey, serverId, serverName).validate()
    fun validate(): RemotePairPayload {
        require(schema == "win2mobile-ntfy-pair" && protocolVersion == 1) { "不支持的远程配对协议" }
        NtfyCredentials(serverUrl, requestTopic, pairingKey, 0).validate()
        NtfyCredentials(serverUrl, responseTopic, pairingKey, 0).validate()
        require(requestTopic != responseTopic && Base64.getDecoder().decode(serverPublicKey).size in 80..256) { "远程配对信息无效" }
        metadata()
        require(Instant.parse(expiresAt).isAfter(Instant.now())) { "远程二维码已过期，请在电脑重新生成" }
        return this
    }
}
@Serializable data class RemotePairRequest(val requestId: String, val deviceName: String, val clientPublicKey: String)
@Serializable data class PairCipher(val requestId: String, val nonce: String, val data: String)

object RemotePairCrypto {
    fun seal(clear: String, key: ByteArray, serverId: String, topic: String, direction: String, id: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); updateAAD(aad(serverId, topic, direction, id))
        }
        return wireJson.encodeToString(PairCipher(id, Base64.getEncoder().encodeToString(cipher.iv),
            Base64.getEncoder().encodeToString(cipher.doFinal(clear.toByteArray(Charsets.UTF_8)))))
    }
    fun open(wire: String, key: ByteArray, serverId: String, topic: String, direction: String, id: String): String {
        require(wire.toByteArray(Charsets.UTF_8).size <= 4096)
        val envelope = wireJson.decodeFromString<PairCipher>(wire)
        require(envelope.requestId == id)
        val nonce = Base64.getDecoder().decode(envelope.nonce); val data = Base64.getDecoder().decode(envelope.data)
        require(nonce.size == 12 && data.size in 17..3000)
        val clear = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad(serverId, topic, direction, id)); doFinal(data)
        }
        return try { String(clear, Charsets.UTF_8) } finally { clear.fill(0) }
    }
    fun responseKey(privateKey: java.security.PrivateKey, serverPublicKey: String): ByteArray {
        val public = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(serverPublicKey)))
        val shared = KeyAgreement.getInstance("ECDH").run { init(privateKey); doPhase(public, true); generateSecret() }
        return try { MessageDigest.getInstance("SHA-256").digest(shared) } finally { shared.fill(0) }
    }
    fun verificationCode(key: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(key)
        val number = hash.take(4).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
        return (number % 1_000_000).toString().padStart(6, '0')
    }
    private fun aad(server: String, topic: String, direction: String, id: String) =
        "win2mobile-ntfy-pair-v1|$server|$topic|$direction|$id".toByteArray(Charsets.UTF_8)
}

class RemotePairClient(private val payload: RemotePairPayload, client: NtfyClient? = null) : AutoCloseable {
    private val relay = client ?: NtfyClient(NtfyCredentials(payload.serverUrl, payload.responseTopic, payload.pairingKey, 0))
    suspend fun pair(deviceName: String, waiting: (String) -> Unit): PairResult {
        payload.validate()
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val responseKey = RemotePairCrypto.responseKey(keys.private, payload.serverPublicKey)
        val pairingKey = Base64.getDecoder().decode(payload.pairingKey)
        val id = UUID.randomUUID().toString()
        val request = wireJson.encodeToString(RemotePairRequest(id, deviceName.take(80), Base64.getEncoder().encodeToString(keys.public.encoded)))
        val wire = RemotePairCrypto.seal(request, pairingKey, payload.serverId, payload.requestTopic, "request", id)
        val verification = "远程校验码 ${RemotePairCrypto.verificationCode(responseKey)}"
        fun report(status: String) = waiting("$verification：$status")
        report("正在连接中转，尚未发送配对请求")
        try {
            return withTimeout(120_000) {
                coroutineScope {
                    val responses = Channel<PairResult>(1)
                    val subscriber = launch {
                        var attempts = 0
                        while (isActive) {
                            try { relay.receive("", connected = {
                                report("已连接中转，正在发送配对请求")
                                relay.publish(payload.requestTopic, wire)
                                report("请求已发送，请在电脑核对相同六位码并批准")
                            }, accept = { message ->
                            val response = runCatching {
                                val clear = RemotePairCrypto.open(message.message ?: error("无消息"), responseKey,
                                    payload.serverId, payload.responseTopic, "response", id)
                                wireJson.decodeFromString<PairResult>(clear)
                            }.getOrNull()
                            if (response != null && response.serverId == payload.serverId) responses.trySend(response)
                            })
                            } catch (e: CancellationException) { throw e
                            } catch (e: java.io.IOException) {
                                val retrySeconds = 1L shl minOf(attempts++, 3)
                                report("${e.message ?: "中转连接中断"}；${retrySeconds} 秒后重试")
                                delay(retrySeconds * 1000)
                            }
                        }
                    }
                    try {
                        val result = responses.receive()
                        when (result.status) {
                            "approved" -> { require(!result.deviceId.isNullOrBlank() && !result.accessToken.isNullOrBlank() && result.ntfy != null) { "电脑跨网络授权未就绪，请重新配对" }; result.ntfy.validate(); result }
                            "denied" -> error("电脑拒绝了远程配对请求")
                            "expired" -> error("远程配对已过期，请重新生成二维码")
                            else -> error("远程配对状态无效")
                        }
                    } finally { subscriber.cancelAndJoin(); responses.cancel() }
                }
            }
        } finally { responseKey.fill(0); pairingKey.fill(0) }
    }
    override fun close() { relay.close() }
}
