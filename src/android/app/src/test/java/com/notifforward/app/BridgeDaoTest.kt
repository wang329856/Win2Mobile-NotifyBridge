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
    private class FakeDao(var pc: Computer) : BridgeDao() {
        val events = mutableListOf<SavedNotification>()
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
        override suspend fun clearHistory() { events.clear() }
        override suspend fun deleteHistory(id: String) { events.removeAll { it.serverId == id } }
        override suspend fun deleteComputer(id: String) { }
    }
}
