package com.notifforward.app

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class NtfySessionTest {
    @Test fun firstSessionSkipsCachedMessagesButReconnectResumesItsMessageId() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val phoneTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val broker = MockWebServer()
        broker.useHttps(serverTls.sslSocketFactory(), false); broker.start()
        val topic = "w2m_" + "a".repeat(60)
        val client = NtfyClient(NtfyCredentials(broker.url("/").newBuilder().host("localhost").build().toString().trimEnd('/'), topic, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 0),
            OkHttpClient.Builder().sslSocketFactory(phoneTls.sslSocketFactory(), phoneTls.trustManager).build())
        try {
            for ((live, previous, expected) in listOf(Triple(true, "OldMessage", "/$topic/ws"),
                Triple(false, "SessionMessage", "/$topic/ws?since=SessionMessage"))) {
                val connected = CompletableDeferred<Unit>()
                broker.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"id":"Open1","event":"open","topic":"$topic"}""")
                    }
                }))
                val receiver = launch { client.receive(previous, { connected.complete(Unit) }, {}, liveOnly = live) }
                try { withTimeout(5000) { connected.await() }; assertEquals(expected, broker.takeRequest(5, TimeUnit.SECONDS)!!.path) }
                finally { receiver.cancelAndJoin() }
            }
        } finally { client.close(); broker.shutdown() }
    }
}
