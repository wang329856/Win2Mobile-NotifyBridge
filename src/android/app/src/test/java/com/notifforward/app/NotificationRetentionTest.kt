package com.notifforward.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NotificationRetentionTest {
    private class Sink : MessageNotificationSink {
        val visible = mutableSetOf<Pair<String, String>>()
        var foregroundStatus = true
        override fun activeMessages() = visible.toList()
        override fun cancelEvent(serverId: String, eventId: String) { visible.remove(serverId to eventId) }
        override fun cancelComputer(serverId: String) { visible.removeAll { it.first == serverId } }
        override fun cancelMessages() { visible.clear() }
    }
    @Test fun deletingWhilePersistenceIsSuspendedCannotLeaveALateAlert() = runBlocking {
        val sink = Sink(); val retention = NotificationRetention(sink)
        val saving = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var saved = false; var dismissed = false
        val receiver = launch(start = CoroutineStart.UNDISPATCHED) {
            retention.deliver(false, save = { saving.complete(Unit); release.await(); saved = true; true },
                display = { sink.visible += "A" to "event" }, acknowledge = {})
        }
        saving.await()
        val deleting = launch(start = CoroutineStart.UNDISPATCHED) {
            retention.delete("A", "event") { saved = false; dismissed = true }
        }
        assertFalse(deleting.isCompleted)
        release.complete(Unit); receiver.join(); deleting.join()
        assertTrue(dismissed); assertFalse(saved); assertTrue(sink.visible.isEmpty())
        retention.deliver(false, save = { !dismissed }, display = { sink.visible += "A" to "event" }, acknowledge = {})
        assertTrue(sink.visible.isEmpty())
    }
    @Test fun deletionIsNotBlockedByTransportAcknowledgement() = runBlocking {
        val sink = Sink(); val retention = NotificationRetention(sink)
        val acknowledging = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val receiver = launch(start = CoroutineStart.UNDISPATCHED) {
            retention.deliver(false, save = { true }, display = { sink.visible += "A" to "event" },
                acknowledge = { acknowledging.complete(Unit); release.await() })
        }
        acknowledging.await()
        withTimeout(1000) { retention.delete("A", "event") {} }
        assertTrue(sink.visible.isEmpty()); release.complete(Unit); receiver.join()
    }
    @Test fun clearAndNewSessionWithdrawOnlyMessagesAndKeepReceivingStatus() = runBlocking {
        val sink = Sink(); val retention = NotificationRetention(sink)
        for (ignored in 1..2) {
            sink.visible += setOf("A" to "one", "B" to "two")
            var cleared = false
            retention.clear { cleared = true }
            assertTrue(cleared); assertTrue(sink.visible.isEmpty()); assertTrue(sink.foregroundStatus)
        }
    }
    @Test fun removingOrRepairingOneComputerKeepsOtherComputersNotifications() = runBlocking {
        val sink = Sink(); val retention = NotificationRetention(sink)
        sink.visible += setOf("A" to "one", "A" to "two", "B" to "three")
        retention.replaceComputer("A") {}
        assertEquals(setOf("B" to "three"), sink.visible); assertTrue(sink.foregroundStatus)
    }
    @Test fun replayIsSilentAndUndoDoesNotGenerateAnotherSystemAlert() = runBlocking {
        val sink = Sink(); val retention = NotificationRetention(sink)
        retention.deliver(true, save = { true }, display = { sink.visible += "A" to "event" }, acknowledge = {})
        assertTrue(sink.visible.isEmpty())
        sink.visible += "A" to "event"
        var saved = true
        retention.delete("A", "event") { saved = false }
        retention.restore { saved = true }
        assertTrue(saved); assertTrue(sink.visible.isEmpty())
    }
    @Test fun clearWaitingForASaveCancelsTheJustDisplayedMessage() = runBlocking {
        val sink = Sink(); val retention = NotificationRetention(sink)
        val release = CompletableDeferred<Unit>(); val saving = CompletableDeferred<Unit>()
        val receiver = launch(start = CoroutineStart.UNDISPATCHED) {
            retention.deliver(false, save = { saving.complete(Unit); release.await(); true },
                display = { sink.visible += "A" to "event" }, acknowledge = {})
        }
        saving.await()
        val clearing = launch(start = CoroutineStart.UNDISPATCHED) { retention.clear {} }
        release.complete(Unit); receiver.join(); clearing.join()
        assertTrue(sink.visible.isEmpty())
    }
    @Test fun cancellationAfterDeletionStartsStillWithdrawsTheNotification() = runBlocking {
        val sink = Sink(); val retention = NotificationRetention(sink)
        sink.visible += "A" to "event"
        val deleting = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var removed = false
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            retention.delete("A", "event") { deleting.complete(Unit); release.await(); removed = true }
        }
        deleting.await(); task.cancel(); release.complete(Unit); task.join()
        assertTrue(removed); assertTrue(sink.visible.isEmpty())
    }
    @Test fun startupReconcilesStaleNotificationsFromAnOlderVersion() = runBlocking {
        val sink = Sink(); val retention = NotificationRetention(sink)
        sink.visible += setOf("A" to "deleted", "B" to "kept")
        retention.reconcile { server, event -> server == "B" && event == "kept" }
        assertEquals(setOf("B" to "kept"), sink.visible)
    }
    @Test fun failedCredentialRemovalCannotStrandNotificationsAfterRoomDeletion() = runBlocking {
        val sink = Sink(); val retention = NotificationRetention(sink)
        sink.visible += setOf("A" to "removed", "B" to "kept")
        var removed = false
        val result = retention.removeComputer("A", removeContent = { removed = true },
            removeCredentials = {
                assertTrue(removed)
                assertFalse(sink.visible.any { it.first == "A" })
                error("Synthetic SharedPreferences persistence failure")
            })
        assertTrue(result.isFailure); assertTrue(removed)
        assertEquals(setOf("B" to "kept"), sink.visible)
    }
}
