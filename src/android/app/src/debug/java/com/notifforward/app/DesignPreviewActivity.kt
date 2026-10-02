package com.notifforward.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import kotlinx.coroutines.launch
import java.time.Instant

/** Debug-only previews use the production composables and an isolated in-memory database. */
class DesignPreviewActivity : ComponentActivity() {
    private lateinit var database: BridgeDatabase
    private val previewModels = ViewModelStore()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        if (intent.getBooleanExtra("wide", false)) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        database = Room.inMemoryDatabaseBuilder(this, BridgeDatabase::class.java).build()
        lifecycleScope.launch {
            val id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            if (!intent.getBooleanExtra("empty", false)) {
            database.dao().saveComputer(Computer(id, "我的 Windows 电脑", "https://192.168.1.5:47721", "A".repeat(64), "preview", cursor = 3, state = "已连接", sessionPending = false, sessionStartedAt = Instant.now().minusSeconds(7200).toString()))
            listOf(Triple("日历", "下一段灵感，准备出发", "今天 15:00 · 项目交流会。会议前还有 10 分钟，带上你的想法。"),
                Triple("邮件", "新消息，已抵达手机", "设计稿已更新。你可以在手机上查看电脑的新通知，无需一直守在桌面前。"),
                Triple("Win2Mobile", "你的私人通知桥已就绪", "同网优先直连，离开 Wi-Fi 后自动选择已授权的跨网络通道。"))
                .forEachIndexed { index, (name, title, body) -> database.dao().insert(SavedNotification(id, "preview-" + index, index.toLong() + 1, name, name, title, body, Instant.now().minusSeconds(index * 420L).toString())) }
            }
            val model = ViewModelProvider(previewModels, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = BridgeViewModel(application, database, true) as T
            })[BridgeViewModel::class.java]
            model.notificationsEnabled.value = true; model.batteryUnrestricted.value = true
            if (intent.getBooleanExtra("detail", false)) model.selected.value = database.dao().savedEvent(id, "preview-0")
            val mode = intent.getStringExtra("theme") ?: "Light"
            model.theme(mode)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = mode != "Dark"; isAppearanceLightNavigationBars = mode != "Dark"
            }
            setContent {
                val screenDensity = LocalDensity.current
                val previewDensity = Density(screenDensity.density * intent.getFloatExtra("densityScale", 1f), intent.getFloatExtra("fontScale", screenDensity.fontScale))
                CompositionLocalProvider(LocalDensity provides previewDensity) {
                    BridgeTheme(mode) { BridgeScreen(model, {}, {}, {}, initialTab = intent.getIntExtra("tab", 0)) }
                }
            }
        }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val fresh = android.content.Intent(this, DesignPreviewActivity::class.java)
        intent.extras?.let { fresh.putExtras(it) }
        finish(); startActivity(fresh)
    }
    override fun onDestroy() { previewModels.clear(); if (::database.isInitialized) database.close(); super.onDestroy() }
}
