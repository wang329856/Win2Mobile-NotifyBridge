package com.notifforward.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLException

class ConnectionCoordinatorTest {
    private class Session(var lan: Boolean = true, var relay: Boolean = true) : ConnectionSession {
        val routes = mutableListOf<ActiveTransport>()
        var probes = 0; var active = 0; var maximum = 0; var waits = 0
        var probeAction: suspend () -> Unit = {}
        var receiver: suspend (ActiveTransport) -> Unit = { awaitCancellation() }
        val first = CompletableDeferred<ActiveTransport>()
        override fun hasLan() = lan
        override suspend fun hasRelay() = relay
        override suspend fun probe() { probes++; probeAction() }
        override suspend fun receive(transport: ActiveTransport, connected: () -> Unit) {
            active++; maximum = maxOf(maximum, active); routes += transport; first.complete(transport); connected()
            try { receiver(transport) } finally { active-- }
        }
        override suspend fun waiting(detail: String) { waits++ }
    }
    private fun coordinator() = ConnectionCoordinator(probeTimeoutMs = 25, checkIntervalMs = 15, confirmationDelayMs = 5, minimumRelayMs = 15)
    @Test fun automaticUsesLanAndCancellationReleasesTheOnlyReceiver() = runBlocking {
        val session = Session(); val job = launch { coordinator().run(ConnectionMode.AUTO, session) }
        assertEquals(ActiveTransport.LAN, withTimeout(1000) { session.first.await() })
        job.cancelAndJoin(); assertEquals(1, session.maximum); assertEquals(0, session.active)
    }
    @Test fun missingLanOrTimedOutProbeUsesExistingRelay() = runBlocking {
        for (hasLan in listOf(false, true)) {
            val session = Session(lan = hasLan); session.probeAction = { delay(1000) }
            val job = launch { coordinator().run(ConnectionMode.AUTO, session) }
            assertEquals(ActiveTransport.RELAY, withTimeout(1000) { session.first.await() })
            job.cancelAndJoin(); assertEquals(0, session.active)
        }
    }
    @Test fun revokedAuthorizationAndChangedIdentityNeverFallBack() = runBlocking {
        for (error in listOf(AuthorizationRequired(), SSLException("pin"), IdentityMismatch())) {
            val session = Session(); session.probeAction = { throw error }
            try { coordinator().run(ConnectionMode.AUTO, session); fail("security failure swallowed") }
            catch (actual: Exception) { assertEquals(error.javaClass, actual.javaClass); assertEquals(error.message, actual.message) }
            assertTrue(session.routes.isEmpty())
        }
    }
    @Test fun lanFailureFallsBackWithoutOverlappingStreams() = runBlocking {
        val session = Session(); session.receiver = { if (it == ActiveTransport.LAN) throw IOException("lost") else awaitCancellation() }
        val job = launch { coordinator().run(ConnectionMode.AUTO, session) }
        withTimeout(1000) { while (session.routes.size < 2) delay(1) }
        job.cancelAndJoin(); assertEquals(listOf(ActiveTransport.LAN, ActiveTransport.RELAY), session.routes); assertEquals(1, session.maximum)
    }
    @Test fun relayReturnsToLanOnlyAfterTwoSuccessfulChecks() = runBlocking {
        val session = Session(); var initial = true
        session.probeAction = { if (initial) { initial = false; throw IOException("not reachable yet") } }
        val job = launch { coordinator().run(ConnectionMode.AUTO, session) }
        withTimeout(1000) { while (session.routes.size < 2) delay(1) }
        job.cancelAndJoin(); assertEquals(listOf(ActiveTransport.RELAY, ActiveTransport.LAN), session.routes); assertTrue(session.probes >= 4); assertEquals(1, session.maximum)
    }
    @Test fun secondFailedCheckKeepsRelayRunning() = runBlocking {
        val session = Session(); session.probeAction = { if (session.probes == 1 || session.probes == 3) throw IOException("flap") }
        val job = launch { coordinator().run(ConnectionMode.AUTO, session) }
        withTimeout(1000) { while (session.probes < 3) delay(1) }
        assertEquals(listOf(ActiveTransport.RELAY), session.routes); job.cancelAndJoin()
    }
    @Test fun manualModesDoNotSwitchAndUnavailableCredentialsDoNotOpenStreams() = runBlocking {
        val forced = Session(); val job = launch { coordinator().run(ConnectionMode.RELAY, forced) }
        assertEquals(ActiveTransport.RELAY, withTimeout(1000) { forced.first.await() }); job.cancelAndJoin(); assertEquals(0, forced.probes)
        val missing = Session(lan = false, relay = false); val waiting = launch { coordinator().run(ConnectionMode.AUTO, missing) }
        withTimeout(1000) { while (missing.waits == 0) delay(1) }; waiting.cancelAndJoin(); assertTrue(missing.routes.isEmpty())
    }
    @Test fun pauseThenResumeDoesNotCreateANewSession() {
        assertEquals("RESUME", ReceiveCommands.startAction(false))
        assertEquals("BEGIN", ReceiveCommands.startAction(true))
    }
    @Test fun cancellingOneComputerDoesNotInterruptAnother() = runBlocking {
        val first = Session(); val second = Session(lan = false)
        val firstJob = launch { coordinator().run(ConnectionMode.AUTO, first) }
        val secondJob = launch { coordinator().run(ConnectionMode.AUTO, second) }
        withTimeout(1000) { first.first.await(); second.first.await() }
        firstJob.cancelAndJoin()
        assertEquals(0, first.active); assertEquals(1, second.active); assertTrue(secondJob.isActive)
        secondJob.cancelAndJoin(); assertEquals(0, second.active)
    }
    @Test fun changingRoutesWaitsForOldReceiverCleanup() = runBlocking {
        val session = Session(); var initial = true; var cleaned = false
        session.probeAction = { if (initial) { initial = false; throw IOException("LAN unavailable") } }
        session.receiver = { route ->
            if (route == ActiveTransport.RELAY) try { awaitCancellation() } finally {
                withContext(NonCancellable) { delay(25); cleaned = true }
            } else { assertTrue("LAN started before relay cleanup", cleaned); awaitCancellation() }
        }
        val job = launch { coordinator().run(ConnectionMode.AUTO, session) }
        withTimeout(1000) { while (session.routes.size < 2) delay(1) }
        job.cancelAndJoin(); assertTrue(cleaned); assertEquals(1, session.maximum)
    }
}
