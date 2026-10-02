package com.notifforward.app

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.*

class BridgeApplication : Application() {
    val db by lazy { Room.databaseBuilder(this, BridgeDatabase::class.java, "bridge.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build() }
    val serviceActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    val tokens by lazy { SecureTokenStore(this) }
    val transports: BridgeTransportFactory by lazy { LanTransportFactory(this) }
    val preferences by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    val initialized by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO).async { db.dao().resetConnections() } }
    override fun onCreate() { super.onCreate(); initialized }
}
