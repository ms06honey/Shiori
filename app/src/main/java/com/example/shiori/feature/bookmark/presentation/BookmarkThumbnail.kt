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
import java.io.File

/**
 * サムネイルコンポーザブル。
 * 優先度: localThumbnailPath（ローカルファイル）> thumbnailUrl（リモート URL）> グラデーション
 */
@Composable
fun BookmarkThumbnail(
    url: String,
    title: String,
    modifier: Modifier = Modifier,
    thumbnailUrl: String = "",
    hasVideo: Boolean = false,
    /** ローカル保存された先頭画像の絶対パス。存在する場合はリモート URL より優先される */
    localThumbnailPath: String = ""
) {
    val shape = MaterialTheme.shapes.medium // 12dp — macOS 風角丸

    // ローカルファイルが存在すればそれを使い、なければリモート URL を使う
    val imageData: Any? = remember(localThumbnailPath, thumbnailUrl) {
        when {
            localThumbnailPath.isNotBlank() -> {
                val file = File(localThumbnailPath)
                if (file.exists()) file else null
            }
            else -> null
        }
    }
    val hasImage = imageData != null || thumbnailUrl.isNotBlank()

    if (hasImage) {
        val context = LocalContext.current
        val imageRequest = remember(localThumbnailPath, thumbnailUrl) {
            val data: Any = imageData ?: thumbnailUrl
            val builder = ImageRequest.Builder(context)
                .data(data)
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .crossfade(300)
            // リモート URL の場合のみヘッダーを付与（File には不要）
            if (imageData == null) {
                builder
                    .addHeader("User-Agent", WebScraper.CHROME_UA)
                    .addHeader("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .addHeader("Referer", url)
            }
            builder.build()
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
            if (hasVideo) {
                VideoBadge(modifier = Modifier.align(Alignment.Center))
            }
        }
    } else {
        Box(modifier = modifier.clip(shape)) {
            GradientThumbnail(
                url = url,
                title = title,
                modifier = Modifier.fillMaxSize()
            )
            if (hasVideo) {
                VideoBadge(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun VideoBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.58f), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "▶",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
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
    // abs(Int.MIN_VALUE) == Int.MIN_VALUE（負値）となるオーバーフローを避けるため
    // ビット AND で符号ビットをゼロにしてから剰余を取る
    return palettes[(url.hashCode() and Int.MAX_VALUE) % palettes.size]
}
