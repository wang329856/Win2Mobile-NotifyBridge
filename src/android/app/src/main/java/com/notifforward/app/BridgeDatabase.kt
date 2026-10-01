package com.notifforward.app

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "computers")
data class Computer(@PrimaryKey val serverId: String, val serverName: String, val baseUrl: String,
    val certificateSha256: String, val deviceId: String, val cursor: Long = 0,
    val enabled: Boolean = true, val state: String = "等待连接", val error: String = "")
@Entity(tableName = "notifications", primaryKeys = ["serverId", "eventId"],
    indices = [Index(value = ["serverId", "sequence"], unique = true)])
data class SavedNotification(val serverId: String, val eventId: String, val sequence: Long, val appId: String,
    val appName: String, val title: String, val body: String, val occurredAt: String)

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
    @Query("UPDATE computers SET state='等待连接',error='' WHERE state IN ('已连接','正在连接')") abstract suspend fun resetConnections()
    @Query("UPDATE computers SET cursor=:cursor WHERE serverId=:id") abstract suspend fun cursor(id: String, cursor: Long)
    @Query("UPDATE computers SET state=:state,error=:error WHERE serverId=:id") abstract suspend fun state(id: String, state: String, error: String = "")
    @Query("UPDATE computers SET enabled=:enabled,state=:state,error='' WHERE serverId=:id") abstract suspend fun enabled(id: String, enabled: Boolean, state: String)
    @Query("DELETE FROM notifications") abstract suspend fun clearHistory()
    @Query("DELETE FROM notifications WHERE serverId=:id") abstract suspend fun deleteHistory(id: String)
    @Query("DELETE FROM computers WHERE serverId=:id") abstract suspend fun deleteComputer(id: String)
    @Transaction open suspend fun savePairing(metadata: Computer) {
        val current = computer(metadata.serverId)
        saveComputer(metadata.copy(cursor = current?.cursor ?: 0))
    }
    @Transaction open suspend fun removeComputer(id: String) { deleteHistory(id); deleteComputer(id) }
    @Transaction open suspend fun accept(id: String, event: BridgeEvent): Boolean {
        event.validate(id)
        val computer = computer(id) ?: error("电脑已移除")
        val next = CursorPolicy.advance(computer.cursor, event.sequence)
        val saved = SavedNotification(id, event.eventId, event.sequence, event.appId, event.appName, event.title, event.body, event.occurredAt)
        val inserted = insert(saved) != -1L
        require(inserted || savedEvent(id, event.eventId) == saved) { "事件标识或序号冲突，游标未推进" }
        cursor(id, next)
        return inserted && event.sequence > computer.cursor
    }
}
@Database(entities = [Computer::class, SavedNotification::class], version = 1, exportSchema = false)
abstract class BridgeDatabase : RoomDatabase() { abstract fun dao(): BridgeDao }
