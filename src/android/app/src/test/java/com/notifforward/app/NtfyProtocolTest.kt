package com.notifforward.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.Base64
import javax.crypto.AEADBadTagException

class NtfyProtocolTest {
    @Serializable private data class Fixture(val serverId: String, val deviceId: String, val credentials: NtfyCredentials,
        val bridgeEvent: BridgeEvent, val fragments: List<String>)
    private fun fixture(): Fixture = javaClass.getResourceAsStream("/ntfy-v1.json")!!.bufferedReader().use {
        wireJson.decodeFromString<Fixture>(it.readText())
    }
    private fun assembler(f: Fixture) = NtfyAssembler(f.serverId, f.deviceId, f.credentials)
    @Test fun rejectsLegacyTopicExceedingPublicServerLimit() {
        val f = fixture()
        try { f.credentials.copy(topic = "w2m_" + "a".repeat(64)).validate(); fail("Oversized topic accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun decryptsActualCSharpVectorWithUnicodeAcrossFragments() {
        val f = fixture(); val a = assembler(f)
        assertTrue(f.fragments.size > 1)
        var event: BridgeEvent? = null
        f.fragments.forEach { event = a.accept(it) }
        assertEquals(f.bridgeEvent, event)
    }
    @Test fun supportsOutOfOrderAndDuplicateFragments() {
        val f = fixture(); val a = assembler(f)
        assertNull(a.accept(f.fragments.last()))
        assertNull(a.accept(f.fragments.last()))
        var event: BridgeEvent? = null
        f.fragments.dropLast(1).reversed().forEach { event = a.accept(it) }
        assertEquals(f.bridgeEvent, event)
    }
    @Test fun rejectsModifiedCiphertextThenAcceptsValidMessage() {
        val f = fixture(); val a = assembler(f)
        val first = wireJson.decodeFromString<NtfyFragment>(f.fragments.first())
        val bytes = Base64.getDecoder().decode(first.data); bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val tampered = wireJson.encodeToString(first.copy(data = Base64.getEncoder().encodeToString(bytes)))
        try { a.accept(tampered); fail("Ciphertext modification accepted") } catch (_: AEADBadTagException) { }
        var event: BridgeEvent? = null; f.fragments.forEach { event = a.accept(it) }
        assertEquals(f.bridgeEvent, event)
    }
    @Test fun rejectsWrongPhoneComputerTopicAndKey() {
        val f = fixture()
        val wrong = listOf(NtfyAssembler(f.serverId, "dddddddd-dddd-dddd-dddd-dddddddddddd", f.credentials),
            NtfyAssembler("dddddddd-dddd-dddd-dddd-dddddddddddd", f.deviceId, f.credentials),
            NtfyAssembler(f.serverId, f.deviceId, f.credentials.copy(topic = "w2m_" + "b".repeat(60))),
            NtfyAssembler(f.serverId, f.deviceId, f.credentials.copy(key = Base64.getEncoder().encodeToString(ByteArray(32)))))
        wrong.forEach { try { it.accept(f.fragments[0]); fail("Foreign routing/key accepted") } catch (_: AEADBadTagException) { } }
    }
    @Test fun authenticatesFragmentIndexCountAndEventId() {
        val f = fixture(); val first = wireJson.decodeFromString<NtfyFragment>(f.fragments.first())
        listOf(first.copy(index = 1), first.copy(count = first.count + 1), first.copy(id = "dddddddd-dddd-dddd-dddd-dddddddddddd"))
            .forEach { changed -> try { assembler(f).accept(wireJson.encodeToString(changed)); fail("Modified assembly accepted") } catch (_: AEADBadTagException) { } }
    }
    @Test fun expiresIncompleteAssembliesWithoutSavingPartialNotifications() {
        val f = fixture(); var now = Instant.parse("2026-10-01T00:00:00Z")
        val a = NtfyAssembler(f.serverId, f.deviceId, f.credentials) { now }
        assertNull(a.accept(f.fragments.first())); now = now.plusSeconds(43201)
        f.fragments.drop(1).forEach { assertNull(a.accept(it)) }
        assertEquals(f.bridgeEvent, a.accept(f.fragments.first()))
    }
    @Test fun rejectsInsecureOrCredentialBearingRelayEndpoints() {
        val f = fixture()
        listOf("http://ntfy.sh", "https://name:secret@ntfy.sh", "https://ntfy.sh/topic", "https://ntfy.sh?token=secret", "https://ntfy.sh#secret").forEach {
            try { f.credentials.copy(serverUrl = it).validate(); fail("Unsafe endpoint accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun rejectsOversizedAndInvalidFragmentsBeforeAssembly() {
        val f = fixture(); val a = assembler(f); val part = wireJson.decodeFromString<NtfyFragment>(f.fragments[0])
        listOf("x".repeat(4097), wireJson.encodeToString(part.copy(count = 129)), wireJson.encodeToString(part.copy(index = -1)))
            .forEach { try { a.accept(it); fail("Invalid fragment accepted") } catch (_: IllegalArgumentException) { } }
    }
}
