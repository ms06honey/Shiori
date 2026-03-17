package com.example.shiori.feature.bookmark.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.shiori.core.scraper.WebScraper
import kotlin.math.abs

/**
 * サムネイルコンポーザブル。
 * - thumbnailUrl が非空 → Coil で実際の画像を読み込む（失敗/読み込み中はグラデーションにフォールバック）
 * - thumbnailUrl が空   → URL ハッシュ値から一貫性のあるグラデーション + タイトル頭文字
 */
@Composable
fun BookmarkThumbnail(
    url: String,
    title: String,
    modifier: Modifier = Modifier,
    thumbnailUrl: String = ""
) {
    val shape = MaterialTheme.shapes.medium // 12dp — macOS 風角丸

    if (thumbnailUrl.isNotBlank()) {
        val context = LocalContext.current
        val imageRequest = remember(thumbnailUrl) {
            ImageRequest.Builder(context)
                .data(thumbnailUrl)
                .addHeader("User-Agent", WebScraper.CHROME_UA)
                .addHeader("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .addHeader("Referer", url)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .crossfade(300)
                .build()
        }

        Box(modifier = modifier.clip(shape)) {
            SubcomposeAsyncImage(
                model = imageRequest,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    GradientThumbnail(url = url, title = title, modifier = Modifier.fillMaxSize())
                },
                error = {
                    GradientThumbnail(url = url, title = title, modifier = Modifier.fillMaxSize())
                }
            )
        }
    } else {
        GradientThumbnail(
            url = url,
            title = title,
            modifier = modifier.clip(shape)
        )
    }
}

/** URL ハッシュ値グラデーション + 頭文字サムネイル */
@Composable
private fun GradientThumbnail(
    url: String,
    title: String,
    modifier: Modifier = Modifier
) {
    val (startColor, endColor) = remember(url) { urlToGradientColors(url) }
    val initial = remember(title) {
        title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "S"
    }

    Box(
        modifier = modifier
            .background(Brush.linearGradient(listOf(startColor, endColor))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleLarge,
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
