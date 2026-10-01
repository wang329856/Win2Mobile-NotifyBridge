package com.notifforward.app

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Entity(tableName = "computers")
data class Computer(@PrimaryKey val serverId: String, val serverName: String, val baseUrl: String,
    val certificateSha256: String, val deviceId: String, val cursor: Long = 0,
    val enabled: Boolean = true, val state: String = "等待连接", val error: String = "",
    @ColumnInfo(defaultValue = "0") val remoteEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "0") val relaySequence: Long = 0,
    @ColumnInfo(defaultValue = "''") val relayMessageId: String = "",
    @ColumnInfo(defaultValue = "0") val relayGapUntil: Long = 0,
    @ColumnInfo(defaultValue = "''") val sessionStartedAt: String = "",
    @ColumnInfo(defaultValue = "1") val sessionPending: Boolean = true,
    @ColumnInfo(defaultValue = "0") val hiddenThrough: Long = 0)
@Entity(tableName = "notifications", primaryKeys = ["serverId", "eventId"],
    indices = [Index(value = ["serverId", "sequence"], unique = true)])
data class SavedNotification(val serverId: String, val eventId: String, val sequence: Long, val appId: String,
    val appName: String, val title: String, val body: String, val occurredAt: String)

@Entity(tableName = "dismissed_notifications", primaryKeys = ["serverId", "eventId"])
data class DismissedNotification(val serverId: String, val eventId: String)

data class AppOption(val appId: String, val appName: String)

@Dao abstract class BridgeDao {
    @Query("SELECT * FROM computers ORDER BY serverName") abstract fun observeComputers(): Flow<List<Computer>>
    @Query("SELECT * FROM notifications WHERE (:appId IS NULL OR appId=:appId) AND (:query='' OR instr(lower(title || char(10) || body || char(10) || appName), lower(:query))>0) ORDER BY occurredAt DESC, sequence DESC LIMIT :limit")
    abstract fun observeNotifications(query: String, appId: String?, limit: Int): Flow<List<SavedNotification>>
    @Query("SELECT COUNT(*) FROM notifications") abstract fun observeCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM notifications WHERE (:appId IS NULL OR appId=:appId) AND (:query='' OR instr(lower(title || char(10) || body || char(10) || appName), lower(:query))>0)")
    abstract fun observeMatchCount(query: String, appId: String?): Flow<Int>
    @Query("SELECT appId, MAX(appName) AS appName FROM notifications GROUP BY appId ORDER BY appName") abstract fun observeApps(): Flow<List<AppOption>>
    @Query("SELECT * FROM computers") abstract suspend fun computers(): List<Computer>
    @Query("SELECT * FROM computers WHERE serverId=:id") abstract suspend fun computer(id: String): Computer?
    @Upsert abstract suspend fun saveComputer(computer: Computer)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insert(event: SavedNotification): Long
    @Query("SELECT * FROM notifications WHERE serverId=:id AND eventId=:eventId") abstract suspend fun savedEvent(id: String, eventId: String): SavedNotification?
    @Query("UPDATE computers SET state='等待连接',error='' WHERE state IN ('已连接','正在连接','中转已连接','正在连接中转')") abstract suspend fun resetConnections()
    @Query("UPDATE computers SET cursor=:cursor WHERE serverId=:id") abstract suspend fun cursor(id: String, cursor: Long)
    @Query("UPDATE computers SET state=:state,error=:error WHERE serverId=:id") abstract suspend fun state(id: String, state: String, error: String = "")
    @Query("UPDATE computers SET enabled=:enabled,state=:state,error='' WHERE serverId=:id") abstract suspend fun enabled(id: String, enabled: Boolean, state: String)
    @Query("DELETE FROM notifications") abstract suspend fun deleteAllHistory()
    @Query("UPDATE computers SET hiddenThrough=MAX(hiddenThrough,cursor,relaySequence) WHERE serverId=:id") abstract suspend fun hideReceived(id: String)
    @Query("UPDATE computers SET sessionPending=1,sessionStartedAt='',relayMessageId='',relayGapUntil=0 WHERE serverId=:id") abstract suspend fun resetSession(id: String)
    @Query("UPDATE computers SET sessionPending=0,sessionStartedAt=:startedAt WHERE serverId=:id AND sessionPending=1") abstract suspend fun sessionConnected(id: String, startedAt: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun dismiss(value: DismissedNotification)
    @Query("SELECT COUNT(*) FROM dismissed_notifications WHERE serverId=:id AND eventId=:eventId") abstract suspend fun dismissed(id: String, eventId: String): Int
    @Query("DELETE FROM notifications WHERE serverId=:id AND eventId=:eventId") abstract suspend fun deleteEvent(id: String, eventId: String)
    @Query("DELETE FROM dismissed_notifications WHERE serverId=:id") abstract suspend fun deleteDismissals(id: String)
    @Transaction open suspend fun deleteNotification(id: String, eventId: String) { dismiss(DismissedNotification(id, eventId)); deleteEvent(id, eventId) }
    @Transaction open suspend fun clearHistory() {
        computers().forEach { hideReceived(it.serverId); deleteDismissals(it.serverId) }
        deleteAllHistory()
    }
    @Transaction open suspend fun beginSession() {
        clearHistory()
        computers().forEach { resetSession(it.serverId) }
    }
    @Transaction open suspend fun alignCursor(id: String, value: Long) {
        require(value >= 0)
        val current = computer(id) ?: error("电脑已移除")
        val next = maxOf(current.cursor, value)
        cursor(id, next)
        if (next >= current.relayGapUntil) relayGap(id, 0)
    }
    @Query("DELETE FROM notifications WHERE serverId=:id") abstract suspend fun deleteHistory(id: String)
    @Query("DELETE FROM computers WHERE serverId=:id") abstract suspend fun deleteComputer(id: String)
    @Query("UPDATE computers SET remoteEnabled=:remote,state='等待连接',error='' WHERE serverId=:id")
    abstract suspend fun remote(id: String, remote: Boolean)
    @Query("UPDATE computers SET baseUrl=:address WHERE serverId=:id")
    abstract suspend fun lanAddress(id: String, address: String)
    @Transaction open suspend fun updateLanAddress(id: String, address: String) {
        require(computer(id) != null) { "电脑已移除" }
        lanAddress(id, LanAddress.normalize(address))
    }
    @Query("UPDATE computers SET relaySequence=:sequence,relayMessageId=:messageId WHERE serverId=:id")
    abstract suspend fun relayCursor(id: String, sequence: Long, messageId: String)
    @Query("UPDATE computers SET relayGapUntil=:sequence WHERE serverId=:id") abstract suspend fun relayGap(id: String, sequence: Long)
    @Transaction open suspend fun savePairing(metadata: Computer) {
        val current = computer(metadata.serverId)
        deleteHistory(metadata.serverId)
        deleteDismissals(metadata.serverId)
        saveComputer(metadata.copy(cursor = maxOf(current?.cursor ?: 0, metadata.cursor),
            hiddenThrough = maxOf(current?.hiddenThrough ?: 0, current?.cursor ?: 0, current?.relaySequence ?: 0, metadata.cursor)))
    }
    @Transaction open suspend fun removeComputer(id: String) { deleteHistory(id); deleteDismissals(id); deleteComputer(id) }
    private suspend fun shouldKeep(computer: Computer, event: BridgeEvent): Boolean = event.sequence > computer.hiddenThrough &&
        (computer.sessionStartedAt.isEmpty() || !Instant.parse(event.occurredAt).isBefore(Instant.parse(computer.sessionStartedAt))) &&
        dismissed(computer.serverId, event.eventId) == 0
    @Transaction open suspend fun accept(id: String, event: BridgeEvent): Boolean {
        event.validate(id)
        val computer = computer(id) ?: error("电脑已移除")
        val next = CursorPolicy.advance(computer.cursor, event.sequence)
        val saved = SavedNotification(id, event.eventId, event.sequence, event.appId, event.appName, event.title, event.body, event.occurredAt)
        if (event.sequence <= computer.cursor || !shouldKeep(computer, event)) {
            savedEvent(id, event.eventId)?.let { require(it == saved) { "已保存事件内容冲突" } }
            cursor(id, next)
            if (computer.relayGapUntil > 0 && next >= computer.relayGapUntil) relayGap(id, 0)
            return false
        }
        val inserted = insert(saved) != -1L
        require(inserted || savedEvent(id, event.eventId) == saved) { "事件标识或序号冲突，游标未推进" }
        cursor(id, next)
        if (computer.relayGapUntil > 0 && next >= computer.relayGapUntil) relayGap(id, 0)
        return inserted && event.sequence > computer.cursor
    }
    @Transaction open suspend fun acceptRelay(id: String, event: BridgeEvent, messageId: String): Boolean {
        event.validate(id)
        val computer = computer(id) ?: error("电脑已移除")
        val saved = SavedNotification(id, event.eventId, event.sequence, event.appId, event.appName, event.title, event.body, event.occurredAt)
        if (event.sequence <= computer.relaySequence || !shouldKeep(computer, event)) {
            savedEvent(id, event.eventId)?.let { require(it == saved) { "已保存中转事件内容冲突" } }
            if (event.sequence >= computer.relaySequence) relayCursor(id, event.sequence, messageId)
            return false
        }
        val inserted = insert(saved) != -1L
        require(inserted || savedEvent(id, event.eventId) == saved) { "中转事件标识或序号冲突" }
        // Relay cache can expire: preserve later events and keep the strict LAN cursor for repair.
        if (event.sequence > computer.relaySequence + 1 && computer.cursor < event.sequence) {
            relayGap(id, maxOf(computer.relayGapUntil, event.sequence))
            state(id, "中转已连接", "本次接收可能有缺失；可切回局域网补收仍在短期队列中的通知")
        }
        if (event.sequence >= computer.relaySequence) relayCursor(id, event.sequence, messageId)
        return inserted && event.sequence > computer.relaySequence
    }
}
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE computers ADD COLUMN remoteEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE computers ADD COLUMN relaySequence INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE computers ADD COLUMN relayMessageId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE computers ADD COLUMN relayGapUntil INTEGER NOT NULL DEFAULT 0")
    }
}
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE computers ADD COLUMN sessionStartedAt TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE computers ADD COLUMN sessionPending INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE computers ADD COLUMN hiddenThrough INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE computers SET hiddenThrough=MAX(cursor,relaySequence),relayGapUntil=0,relayMessageId=''")
        db.execSQL("DELETE FROM notifications")
        db.execSQL("CREATE TABLE IF NOT EXISTS dismissed_notifications (serverId TEXT NOT NULL,eventId TEXT NOT NULL,PRIMARY KEY(serverId,eventId))")
    }
}
@Database(entities = [Computer::class, SavedNotification::class, DismissedNotification::class], version = 3, exportSchema = false)
abstract class BridgeDatabase : RoomDatabase() { abstract fun dao(): BridgeDao }
