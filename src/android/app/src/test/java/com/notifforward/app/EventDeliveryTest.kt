package com.notifforward.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EventDeliveryTest {
    @Test fun liveNotificationIsDisplayedBeforeAckEvenWhenAckFails() = runBlocking {
        val calls = mutableListOf<String>()
        try {
            EventDelivery.deliver(false,
                save = { calls += "save"; true }, display = { calls += "display" },
                acknowledge = { calls += "ack"; throw java.io.IOException("Connection lost") })
            fail("ACK failure should trigger transport reconnect")
        } catch (_: java.io.IOException) { }
        assertEquals(listOf("save", "display", "ack"), calls)
    }
    @Test fun replayAndDuplicatesStaySilentButAreAcknowledged() = runBlocking {
        listOf(true to true, false to false).forEach { (replay, added) ->
            val calls = mutableListOf<String>()
            EventDelivery.deliver(replay, save = { calls += "save"; added }, display = { calls += "display" }, acknowledge = { calls += "ack" })
            assertEquals(listOf("save", "ack"), calls)
        }
    }
    @Test fun displayPermissionFailureDoesNotBlockSavedEventAck() = runBlocking {
        var acknowledged = false
        EventDelivery.deliver(false, save = { true }, display = { throw SecurityException("Permission changed") }, acknowledge = { acknowledged = true })
        assertTrue(acknowledged)
    }
    @Test fun failedPersistenceNeverDisplaysOrAcknowledges() = runBlocking {
        val calls = mutableListOf<String>()
        try {
            EventDelivery.deliver(false, save = { throw IllegalArgumentException("Unique conflict") }, display = { calls += "display" }, acknowledge = { calls += "ack" })
            fail("Persistence failure swallowed")
        } catch (_: IllegalArgumentException) { }
        assertTrue(calls.isEmpty())
    }
}
