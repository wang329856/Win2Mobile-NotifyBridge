package com.notifforward.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[BridgeViewModel::class.java] }
    private val scanner = registerForActivityResult(ScanContract()) { result -> result.contents?.let { model.scanResult(it) } }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scan() else model.report("未获得相机权限，可点击连接菜单粘贴配对信息")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.notificationsEnabled.value = granted
        if (!granted) model.report("系统通知未开启，消息仍会保存，可在设置中开启")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); readFocus(intent)
        if (savedInstanceState?.containsKey("scanTarget") == true) model.scanTarget = savedInstanceState.getString("scanTarget")
        setContent {
            val preferences by model.preferences.collectAsStateWithLifecycle()
            val dark = preferences.theme == "Dark" || (preferences.theme == "System" && isSystemInDarkTheme())
            SideEffect { WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } }
            BridgeTheme(preferences.theme) { BridgeScreen(model, ::requestScan, ::requestNotices, ::launchSettings) }
        }
    }
    override fun onResume() {
        super.onResume()
        model.notificationsEnabled.value = getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
        model.batteryUnrestricted.value = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readFocus(intent) }
    private fun readFocus(intent: Intent) {
        val server = intent.getStringExtra("serverId"); val event = intent.getStringExtra("eventId")
        if (server != null && event != null) model.focus(server, event)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("scanTarget", model.scanTarget)
        super.onSaveInstanceState(outState)
    }
    private fun scan() {
        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setCaptureActivity(ScannerActivity::class.java).setBeepEnabled(false)
            .addExtra(ScannerActivity.EXTRA_UPDATING, model.scanTarget != null))
    }
    private fun requestScan(target: String?) {
        model.scanTarget = target
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scan() else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    private fun requestNotices() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun launchSettings(kind: String) {
        val action = when (kind) { "notifications" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS; "battery" -> Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS; else -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS }
        runCatching { startActivity(Intent(action).apply { if (kind == "notifications") putExtra(Settings.EXTRA_APP_PACKAGE, packageName) else if (kind != "battery") data = Uri.parse("package:" + packageName) }) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}
