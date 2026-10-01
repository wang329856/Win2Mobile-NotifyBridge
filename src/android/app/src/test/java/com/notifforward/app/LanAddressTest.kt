package com.notifforward.app

import org.junit.Assert.*
import org.junit.Test

class LanAddressTest {
    @Test fun acceptsChangedAddressWithoutBindingToRelayHost() {
        assertEquals("https://192.168.31.25:47721", LanAddress.normalize("192.168.31.25"))
        assertEquals("https://192.168.31.25:47722", LanAddress.normalize(" https://192.168.31.25:47722/ "))
        assertEquals("https://[fd00::25]:47721", LanAddress.normalize("https://[fd00::25]"))
    }
    @Test fun rejectsLoopbackAndCredentialBearingOrRedirectAddresses() {
        for (value in listOf("", "127.0.0.1", "https://localhost", "https://[::1]", "0.0.0.0", "http://192.168.1.2", "https://user:secret@192.168.1.2", "https://192.168.1.2/path", "https://192.168.1.2?q=1", "https://192.168.1.2#fragment")) {
            try { LanAddress.normalize(value); fail("Unsafe LAN target accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
