package com.notifforward.app

import org.junit.Assert.*
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.util.Date

class ProtocolTest {
    private val server = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private fun qr() = PairPayload("win2mobile-pair", 1, "https://192.168.1.2:47721", "A".repeat(64), "z".repeat(43), server, "电脑")
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid input was accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun qrRoundTripsEveryRequiredField() {
        val text = """{"schema":"win2mobile-pair","protocolVersion":1,"baseUrl":"https://192.168.1.2:47721","certificateSha256":"${"A".repeat(64)}","pairingCode":"${"z".repeat(43)}","serverId":"$server","serverName":"电脑","futureField":true}"""
        assertEquals(qr(), PairPayload.parse(text))
    }
    @Test fun qrRejectsUntrustedUrlsAndUnsupportedProtocol() {
        listOf("http://192.168.1.2:47721", "https://user:secret@192.168.1.2", "https://192.168.1.2?token=abc", "https://192.168.1.2/#token", "https://192.168.1.2/path", "https://192.168.1.2:99999").forEach {
            rejects { qr().copy(baseUrl = it).validate() }
        }
        rejects { qr().copy(protocolVersion = 2).validate() }
        rejects { qr().copy(certificateSha256 = "a".repeat(64)).validate() }
        rejects { qr().copy(pairingCode = " ".repeat(43)).validate() }
        rejects { qr().copy(serverId = "not-a-uuid").validate() }
    }
    @Test fun qrRequiresAllFields() {
        try { PairPayload.parse("""{"schema":"win2mobile-pair","protocolVersion":1}"""); fail("Missing fields accepted") }
        catch (_: kotlinx.serialization.SerializationException) { }
    }
    @Test fun pinHashesDerBytesWithKnownSha256() {
        assertEquals("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", CertificatePin.fingerprint("abc".toByteArray()))
    }
    private fun certificate(): X509Certificate = javaClass.getResourceAsStream("/pin-test.der").use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
    @Test fun pinUsesWholeCertificateRatherThanPublicKey() {
        val certificate = certificate()
        val validDate = Date(certificate.notBefore.time + 1000)
        CertificatePin.verify(certificate, CertificatePin.fingerprint(certificate.encoded), validDate)
        rejects { CertificatePin.verify(certificate, CertificatePin.fingerprint(certificate.publicKey.encoded), validDate) }
        rejects { CertificatePin.verify(certificate, "0".repeat(64), validDate) }
    }
    @Test fun pinRejectsExpiredAndNotYetValidCertificate() {
        val certificate = certificate()
        val pin = CertificatePin.fingerprint(certificate.encoded)
        try { CertificatePin.verify(certificate, pin, Date(certificate.notAfter.time + 1000)); fail("Expired certificate accepted") }
        catch (_: CertificateExpiredException) { }
        try { CertificatePin.verify(certificate, pin, Date(certificate.notBefore.time - 1000)); fail("Future certificate accepted") }
        catch (_: CertificateNotYetValidException) { }
    }
    @Test fun cursorAdvancesOnlyConsecutiveEventsAndNeverMovesBackward() {
        assertEquals(1L, CursorPolicy.advance(0, 1))
        assertEquals(4L, CursorPolicy.advance(4, 3))
        assertEquals(4L, CursorPolicy.advance(4, 4))
        assertEquals(5L, CursorPolicy.advance(4, 5))
        rejects { CursorPolicy.advance(4, 6) }
        rejects { CursorPolicy.advance(-1, 1) }
        rejects { CursorPolicy.advance(0, 0) }
        rejects { CursorPolicy.advance(Long.MAX_VALUE, -1) }
    }
    private fun event(sequence: Long = 5, source: String = server) = BridgeEvent(sequence, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", source, "source", "app", "App", "Title", "Body", "2026-09-30T00:00:00Z")
    @Test fun streamRequiresHelloAndRejectsWrongIdentityOrRegressedWatermark() {
        rejects { StreamState(server, 4).accept(StreamFrame("event", event = event())) }
        rejects { StreamState(server, 4).accept(StreamFrame("heartbeat", serverTime = "2026-09-30T00:00:00Z")) }
        rejects { StreamState(server, 4).accept(StreamFrame("hello", 1, "wrong", 4)) }
        rejects { StreamState(server, 4).accept(StreamFrame("hello", 1, server, 3)) }
        rejects { StreamState(server, 4).accept(StreamFrame("hello", 2, server, 4)) }
    }
    @Test fun streamSeparatesReplayFromNewEventsAndRequiresFreshReconnectHandshake() {
        val state = StreamState(server, 4)
        state.accept(StreamFrame("hello", 1, server, 8))
        state.accept(StreamFrame("event", event = event()))
        state.accept(StreamFrame("heartbeat", serverTime = "2026-09-30T00:00:00Z"))
        assertTrue(state.isReplay(8))
        assertFalse(state.isReplay(9))
        rejects { state.accept(StreamFrame("hello", 1, server, 8)) }
        rejects { state.accept(StreamFrame("event", event = event(source = "wrong"))) }
        rejects { StreamState(server, 8).accept(StreamFrame("event", event = event(9))) }
    }
}
