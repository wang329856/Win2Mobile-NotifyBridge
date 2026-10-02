package com.notifforward.app

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Opens a real v3 SQLite fixture through Room v4; never modifies the user's bridge.db. */
class MigrationVerificationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val display = TextView(this).apply { text = "正在验证真实 SQLite 迁移…"; textSize = 20f; setPadding(48, 80, 48, 40) }; setContentView(display)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { verify(); "PASS: Room v3→v4 preserves computers, notifications, deletion markers, credentials metadata and both cursors. Undo/clear/session checks passed." }.getOrElse { "FAIL: " + it.stackTraceToString() } }
            File(filesDir, "ui-verification.txt").writeText(result)
            Log.i("BridgeVerification", result); display.text = result
        }
    }
    private suspend fun verify() {
        val name = "ui-migration-verification.db"
        deleteDatabase(name)
        val id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), null).use { db ->
            db.execSQL("CREATE TABLE computers (serverId TEXT NOT NULL PRIMARY KEY,serverName TEXT NOT NULL,baseUrl TEXT NOT NULL,certificateSha256 TEXT NOT NULL,deviceId TEXT NOT NULL,cursor INTEGER NOT NULL,enabled INTEGER NOT NULL,state TEXT NOT NULL,error TEXT NOT NULL,remoteEnabled INTEGER NOT NULL DEFAULT 0,relaySequence INTEGER NOT NULL DEFAULT 0,relayMessageId TEXT NOT NULL DEFAULT '',relayGapUntil INTEGER NOT NULL DEFAULT 0,sessionStartedAt TEXT NOT NULL DEFAULT '',sessionPending INTEGER NOT NULL DEFAULT 1,hiddenThrough INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE notifications (serverId TEXT NOT NULL,eventId TEXT NOT NULL,sequence INTEGER NOT NULL,appId TEXT NOT NULL,appName TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,occurredAt TEXT NOT NULL,PRIMARY KEY(serverId,eventId))")
            db.execSQL("CREATE UNIQUE INDEX index_notifications_serverId_sequence ON notifications(serverId,sequence)")
            db.execSQL("CREATE TABLE dismissed_notifications(serverId TEXT NOT NULL,eventId TEXT NOT NULL,PRIMARY KEY(serverId,eventId))")
            db.execSQL("INSERT INTO computers VALUES(?,?,?,?,?,8,1,'中转已连接','',1,12,'Relay12',12,'2026-10-01T00:00:00Z',0,0)", arrayOf(id, "Migration PC", "https://192.168.1.5:47721", "A".repeat(64), "existing-device"))
            db.execSQL("INSERT INTO notifications VALUES(?, 'saved', 8, 'source', 'App', 'Title', 'Body', '2026-10-01T01:00:00Z')", arrayOf(id))
            db.execSQL("INSERT INTO dismissed_notifications VALUES(?, 'deleted')", arrayOf(id))
            db.version = 3
        }
        val migrated = Room.databaseBuilder(this, BridgeDatabase::class.java, name).addMigrations(MIGRATION_3_4).build()
        try {
            val dao = migrated.dao(); val pc = dao.computer(id)!!
            check(pc.connectionMode == "AUTO" && pc.cursor == 8L && pc.relaySequence == 12L && pc.relayMessageId == "Relay12" && pc.relayGapUntil == 12L)
            check(pc.deviceId == "existing-device" && pc.remoteEnabled && !pc.sessionPending && pc.hiddenThrough == 0L)
            check(dao.dismissed(id, "deleted") == 1)
            val item = dao.savedEvent(id, "saved")!!
            val ticket = dao.deleteWithUndo(item)!!; check(dao.savedEvent(id, "saved") == null)
            check(dao.restoreNotification(ticket)); check(dao.dismissed(id, "saved") == 0)
            val invalid = dao.deleteWithUndo(item)!!; dao.clearHistory(); check(!dao.restoreNotification(invalid))
            check(dao.computer(id)!!.cursor == 8L && dao.computer(id)!!.relaySequence == 12L)
            dao.beginSession(); check(dao.computer(id)!!.sessionPending)
            check(ReceiveCommands.startAction(false) == "RESUME")
        } finally { migrated.close(); deleteDatabase(name) }
    }
}
