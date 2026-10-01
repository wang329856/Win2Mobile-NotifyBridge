package com.notifforward.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID

class RemotePairClientTest {
    @Test fun completesEncryptedRemoteApprovalOverHttpsAndWssWithoutLan() = runBlocking { pairingScenario(false) }
    @Test fun retriesDisconnectedReplyUsingSameEphemeralKeyAndRequestId() = runBlocking { pairingScenario(true) }
    @Test fun reportsRelayRejectionBeforeRetrying() = runBlocking { pairingScenario(false, true) }
    private suspend fun pairingScenario(dropReplyConnection: Boolean, rejectFirstConnection: Boolean = false) {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val phoneTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val serverKeys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val secret = ByteArray(32) { (it + 1).toByte() }
        val serverId = UUID.randomUUID().toString(); val requestTopic = "w2m_" + "a".repeat(60); val responseTopic = "w2m_" + "b".repeat(60)
        val broker = MockWebServer(); broker.useHttps(serverTls.sslSocketFactory(), false); broker.start()
        var socket: WebSocket? = null; var displayedCode = ""; var observedCode = ""
        var firstRequestId: String? = null; var posts = 0
        var subscriptions = 0; val phases = mutableListOf<String>()
        // Use the certified hostname instead of machine-dependent reverse DNS of loopback.
        val credentials = NtfyCredentials(broker.url("/").newBuilder().host("localhost").build().toString().trimEnd('/'), "w2m_" + "c".repeat(60), Base64.getEncoder().encodeToString(ByteArray(32)), 0)
        val expected = PairResult("approved", serverId, "Remote PC", UUID.randomUUID().toString(), "A".repeat(64), credentials)
        broker.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/$responseTopic/ws")) {
                    subscriptions++
                    if (rejectFirstConnection && subscriptions == 1) return MockResponse().setResponseCode(403)
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        socket = webSocket
                        webSocket.send(wireJson.encodeToString(NtfyMessage("Open00000001", "open", responseTopic)))
                    }
                })
                }
                if (request.path == "/$requestTopic" && request.method == "POST") {
                    val wire = request.body.readUtf8(); val envelope = wireJson.decodeFromString<PairCipher>(wire)
                    val clear = RemotePairCrypto.open(wire, secret, serverId, requestTopic, "request", envelope.requestId)
                    val body = wireJson.decodeFromString<RemotePairRequest>(clear)
                    if (firstRequestId == null) firstRequestId = body.requestId else assertEquals(firstRequestId, body.requestId)
                    assertEquals("Test phone", body.deviceName)
                    assertFalse(wire.contains("Test phone"))
                    val key = RemotePairCrypto.responseKey(serverKeys.private, body.clientPublicKey)
                    observedCode = RemotePairCrypto.verificationCode(key)
                    socket!!.send(wireJson.encodeToString(NtfyMessage("Bad000000001", "message", responseTopic, "malformed")))
                    val reply = RemotePairCrypto.seal(wireJson.encodeToString(expected), key, serverId, responseTopic, "response", body.requestId)
                    posts++
                    if (dropReplyConnection && posts == 1) socket!!.close(1012, "restart")
                    else socket!!.send(wireJson.encodeToString(NtfyMessage("Reply0000001", "message", responseTopic, reply)))
                    return MockResponse().setResponseCode(200)
                }
                return MockResponse().setResponseCode(404)
            }
        }
        val payload = RemotePairPayload("win2mobile-ntfy-pair", 1, credentials.serverUrl, requestTopic, responseTopic,
            Base64.getEncoder().encodeToString(secret), Base64.getEncoder().encodeToString(serverKeys.public.encoded), serverId, "Remote PC",
            "https://192.168.1.2:47721", "A".repeat(64), Instant.now().plusSeconds(120).toString())
        val http = OkHttpClient.Builder().sslSocketFactory(phoneTls.sslSocketFactory(), phoneTls.trustManager)
            .followRedirects(false).followSslRedirects(false).build()
        val client = RemotePairClient(payload, NtfyClient(NtfyCredentials(payload.serverUrl, responseTopic, payload.pairingKey, 0), http))
        try {
            val result = withTimeout(5000) { client.pair("Test phone") { displayedCode = it; phases.add(it) } }
            assertEquals(expected, result)
            assertTrue(displayedCode.contains(observedCode) && observedCode.length == 6)
            assertEquals((if (dropReplyConnection) 4 else 2) + (if (rejectFirstConnection) 1 else 0), broker.requestCount)
            assertTrue(phases.first().contains("尚未发送"))
            assertTrue(phases.any { it.contains("请求已发送") })
            if (rejectFirstConnection) assertTrue(phases.any { it.contains("HTTP 403") && it.contains("重试") })
        } finally { client.close(); socket?.close(1000, null); broker.shutdown() }
    }
}
