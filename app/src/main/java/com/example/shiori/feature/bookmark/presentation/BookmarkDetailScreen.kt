package com.example.shiori.feature.bookmark.presentation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shiori.core.util.formatDate
import com.example.shiori.feature.bookmark.domain.model.Bookmark
import com.example.shiori.feature.bookmark.domain.model.toMarkdown
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookmarkDetailScreen(
    bookmarkId: Long,
    onNavigateBack: () -> Unit,
    viewModel: BookmarkDetailViewModel = hiltViewModel()
) {
    val bookmark by viewModel.bookmark.collectAsStateWithLifecycle()
    val isReanalyzing by viewModel.isReanalyzing.collectAsStateWithLifecycle()
    val showMemoDialog by viewModel.showMemoDialog.collectAsStateWithLifecycle()
    val editingMemo by viewModel.editingMemo.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(bookmarkId) { viewModel.load(bookmarkId) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("詳細", style = MaterialTheme.typography.titleMedium) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    val b = bookmark ?: return@TopAppBar
                    // MD コピーボタン
                    IconButton(onClick = {
                        clipboardManager.setText(AnnotatedString(b.toMarkdown()))
                        scope.launch { snackbarHostState.showSnackbar("MDをコピーしました") }
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "MDとしてコピー")
                    }
                    IconButton(onClick = { viewModel.reanalyze() }, enabled = !isReanalyzing) {
                        if (isReanalyzing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = "再解析")
                    }
                    IconButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(b.url)))
                    }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "ブラウザで開く")
                    }
                }
            )
        }
    ) { innerPadding ->
        val b = bookmark
        if (b == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            DetailContent(
                bookmark = b,
                isReanalyzing = isReanalyzing,
                onReanalyze = { viewModel.reanalyze() },
                onOpenMemoDialog = { viewModel.openMemoDialog() },
                onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                modifier = Modifier.padding(innerPadding)
            )
        }
    }

    if (showMemoDialog) {
        MemoEditDialog(
            memoText = editingMemo,
            onMemoChange = viewModel::onMemoTextChange,
            onConfirm = { viewModel.saveMemo() },
            onDismiss = { viewModel.dismissMemoDialog() }
        )
    }
}

// ── 詳細コンテンツ ──────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(
    bookmark: Bookmark,
    isReanalyzing: Boolean,
    onReanalyze: () -> Unit,
    onOpenMemoDialog: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── ヘッダー（サムネイル + タイトル + URL） ──────────────────
        MacSection {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                BookmarkThumbnail(
                    url = bookmark.url,
                    title = bookmark.title,
                    thumbnailUrl = bookmark.thumbnailUrl,
                    hasVideo = bookmark.localVideoPath.isNotBlank() || bookmark.videoUrl.isNotBlank(),
                    localThumbnailPath = bookmark.localImagePaths.firstOrNull() ?: "",
                    modifier = Modifier.size(72.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        bookmark.title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        bookmark.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (bookmark.category.isNotBlank()) {
                            MacTagBadge(text = bookmark.category, isPrimary = true)
                        }
                        Text(
                            formatDate(bookmark.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── AI サマリー ─────────────────────────────────────────────
        MacSection {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "AI サマリー",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = onReanalyze, enabled = !isReanalyzing, modifier = Modifier.size(30.dp)) {
                        if (isReanalyzing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(6.dp))
                when {
                    isReanalyzing -> Text(
                        "AIが解析中…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    bookmark.summary.isNotBlank() -> Text(
                        bookmark.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                    )
                    else -> Text(
                        "サマリーがありません。再解析ボタンで取得できます。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // ── タグ ────────────────────────────────────────────────────
        if (bookmark.tags.isNotEmpty()) {
            MacSection {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "タグ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        bookmark.tags.forEach { tag ->
                            MacTagBadge(text = tag, isPrimary = false)
                        }
                    }
                }
            }
        }

        // ── ユーザーメモ ────────────────────────────────────────────
        MacSection {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "メモ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = onOpenMemoDialog, modifier = Modifier.size(30.dp)) {
                        Icon(
                            if (bookmark.userMemo.isBlank()) Icons.AutoMirrored.Filled.NoteAdd
                            else Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (bookmark.userMemo.isNotBlank()) {
                    Text(bookmark.userMemo, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        "メモがありません。編集ボタンで追加できます。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // ── 保存済み画像ギャラリー ──────────────────────────────────
        if (bookmark.localImagePaths.isNotEmpty()) {
            MacSection {
                Column(modifier = Modifier.padding(16.dp)) {
                    ImageGallerySection(
                        imagePaths = bookmark.localImagePaths,
                        onMessage = onMessage
                    )
                }
            }
        }

        // ── 動画 ────────────────────────────────────────────────────
        if (bookmark.videoUrl.isNotBlank() || bookmark.localVideoPath.isNotBlank()) {
            MacSection {
                Column(modifier = Modifier.padding(16.dp)) {
                    val playableUri = remember(bookmark.localVideoPath, bookmark.videoUrl) {
                        when {
                            bookmark.localVideoPath.isNotBlank() -> Uri.fromFile(File(bookmark.localVideoPath))
                            bookmark.videoUrl.isNotBlank() -> Uri.parse(bookmark.videoUrl)
                            else -> null
                        }
                    }

                    Text(
                        "動画",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (bookmark.localVideoPath.isNotBlank()) {
                            "ローカル保存済みの動画を再生できます。"
                        } else {
                            "動画本体のローカル保存に失敗したため、リモート動画を再生します。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    playableUri?.let { uri ->
                        BookmarkVideoPlayer(videoUri = uri)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val videoUri = when {
                                    bookmark.localVideoPath.isNotBlank() -> FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        File(bookmark.localVideoPath)
                                    )
                                    else -> playableUri ?: return@OutlinedButton
                                }
                                val typedIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(
                                        videoUri,
                                        if ((bookmark.videoUrl.ifBlank { videoUri.toString() }).contains(".m3u8", ignoreCase = true)) {
                                            "application/x-mpegURL"
                                        } else {
                                            "video/*"
                                        }
                                    )
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val fallbackIntent = Intent(Intent.ACTION_VIEW, videoUri).apply {
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }

                                runCatching { context.startActivity(typedIntent) }
                                    .recoverCatching { context.startActivity(fallbackIntent) }
                                    .onFailure { onMessage("動画を開けませんでした") }
                            },
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("外部プレイヤーで開く")
                        }
                    }
                    if (bookmark.videoUrl.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            bookmark.videoUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── macOS 風セクションカード（白背景 + 角丸 + 軽い影） ───────────────────

@Composable
private fun MacSection(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 1.dp,
                shape = MaterialTheme.shapes.medium,
                ambientColor = Color.Black.copy(alpha = 0.06f)
            )
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        content()
    }
}

// ── メモ編集ダイアログ ──────────────────────────────────────────────────

@Composable
private fun MemoEditDialog(
    memoText: String,
    onMemoChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text("メモを編集") },
        text = {
            OutlinedTextField(
                value = memoText,
                onValueChange = onMemoChange,
                placeholder = { Text("自由にメモを入力してください…") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                shape = MaterialTheme.shapes.medium,
                maxLines = 8,
                minLines = 4
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}
