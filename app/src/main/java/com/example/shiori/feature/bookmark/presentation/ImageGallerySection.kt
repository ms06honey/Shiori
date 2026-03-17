package com.example.shiori.feature.bookmark.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.shiori.core.util.ImageActionHelper
import kotlinx.coroutines.launch
import java.io.File

/**
 * ローカル保存済み画像のサムネイル一覧 + 全画面ビューア。
 *
 * - サムネイルをタップ → 全画面ダイアログ表示
 * - 全画面ビューア内で ダウンロード / クリップボードコピー ボタン
 * - ピンチ操作でズーム、ドラッグで移動
 */
@Composable
fun ImageGallerySection(
    imagePaths: List<String>,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit   // Snackbar などへのコールバック
) {
    if (imagePaths.isEmpty()) return

    var fullscreenIndex by remember { mutableIntStateOf(-1) }

    Column(modifier = modifier) {
        Text(
            "保存済み画像 (${imagePaths.size}枚)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            itemsIndexed(imagePaths) { index, path ->
                ImageThumbnailItem(
                    filePath = path,
                    index = index,
                    total = imagePaths.size,
                    onClick = { fullscreenIndex = index }
                )
            }
        }
    }

    // 全画面ビューア
    if (fullscreenIndex >= 0) {
        FullscreenImageViewer(
            imagePaths = imagePaths,
            initialIndex = fullscreenIndex,
            onDismiss = { fullscreenIndex = -1 },
            onMessage = onMessage
        )
    }
}

// ── サムネイルアイテム ─────────────────────────────────────────────────

@Composable
private fun ImageThumbnailItem(
    filePath: String,
    index: Int,
    total: Int,
    onClick: () -> Unit
) {
    val file = remember(filePath) { File(filePath) }
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(if (file.exists()) file else null)
                .crossfade(200)
                .build(),
            contentDescription = "画像 ${index + 1} / $total",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // 番号バッジ
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp)
        ) {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
        }
    }
}

// ── 全画面ビューア ─────────────────────────────────────────────────────

@Composable
private fun FullscreenImageViewer(
    imagePaths: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, imagePaths.lastIndex),
        pageCount = { imagePaths.size }
    )
    val currentIndex = pagerState.currentPage
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isCurrentImageZoomed by remember { mutableStateOf(false) }

    LaunchedEffect(currentIndex) {
        isCurrentImageZoomed = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // ── 画像（左右スワイプ + ピンチズーム + ドラッグ） ────────
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = imagePaths.size > 1 && !isCurrentImageZoomed
            ) { page ->
                ZoomableImage(
                    filePath = imagePaths[page],
                    modifier = Modifier.fillMaxSize(),
                    onZoomedStateChange = { zoomed ->
                        if (page == pagerState.currentPage) {
                            isCurrentImageZoomed = zoomed
                        }
                    }
                )
            }

            // ── 上部バー（閉じる + インジケーター） ────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "閉じる",
                        tint = Color.White
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${currentIndex + 1} / ${imagePaths.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
                Spacer(Modifier.weight(1f))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TopOverlayActionButton(
                        icon = Icons.Default.Download,
                        contentDescription = "画像を保存",
                        onClick = {
                            scope.launch {
                                val ok = ImageActionHelper.downloadToGallery(
                                    context,
                                    imagePaths[currentIndex]
                                )
                                onMessage(if (ok) "ギャラリーに保存しました" else "保存に失敗しました")
                            }
                        }
                    )
                    TopOverlayActionButton(
                        icon = Icons.Default.ContentCopy,
                        contentDescription = "画像をコピー",
                        onClick = {
                            val ok = ImageActionHelper.copyToClipboard(
                                context,
                                imagePaths[currentIndex]
                            )
                            onMessage(if (ok) "クリップボードにコピーしました" else "コピーに失敗しました")
                        }
                    )
                }
            }

            // ── 中央左右ナビゲーション ──────────────────────────────
            if (imagePaths.size > 1) {
                SideOverlayNavigationButton(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp),
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "前の画像",
                    enabled = currentIndex > 0,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(currentIndex - 1) }
                    }
                )

                SideOverlayNavigationButton(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp),
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "次の画像",
                    enabled = currentIndex < imagePaths.lastIndex,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(currentIndex + 1) }
                    }
                )
            }
        }
    }
}

// ── ズーム可能な画像 ──────────────────────────────────────────────────

@Composable
private fun ZoomableImage(
    filePath: String,
    modifier: Modifier = Modifier,
    onZoomedStateChange: (Boolean) -> Unit = {}
) {
    val file = remember(filePath) { File(filePath) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // ページが変わったらリセット
    LaunchedEffect(filePath) {
        scale = 1f; offsetX = 0f; offsetY = 0f
        onZoomedStateChange(false)
    }

    Box(
        modifier = modifier
            .pointerInput(filePath) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f; offsetY = 0f
                    }
                    onZoomedStateChange(scale > 1.02f)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(if (file.exists()) file else null)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        )
    }
}

@Composable
private fun SideOverlayNavigationButton(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.size(52.dp),
        shape = CircleShape,
        color = Color.Black.copy(alpha = if (enabled) 0.42f else 0.22f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        onClick = onClick,
        enabled = enabled
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White.copy(alpha = if (enabled) 0.95f else 0.45f),
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

// ── 上部オーバーレイ用アイコンボタン ─────────────────────────────────

@Composable
private fun TopOverlayActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

