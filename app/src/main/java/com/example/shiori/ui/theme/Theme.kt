package com.example.shiori.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// ── macOS Ventura / Sonoma 風カラースキーム ───────────────────────────────

private val MacLightColorScheme = lightColorScheme(
    primary = MacBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F2FF),
    onPrimaryContainer = Color(0xFF003A70),
    secondary = MacIndigo,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEECFF),
    onSecondaryContainer = Color(0xFF1B1340),
    tertiary = MacTeal,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD4F7FF),
    onTertiaryContainer = Color(0xFF003544),
    background = MacBackground,
    onBackground = MacOnSurface,
    surface = MacSurface,
    onSurface = MacOnSurface,
    surfaceVariant = MacSurfaceVariant,
    onSurfaceVariant = MacOnSurfaceVariant,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFC),
    surfaceContainer = MacSurfaceVariant,
    surfaceContainerHigh = Color(0xFFECECF0),
    surfaceContainerHighest = Color(0xFFE5E5EA),
    outline = MacOutline,
    outlineVariant = MacDivider,
    error = MacRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFE5E5),
    onErrorContainer = Color(0xFF5A0000),
)

private val MacDarkColorScheme = darkColorScheme(
    primary = MacBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF003A70),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = MacIndigoDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF3B3680),
    onSecondaryContainer = Color(0xFFE4DFFF),
    tertiary = MacTealDark,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF004D62),
    onTertiaryContainer = Color(0xFFBDECFF),
    background = MacBackgroundDark,
    onBackground = MacOnSurfaceDark,
    surface = MacSurfaceDark,
    onSurface = MacOnSurfaceDark,
    surfaceVariant = MacSurfaceVariantDark,
    onSurfaceVariant = MacOnSurfaceVariantDark,
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = MacSurfaceVariantDark,
    surfaceContainerHigh = Color(0xFF3A3A3C),
    surfaceContainerHighest = Color(0xFF48484A),
    outline = MacOutlineDark,
    outlineVariant = MacDividerDark,
    error = MacRedDark,
    onError = Color.White,
    errorContainer = Color(0xFF5A0000),
    onErrorContainer = Color(0xFFFFD6D6),
)

/**
 * macOS Sonoma 風の角丸設計。
 * macOS のウインドウ角丸 (10pt)、シート (12pt)、ボタン (8pt) に相当する。
 */
val MacShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),   // 小チップ・バッジ
    small      = RoundedCornerShape(8.dp),   // ボタン
    medium     = RoundedCornerShape(12.dp),  // カード・テキストフィールド
    large      = RoundedCornerShape(16.dp),  // ダイアログ・ボトムシート
    extraLarge = RoundedCornerShape(20.dp),  // フルスクリーンシート
)

@Composable
fun ShioriTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // macOS 風の独自パレットを使うため dynamic color は OFF
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> MacDarkColorScheme
        else -> MacLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = MacShapes,
        content = content
    )
}