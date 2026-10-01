package com.notifforward.app

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual HTTPS/WSS and asynchronous OkHttp calls against a loopback TLS server; no Android runtime. */
class LanClientTest {
    private lateinit var server: MockWebServer
    private lateinit var certificate: HeldCertificate
    private val clients = mutableListOf<LanClient>()
    private val identity = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    @Before fun setup() {
        certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server = MockWebServer()
        server.useHttps(tls.sslSocketFactory(), false)
        server.start()
    }
    @After fun teardown() { clients.forEach { it.close() }; server.shutdown() }
    private fun pin() = CertificatePin.fingerprint(certificate.certificate.encoded)
    private fun client(fingerprint: String = pin()) = LanClient(server.url("/").toString(), fingerprint).also { clients += it }
    private fun payload() = PairPayload("win2mobile-pair", 1, server.url("/").toString(), pin(), "p".repeat(43), identity, "Test PC").validate()
    @Test fun approvedPairingUsesSecretHeaderAndAckUsesBearerHeader() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"requestId":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","requestSecret":"secret-test-value","status":"pending","expiresAt":"${Instant.now().plusSeconds(120)}"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"approved","serverId":"$identity","serverName":"Test PC","deviceId":"device-test","accessToken":"token-test-value"}"""))
        server.enqueue(MockResponse().setBody("""{"acknowledgedSequence":4}"""))
        val client = client()
        var waiting = false
        val result = client.pair(payload(), "Test Phone") { waiting = true }
        assertTrue(waiting)
        assertEquals("token-test-value", result.accessToken)
        client.ack(4, result.accessToken!!)
        val pair = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/pairing/requests", pair.path)
        assertEquals("POST", pair.method)
        assertEquals("Test Phone", wireJson.decodeFromString<PairRequest>(pair.body.readUtf8()).deviceName)
        assertNull(pair.getHeader("Authorization"))
        val poll = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("secret-test-value", poll.getHeader("X-Pairing-Secret"))
        assertFalse(poll.path!!.contains("secret-test-value"))
        val ack = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer token-test-value", ack.getHeader("Authorization"))
        assertFalse(ack.path!!.contains("token-test-value"))
        assertEquals(4L, wireJson.decodeFromString<AckRequest>(ack.body.readUtf8()).sequence)
    }
    @Test fun http401And403RequireNewAuthorization() = runBlocking {
        val client = client()
        for (status in listOf(401, 403)) {
            server.enqueue(MockResponse().setResponseCode(status))
            try { client.ack(0, "token"); fail("Rejected authorization accepted") }
            catch (_: AuthorizationRequired) { }
        }
    }
    @Test fun untrustedDerFingerprintRejectsActualTlsHandshake() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"acknowledgedSequence":0}"""))
        try { client("0".repeat(64)).ack(0, "token"); fail("Wrong pin accepted") }
        catch (_: javax.net.ssl.SSLException) { }
    }
    @Test fun changedLanEndpointStillRequiresTheOriginalCertificate() = runBlocking {
        val address = LanAddress.normalize(server.url("/").newBuilder().host("192.0.2.1").build().toString())
        assertTrue(address.contains(":${server.port}"))
        // Route the changed public-facing LAN address to this local broker; DER pin remains authoritative.
        server.enqueue(MockResponse().setBody("""{"acknowledgedSequence":0}"""))
        val client = LanClient(address, pin()).also { clients += it }
        val routed = client.http.newBuilder().dns(object : Dns {
            override fun lookup(hostname: String) = listOf(java.net.InetAddress.getByName("127.0.0.1"))
        }).build()
        // Use a hostname so custom DNS routing participates; the certificate lacks this hostname.
        val result = routed.newCall(Request.Builder().url("https://changed-pc.test:${server.port}/v1/acks")
            .post("{}".toRequestBody()).build()).execute()
        result.use { assertEquals(200, it.code) }
        routed.connectionPool.evictAll()
    }
    @Test fun cancelPairingImmediatelyReleasesItsOutstandingHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = client()
        val pairing = launch(Dispatchers.IO) { client.pair(payload(), "Test Phone") { } }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
        withTimeout(2000) {
            pairing.cancelAndJoin()
            while (client.http.dispatcher.runningCallsCount() != 0) delay(10)
        }
        assertTrue(pairing.isCancelled)
    }
    @Test fun streamUsesAuthenticatedWssAndCursorWithoutUrlToken() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"kind":"hello","protocolVersion":1,"serverId":"$identity","highWatermark":4}""")
            }
        }))
        val received = CountDownLatch(1)
        var failure: Throwable? = null
        val socket = client().stream(4, "token-test-value", object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val state = StreamState(identity, 4)
                state.accept(wireJson.decodeFromString<StreamFrame>(text))
                received.countDown()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failure = t; received.countDown() }
        })
        try {
            assertTrue(received.await(5, TimeUnit.SECONDS))
            assertNull(failure)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/events/stream?after=4", request.path)
            assertEquals("Bearer token-test-value", request.getHeader("Authorization"))
            assertFalse(request.path!!.contains("token-test-value"))
        } finally { socket.cancel() }
    }
    @Test fun ackMustConfirmAtLeastTheRequestedSavedCursor() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"acknowledgedSequence":3}"""))
        try { client().ack(4, "token"); fail("Regressed acknowledgement accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
