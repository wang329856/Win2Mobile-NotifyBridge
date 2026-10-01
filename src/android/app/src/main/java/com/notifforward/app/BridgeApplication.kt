package com.notifforward.app

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.*

class BridgeApplication : Application() {
    val db by lazy { Room.databaseBuilder(this, BridgeDatabase::class.java, "bridge.db").build() }
    val tokens by lazy { SecureTokenStore(this) }
    val transports: BridgeTransportFactory by lazy { LanTransportFactory(this) }
    val preferences by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    val initialized by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO).async { db.dao().resetConnections() } }
    override fun onCreate() { super.onCreate(); initialized }
}
