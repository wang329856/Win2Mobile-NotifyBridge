package com.notifforward.app

import kotlinx.serialization.Serializable
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable data class NtfyCredentials(val serverUrl: String, val topic: String, val key: String, val startSequence: Long) {
    fun validate(): NtfyCredentials {
        val uri = URI(serverUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null &&
            uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/") && (uri.port == -1 || uri.port in 1..65535)) { "中转地址无效" }
        require(topic.matches(Regex("w2m_[a-f0-9]{60}")) && Base64.getDecoder().decode(key).size == 32 && startSequence >= 0) { "中转凭据无效" }
        return this
    }
}
@Serializable data class NtfyFragment(val v: Int, val id: String, val index: Int, val count: Int, val nonce: String, val data: String)
@Serializable data class NtfyMessage(val id: String, val event: String, val topic: String, val message: String? = null)

/** Authenticate before allocating assembly entries. Untrusted topic publishers cannot insert plaintext. */
class NtfyAssembler(private val serverId: String, private val deviceId: String, private val credentials: NtfyCredentials,
    private val now: () -> Instant = { Instant.now() }) {
    private data class Assembly(val parts: Array<ByteArray?>, val created: Instant)
    private val pending = linkedMapOf<String, Assembly>()
    init { credentials.validate(); UUID.fromString(serverId); UUID.fromString(deviceId) }
    fun accept(text: String): BridgeEvent? {
        require(text.toByteArray(Charsets.UTF_8).size <= 4096) { "中转分片过大" }
        val part = wireJson.decodeFromString<NtfyFragment>(text)
        require(part.v == 1 && part.count in 1..128 && part.index in 0 until part.count) { "中转协议无效" }
        UUID.fromString(part.id)
        val nonce = Base64.getDecoder().decode(part.nonce)
        val encrypted = Base64.getDecoder().decode(part.data)
        require(nonce.size == 12 && encrypted.size in 17..2416) { "中转分片长度无效" }
        val key = Base64.getDecoder().decode(credentials.key)
        val clear = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD("win2mobile-ntfy-v1|$serverId|$deviceId|${credentials.topic}|${part.id}|${part.index}|${part.count}".toByteArray(Charsets.UTF_8))
                doFinal(encrypted)
            }
        } finally { key.fill(0) }
        val instant = now()
        pending.entries.removeAll { it.value.created.plusSeconds(43200).isBefore(instant) }
        if (part.id !in pending && pending.size >= 8) pending.remove(pending.keys.first())
        val assembly = pending.getOrPut(part.id) { Assembly(arrayOfNulls(part.count), instant) }
        require(assembly.parts.size == part.count) { "分片数量不一致" }
        val previous = assembly.parts[part.index]
        require(previous == null || MessageDigest.isEqual(previous, clear)) { "分片内容冲突" }
        assembly.parts[part.index] = clear
        if (assembly.parts.any { it == null }) return null
        pending.remove(part.id)
        val bytes = assembly.parts.fold(ByteArray(0)) { result, chunk -> result + chunk!! }
        return try {
            val result = wireJson.decodeFromString<BridgeEvent>(String(bytes, Charsets.UTF_8)).validate(serverId)
            require(result.eventId == part.id && result.sequence > credentials.startSequence) { "中转事件标识或序号无效" }
            result
        } finally { bytes.fill(0); assembly.parts.forEach { it?.fill(0) } }
    }
}
