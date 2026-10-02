package com.notifforward.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Execute the real accept implementation with a DAO whose unique constraints mirror Room. */
class BridgeDaoTest {
    private val server = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val first = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val second = "cccccccc-cccc-cccc-cccc-cccccccccccc"
    private fun event(sequence: Long, id: String = first, body: String = "Body") =
        BridgeEvent(sequence, id, server, "source", "app", "App", "Title", body, "2026-09-30T00:00:00Z")
    private fun dao(cursor: Long = 0) = FakeDao(Computer(server, "PC", "https://192.168.1.2", "A".repeat(64), "device", cursor))
    @Test fun persistsBeforeAdvancingCursorAndReplayIsIdempotent() = runBlocking {
        val dao = dao()
        assertTrue(dao.accept(server, event(1)))
        assertEquals(listOf("insert", "cursor"), dao.operations)
        assertEquals(1L, dao.computer(server)?.cursor)
        assertFalse(dao.accept(server, event(1)))
        assertEquals(1, dao.events.size)
        assertEquals(1L, dao.computer(server)?.cursor)
    }
    @Test fun eventIdConflictDoesNotAdvanceCursor() = runBlocking {
        val dao = dao()
        dao.accept(server, event(1))
        try { dao.accept(server, event(2)); fail("Conflicting event ID advanced cursor") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1L, dao.computer(server)?.cursor)
        assertEquals(1, dao.events.size)
    }
    @Test fun sequenceConflictDoesNotAdvanceCursor() = runBlocking {
        val dao = dao()
        dao.events += SavedNotification(server, first, 1, "app", "App", "Title", "Body", "2026-09-30T00:00:00Z")
        try { dao.accept(server, event(1, second)); fail("Conflicting sequence advanced cursor") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0L, dao.computer(server)?.cursor)
        assertFalse(dao.operations.contains("cursor"))
    }
    @Test fun changedReplayPayloadIsRejectedAndSequenceGapDoesNotInsert() = runBlocking {
        val dao = dao()
        dao.accept(server, event(1))
        try { dao.accept(server, event(1, body = "Changed")); fail("Changed replay accepted") }
        catch (_: IllegalArgumentException) { }
        val before = dao.operations.size
        try { dao.accept(server, event(3, second)); fail("Gap accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, dao.operations.size)
        assertEquals(1L, dao.computer(server)?.cursor)
    }
    @Test fun repairingReadsLatestCursorWithinDaoTransactionBoundary() = runBlocking {
        val dao = dao(4)
        val staleMetadata = dao.pc.copy(serverName = "Renamed", baseUrl = "https://192.168.1.9", cursor = 4)
        dao.accept(server, event(5))
        dao.savePairing(staleMetadata)
        assertEquals(5L, dao.computer(server)?.cursor)
        assertEquals("https://192.168.1.9", dao.computer(server)?.baseUrl)
        assertEquals("Renamed", dao.computer(server)?.serverName)
    }
    @Test fun changingLanAddressPreservesRemoteIdentityAndAllSyncProgress() = runBlocking {
        val dao = dao(7)
        dao.pc = dao.pc.copy(remoteEnabled = true, relaySequence = 12, relayMessageId = "Msg12", relayGapUntil = 12)
        val before = dao.pc
        dao.updateLanAddress(server, "192.168.1.20")
        assertEquals(before.copy(baseUrl = "https://192.168.1.20:47721"), dao.pc)
        try { dao.updateLanAddress(server, "http://192.168.1.21"); fail("Invalid address saved") }
        catch (_: IllegalArgumentException) { }
        assertEquals("https://192.168.1.20:47721", dao.pc.baseUrl)
    }
    @Test fun relayGapKeepsLanCursorAndLanRepairsWithoutDuplicatingAlerts() = runBlocking {
        val dao = dao()
        assertTrue(dao.acceptRelay(server, event(2, second), "Message2"))
        assertEquals(0L, dao.pc.cursor)
        assertEquals(2L, dao.pc.relaySequence)
        assertEquals(2L, dao.pc.relayGapUntil)
        assertTrue(dao.pc.error.contains("缺失"))
        assertTrue(dao.accept(server, event(1)))
        assertFalse(dao.accept(server, event(2, second)))
        assertEquals(2L, dao.pc.cursor)
        assertEquals(0L, dao.pc.relayGapUntil)
        assertEquals(2, dao.events.size)
    }
    @Test fun relayReplayAndConflictDoNotProduceDuplicatesOrCorruptProgress() = runBlocking {
        val dao = dao()
        assertTrue(dao.acceptRelay(server, event(1), "Message1"))
        assertFalse(dao.acceptRelay(server, event(1), "Message1Retry"))
        assertEquals(0L, dao.pc.cursor)
        assertEquals(1L, dao.pc.relaySequence)
        try { dao.acceptRelay(server, event(2), "Conflict"); fail("Relay conflict accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(1L, dao.pc.relaySequence)
        assertEquals("Message1Retry", dao.pc.relayMessageId)
        assertEquals(1, dao.events.size)
    }
    @Test fun clearCannotBeUndoneByLanRelayReplaysOrSwitchingTransports() = runBlocking {
        val dao = dao()
        dao.acceptRelay(server, event(2, second), "M2")
        dao.accept(server, event(1))
        dao.clearHistory()
        assertTrue(dao.events.isEmpty())
        assertFalse(dao.accept(server, event(1)))
        assertFalse(dao.accept(server, event(2, second)))
        assertFalse(dao.acceptRelay(server, event(1), "M1"))
        assertFalse(dao.acceptRelay(server, event(2, second), "M2retry"))
        assertTrue(dao.events.isEmpty())
        assertTrue(dao.accept(server, event(3, "dddddddd-dddd-dddd-dddd-dddddddddddd")))
        assertEquals(1, dao.events.size)
    }
    @Test fun individualDeletionSurvivesRelayFirstThenLanCatchup() = runBlocking {
        val dao = dao()
        dao.acceptRelay(server, event(2, second), "M2")
        dao.deleteNotification(server, second)
        assertTrue(dao.events.isEmpty())
        assertFalse(dao.acceptRelay(server, event(2, second), "Retry"))
        assertTrue(dao.accept(server, event(1)))
        assertFalse(dao.accept(server, event(2, second)))
        assertEquals(listOf(first), dao.events.map { it.eventId })
        assertEquals(2L, dao.pc.cursor)
    }
    @Test fun newSessionClearsContentButReconnectAndAddressUpdateKeepCurrentMessages() = runBlocking {
        val dao = dao()
        dao.accept(server, event(1))
        val identity = dao.pc.deviceId
        dao.beginSession()
        assertTrue(dao.events.isEmpty())
        assertEquals(identity, dao.pc.deviceId)
        assertEquals(1L, dao.pc.hiddenThrough)
        assertTrue(dao.pc.sessionPending)
        dao.sessionConnected(server, "2026-10-01T00:00:00Z")
        dao.alignCursor(server, 5)
        assertFalse(dao.acceptRelay(server, event(2, second), "Old"))
        val fresh = event(6, second).copy(occurredAt = "2026-10-01T00:00:01Z")
        assertTrue(dao.accept(server, fresh))
        dao.sessionConnected(server, "2026-10-01T00:01:00Z")
        dao.updateLanAddress(server, "192.168.1.8")
        assertEquals("2026-10-01T00:00:00Z", dao.pc.sessionStartedAt)
        assertEquals(listOf(second), dao.events.map { it.eventId })
        assertFalse(dao.accept(server, fresh))
    }
    @Test fun undoRestoresDeletionMarkerWithoutRewindingEitherCursor() = runBlocking {
        val dao = dao()
        dao.accept(server, event(1))
        val ticket = dao.deleteWithUndo(dao.events.single())!!
        assertTrue(dao.events.isEmpty())
        assertEquals(1, dao.dismissed(server, first))
        assertTrue(dao.restoreNotification(ticket))
        assertEquals(0, dao.dismissed(server, first))
        assertEquals(1L, dao.pc.cursor)
        assertFalse(dao.accept(server, event(1)))
        assertEquals(1, dao.events.size)
    }
    @Test fun clearNewSessionAndRepairInvalidatePendingUndo() = runBlocking {
        for (action in listOf<suspend (FakeDao) -> Unit>({ it.clearHistory() }, { it.beginSession() }, { it.savePairing(it.pc) })) {
            val dao = dao(); dao.accept(server, event(1))
            val ticket = dao.deleteWithUndo(dao.events.single())!!
            action(dao)
            assertFalse(dao.restoreNotification(ticket))
            assertTrue(dao.events.isEmpty())
        }
    }
    private class FakeDao(var pc: Computer) : BridgeDao() {
        val events = mutableListOf<SavedNotification>()
        val deleted = mutableSetOf<DismissedNotification>()
        val operations = mutableListOf<String>()
        override fun observeComputers(): Flow<List<Computer>> = flowOf(listOf(pc))
        override fun observeNotifications(query: String, appId: String?, limit: Int): Flow<List<SavedNotification>> = flowOf(events.take(limit))
        override fun observeCount(): Flow<Int> = flowOf(events.size)
        override fun observeMatchCount(query: String, appId: String?): Flow<Int> = flowOf(events.size)
        override fun observeApps(): Flow<List<AppOption>> = flowOf(emptyList())
        override suspend fun computers() = listOf(pc)
        override suspend fun computer(id: String) = pc.takeIf { it.serverId == id }
        override suspend fun saveComputer(computer: Computer) { pc = computer }
        override suspend fun insert(event: SavedNotification): Long {
            operations += "insert"
            if (events.any { it.serverId == event.serverId && (it.eventId == event.eventId || it.sequence == event.sequence) }) return -1
            events += event
            return events.size.toLong()
        }
        override suspend fun savedEvent(id: String, eventId: String) = events.firstOrNull { it.serverId == id && it.eventId == eventId }
        override suspend fun resetConnections() { pc = pc.copy(state = "等待连接") }
        override suspend fun cursor(id: String, cursor: Long) { operations += "cursor"; pc = pc.copy(cursor = cursor) }
        override suspend fun state(id: String, state: String, error: String) { pc = pc.copy(state = state, error = error) }
        override suspend fun enabled(id: String, enabled: Boolean, state: String) { pc = pc.copy(enabled = enabled, state = state) }
        override suspend fun deleteAllHistory() { events.clear() }
        override suspend fun hideReceived(id: String) { pc = pc.copy(hiddenThrough = maxOf(pc.hiddenThrough, pc.cursor, pc.relaySequence)) }
        override suspend fun resetSession(id: String) { pc = pc.copy(sessionPending = true, sessionStartedAt = "", relayGapUntil = 0, relayMessageId = "") }
        override suspend fun sessionConnected(id: String, startedAt: String) { if (pc.sessionPending) pc = pc.copy(sessionPending = false, sessionStartedAt = startedAt) }
        override suspend fun dismiss(value: DismissedNotification) { deleted += value }
        override suspend fun dismissed(id: String, eventId: String) = if (DismissedNotification(id, eventId) in deleted) 1 else 0
        override suspend fun deleteEvent(id: String, eventId: String) { events.removeAll { it.serverId == id && it.eventId == eventId } }
        override suspend fun deleteDismissals(id: String) { deleted.removeAll { it.serverId == id } }
        override suspend fun undismiss(id: String, eventId: String) { deleted.remove(DismissedNotification(id, eventId)) }
        override suspend fun connectionMode(id: String, mode: String) { pc = pc.copy(connectionMode = mode) }
        override suspend fun deleteHistory(id: String) { events.removeAll { it.serverId == id } }
        override suspend fun deleteComputer(id: String) { }
        override suspend fun remote(id: String, remote: Boolean) { pc = pc.copy(remoteEnabled = remote) }
        override suspend fun lanAddress(id: String, address: String) { pc = pc.copy(baseUrl = address) }
        override suspend fun relayCursor(id: String, sequence: Long, messageId: String) { operations += "relayCursor"; pc = pc.copy(relaySequence = sequence, relayMessageId = messageId) }
        override suspend fun relayGap(id: String, sequence: Long) { pc = pc.copy(relayGapUntil = sequence) }
    }
}
