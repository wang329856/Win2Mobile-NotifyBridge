package com.notifforward.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DefaultDecoderFactory

class ScannerActivity : ComponentActivity() {
    private lateinit var camera: BarcodeView
    private var torch by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var hasCameraPermission by mutableStateOf(false)
    private var resultDelivered = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        if (granted) { error = null; camera.resume() } else error = "相机权限未开启"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
        }
        val metrics = resources.displayMetrics
        val frame = minOf(metrics.widthPixels * .74f, metrics.heightPixels * .38f).toInt()
        camera = BarcodeView(this).apply {
            setUseTextureView(true)
            framingRectSize = com.journeyapps.barcodescanner.Size(frame, frame)
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            addStateListener(object : CameraPreview.StateListener {
                override fun previewSized() {}
                override fun previewStarted() { error = null }
                override fun previewStopped() {}
                override fun cameraClosed() {}
                override fun cameraError(exception: Exception) { error = "相机暂不可用，请重试" }
            })
            decodeSingle(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult) {
                    if (resultDelivered) return
                    resultDelivered = true
                    setResult(RESULT_OK, Intent().putExtra(Intents.Scan.RESULT, result.text)
                        .putExtra(Intents.Scan.RESULT_FORMAT, result.barcodeFormat.toString()))
                    finish()
                }
            })
        }
        val updating = intent.getBooleanExtra(EXTRA_UPDATING, false)
        val flashAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        setContent {
            BridgeTheme("Dark") {
                ScannerScreen(updating, frame, flashAvailable, torch, error,
                    camera = { AndroidView(factory = { camera }, modifier = Modifier.fillMaxSize()) },
                    close = { finish() }, toggleTorch = { torch = !torch; camera.setTorch(torch) },
                    retry = {
                        if (!hasCameraPermission) permission.launch(Manifest.permission.CAMERA)
                        else { error = null; camera.pause(); camera.resume() }
                    })
            }
        }
    }
    override fun onResume() {
        super.onResume()
        hasCameraPermission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (hasCameraPermission) { camera.resume(); camera.setTorch(torch) }
        else error = "相机权限未开启"
    }
    override fun onPause() { camera.pause(); super.onPause() }
    companion object { const val EXTRA_UPDATING = "win2mobile.updateAddress" }
}

/** Production scanner chrome; camera slot also permits an isolated design preview. */
@Composable internal fun ScannerScreen(
    updating: Boolean, framePixels: Int, flashAvailable: Boolean, torch: Boolean, error: String?,
    camera: @Composable () -> Unit, close: () -> Unit, toggleTorch: () -> Unit, retry: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "scan")
    val progress by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse), label = "scanLine")
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().background(Color(0xFF10121E))) {
        camera()
        Canvas(Modifier.fillMaxSize()) {
            val side = minOf(framePixels.toFloat(), size.width * .8f, size.height * .4f)
            val x = (size.width - side) / 2; val y = (size.height - side) / 2
            val radius = with(density) { 28.dp.toPx() }
            val hole = androidx.compose.ui.geometry.RoundRect(x, y, x + side, y + side, CornerRadius(radius))
            val mask = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)); addRoundRect(hole)
            }
            drawPath(mask, Color.Black.copy(alpha = .62f))
            drawRoundRect(Color(0xFFAFA7FF), Offset(x, y), Size(side, side), CornerRadius(radius), style = Stroke(3.dp.toPx()))
            if (error == null) {
                val lineY = y + side * (.12f + .76f * progress)
                drawLine(Color(0xFF75E4D0).copy(alpha = .9f), Offset(x + radius, lineY), Offset(x + side - radius, lineY), 2.dp.toPx())
            }
        }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(20.dp)) {
            TextButton(onClick = close, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("‹  返回") }
            Spacer(Modifier.height(18.dp))
            Text(if (updating) "更新电脑地址" else "扫码连接电脑", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Text(if (updating) "扫描同一电脑的新二维码，授权和消息将保留。" else "打开电脑上的 Win2Mobile，点击「连接手机」。",
                style = MaterialTheme.typography.bodyLarge, color = Color(0xFFDEDBF0))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(error ?: "将二维码放入框内，即可自动识别", color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            if (error != null) Button(onClick = retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试 / 允许相机") }
            else if (flashAvailable) FilledTonalButton(onClick = toggleTorch, modifier = Modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) {
                Text(if (torch) "关闭手电筒" else "打开手电筒")
            }
            Spacer(Modifier.height(14.dp))
            Text("仅连接你批准的私人设备", style = MaterialTheme.typography.bodySmall, color = Color(0xFFBDB8D2))
        }
    }
}
