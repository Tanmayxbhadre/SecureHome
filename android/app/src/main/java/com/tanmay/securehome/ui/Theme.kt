package com.tanmay.securehome.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Same tokens as relay/public/style.css.
private val Light = lightColorScheme(
    primary = Color(0xFF1D4ED8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEFF6FF),
    onPrimaryContainer = Color(0xFF1E40AF),
    secondaryContainer = Color(0xFFEFF6FF),
    onSecondaryContainer = Color(0xFF1E40AF),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF15181E),
    surface = Color(0xFFF6F7F9),
    onSurface = Color(0xFF15181E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF0F1F4),
    onSurfaceVariant = Color(0xFF5D6572),
    outline = Color(0xFFC9CED6),
    outlineVariant = Color(0xFFE3E6EB),
    error = Color(0xFFB42318),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF3B82F6),
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF1E293B),
    onPrimaryContainer = Color(0xFF93C5FD),
    secondaryContainer = Color(0xFF1E293B),
    onSecondaryContainer = Color(0xFF93C5FD),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFECEEF2),
    surface = Color(0xFF0F1115),
    onSurface = Color(0xFFECEEF2),
    surfaceContainerLowest = Color(0xFF171A20),
    surfaceContainerLow = Color(0xFF171A20),
    surfaceContainer = Color(0xFF171A20),
    surfaceContainerHigh = Color(0xFF1E222A),
    onSurfaceVariant = Color(0xFF9AA3B2),
    outline = Color(0xFF3A404B),
    outlineVariant = Color(0xFF2A2F38),
    error = Color(0xFFFF7B72),
)

data class Badge(val bg: Color, val fg: Color)

@Composable
fun badgeFor(kind: String): Badge {
    val dark = isSystemInDarkTheme()
    return when (kind) {
        "delivery" -> if (dark) Badge(Color(0xFF1D2740), Color(0xFFA9C1FF)) else Badge(Color(0xFFEAF0FB), Color(0xFF2B4C9B))
        "ride" -> if (dark) Badge(Color(0xFF17302A), Color(0xFF8FDCB6)) else Badge(Color(0xFFE5F4EC), Color(0xFF126340))
        else -> if (dark) Badge(Color(0xFF1E293B), Color(0xFF93C5FD)) else Badge(Color(0xFFEFF6FF), Color(0xFF1E40AF))
    }
}

@Composable
fun SecureHomeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** The SecureHome mark: a house with a central security shield (relay/public/mark.svg). */
@Composable
fun Mark(size: Dp = 28.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 32f
        drawRoundRect(Color(0xFF1D4ED8), cornerRadius = CornerRadius(8 * u))
        val house = Path().apply {
            moveTo(16 * u, 6.5f * u)
            lineTo(6.5f * u, 14.2f * u)
            lineTo(9.5f * u, 14.2f * u)
            lineTo(9.5f * u, 25 * u)
            lineTo(22.5f * u, 25 * u)
            lineTo(22.5f * u, 14.2f * u)
            lineTo(25.5f * u, 14.2f * u)
            close()
        }
        drawPath(house, Color.White)
        val shield = Path().apply {
            moveTo(16 * u, 13.5f * u)
            lineTo(19.5f * u, 14.2f * u)
            lineTo(19.5f * u, 18.2f * u)
            quadraticTo(19.5f * u, 20.8f * u, 16 * u, 22.3f * u)
            quadraticTo(12.5f * u, 20.8f * u, 12.5f * u, 18.2f * u)
            lineTo(12.5f * u, 14.2f * u)
            close()
        }
        drawPath(shield, Color(0xFF1D4ED8))
        drawCircle(Color.White, 1.1f * u, Offset(16 * u, 16.8f * u))
        drawLine(Color.White, Offset(16 * u, 17.5f * u), Offset(16 * u, 19.5f * u), strokeWidth = 1.1f * u, cap = StrokeCap.Round)
    }
}

val CardShape = RoundedCornerShape(14.dp)
