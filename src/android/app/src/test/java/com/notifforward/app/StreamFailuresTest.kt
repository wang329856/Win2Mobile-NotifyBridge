package com.notifforward.app

import org.junit.Assert.*
import org.junit.Test

class StreamFailuresTest {
    @Test fun hostPolicyCloseRetriesRatherThanPermanentlyDisablingComputer() {
        assertFalse(StreamFailures.closed(1008) is AuthorizationRequired)
        assertTrue(StreamFailures.closed(1008) is java.io.IOException)
        assertFalse(StreamFailures.closed(1000) is AuthorizationRequired)
    }
    @Test fun authenticatedHandshakeRejectionRequiresNewAuthorization() {
        val failure = java.io.IOException("Socket handshake failed")
        assertTrue(StreamFailures.handshake(401, failure) is AuthorizationRequired)
        assertTrue(StreamFailures.handshake(403, failure) is AuthorizationRequired)
        assertSame(failure, StreamFailures.handshake(503, failure))
        assertSame(failure, StreamFailures.handshake(null, failure))
    }
}
