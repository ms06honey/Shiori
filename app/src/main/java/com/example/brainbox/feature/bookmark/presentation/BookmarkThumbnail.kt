package com.example.brainbox.feature.bookmark.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * PDF仕様書 §3.3「サムネイル自動生成」に準拠。
 * URL のハッシュ値から一貫性のあるグラデーション + タイトル頭文字を表示する。
 */
@Composable
fun BookmarkThumbnail(
    url: String,
    title: String,
    modifier: Modifier = Modifier
) {
    val (startColor, endColor) = remember(url) { urlToGradientColors(url) }
    val initial = remember(title) {
        title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "B"
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(startColor, endColor))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

/** URL ハッシュ値 → グラデーション色ペアのマッピング */
private fun urlToGradientColors(url: String): Pair<Color, Color> {
    val palettes = listOf(
        Color(0xFF667EEA) to Color(0xFF764BA2),
        Color(0xFFF093FB) to Color(0xFFF5576C),
        Color(0xFF4FACFE) to Color(0xFF00F2FE),
        Color(0xFF43E97B) to Color(0xFF38F9D7),
        Color(0xFFFDA085) to Color(0xFFF6D365),
        Color(0xFF89F7FE) to Color(0xFF66A6FF),
        Color(0xFFA18CD1) to Color(0xFFFBC2EB),
        Color(0xFFFF9A9E) to Color(0xFFFECFEF),
        Color(0xFF96FBC4) to Color(0xFFF9F586),
        Color(0xFFFC466B) to Color(0xFF3F5EFB),
    )
    return palettes[abs(url.hashCode()) % palettes.size]
}

