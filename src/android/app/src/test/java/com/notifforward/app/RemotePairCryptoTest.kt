package com.notifforward.app

import kotlinx.serialization.Serializable
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.AEADBadTagException

class RemotePairCryptoTest {
    @Serializable private data class Fixture(val serverId: String, val requestId: String, val topic: String, val pairingKey: String,
        val request: String, val serverPublicKey: String, val clientPrivateKey: String, val responseKey: String,
        val response: String, val verificationCode: String)
    private fun fixture(): Fixture = javaClass.getResourceAsStream("/ntfy-pair-v1.json")!!.bufferedReader().use {
        wireJson.decodeFromString<Fixture>(it.readText())
    }
    @Test fun derivesCSharpEcdhKeyAndSameSixDigitVerificationCode() {
        val f = fixture()
        val private = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(f.clientPrivateKey)))
        val derived = RemotePairCrypto.responseKey(private, f.serverPublicKey)
        assertArrayEquals(Base64.getDecoder().decode(f.responseKey), derived)
        assertEquals(f.verificationCode, RemotePairCrypto.verificationCode(derived))
        assertEquals("{\"status\":\"approved\"}", RemotePairCrypto.open(f.response, derived, f.serverId, f.topic, "response", f.requestId))
    }
    @Test fun decryptsCSharpRequestButQrSecretCannotDecryptApprovedReply() {
        val f = fixture(); val key = Base64.getDecoder().decode(f.pairingKey)
        assertEquals("{\"deviceName\":\"Synthetic\"}", RemotePairCrypto.open(f.request, key, f.serverId, f.topic, "request", f.requestId))
        try { RemotePairCrypto.open(f.response, key, f.serverId, f.topic, "response", f.requestId); fail("QR secret read another phone's credentials") } catch (_: AEADBadTagException) { }
    }
    @Test fun bindsRepliesToRequestIdAndDirection() {
        val f = fixture(); val key = Base64.getDecoder().decode(f.responseKey)
        try { RemotePairCrypto.open(f.response, key, f.serverId, f.topic, "request", f.requestId); fail("Wrong direction accepted") } catch (_: AEADBadTagException) { }
        try { RemotePairCrypto.open(f.response, key, f.serverId, f.topic, "response", "dddddddd-dddd-dddd-dddd-dddddddddddd"); fail("Wrong request accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun kotlinPairEnvelopeRoundTripsAndRejectsTampering() {
        val f = fixture(); val key = Base64.getDecoder().decode(f.responseKey)
        val wire = RemotePairCrypto.seal("私密凭据", key, f.serverId, f.topic, "response", f.requestId)
        assertEquals("私密凭据", RemotePairCrypto.open(wire, key, f.serverId, f.topic, "response", f.requestId))
        try { RemotePairCrypto.open(wire, key, "dddddddd-dddd-dddd-dddd-dddddddddddd", f.topic, "response", f.requestId); fail("Foreign server accepted") } catch (_: AEADBadTagException) { }
    }
}
