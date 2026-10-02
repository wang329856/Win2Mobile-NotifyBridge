package com.notifforward.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val LightBridgeColors = lightColorScheme(
    primary = Color(0xFF635BDF), onPrimary = Color.White, primaryContainer = Color(0xFFE9E6FF), onPrimaryContainer = Color(0xFF272344),
    secondary = Color(0xFF087D72), onSecondary = Color.White, secondaryContainer = Color(0xFFD0F4EA), onSecondaryContainer = Color(0xFF00382F),
    tertiary = Color(0xFF9C5B08), tertiaryContainer = Color(0xFFFFE6BA), onTertiaryContainer = Color(0xFF452B00),
    background = Color(0xFFF5F5FA), surface = Color(0xFFFCFBFF), surfaceContainer = Color(0xFFF0EEF7),
    surfaceContainerLow = Color.White, surfaceContainerHigh = Color(0xFFEAE7F2), onSurface = Color(0xFF202132), onSurfaceVariant = Color(0xFF65677B),
    outlineVariant = Color(0xFFE4E3EE), error = Color(0xFFBA344A)
)
private val DarkBridgeColors = darkColorScheme(
    primary = Color(0xFFBFC2FF), onPrimary = Color(0xFF242144), primaryContainer = Color(0xFF333052), onPrimaryContainer = Color(0xFFE9E6FF),
    secondary = Color(0xFF6CDCC9), secondaryContainer = Color(0xFF124B42), onSecondaryContainer = Color(0xFFB9F4E5),
    tertiary = Color(0xFFF0C27D), tertiaryContainer = Color(0xFF5C421D), onTertiaryContainer = Color(0xFFFFE6BA),
    background = Color(0xFF12131B), surface = Color(0xFF1D1F2A), surfaceContainer = Color(0xFF232532),
    surfaceContainerLow = Color(0xFF1D1F2A), surfaceContainerHigh = Color(0xFF2B2E3C), onSurface = Color(0xFFF0F0FA), onSurfaceVariant = Color(0xFFB1B3C6),
    outlineVariant = Color(0xFF36394B), error = Color(0xFFFFADB7)
)
@Composable fun BridgeTheme(mode: String = "System", content: @Composable () -> Unit) {
    val dark = mode == "Dark" || (mode == "System" && isSystemInDarkTheme())
    val typography = Typography().let { it.copy(headlineLarge = it.headlineLarge.copy(fontWeight = FontWeight.Bold), headlineSmall = it.headlineSmall.copy(fontWeight = FontWeight.Bold), titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold)) }
    MaterialTheme(colorScheme = if (dark) DarkBridgeColors else LightBridgeColors, typography = typography,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)), content = content)
}
