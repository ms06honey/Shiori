package com.example.brainbox.feature.bookmark.presentation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.brainbox.core.util.formatDate
import com.example.brainbox.feature.bookmark.domain.model.Bookmark

@OptIn(ExperimentalMaterial3Api::class)
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

    LaunchedEffect(bookmarkId) { viewModel.load(bookmarkId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("詳細") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    val currentBookmark = bookmark
                    if (currentBookmark != null) {
                        // 再解析ボタン
                        IconButton(
                            onClick = { viewModel.reanalyze() },
                            enabled = !isReanalyzing
                        ) {
                            if (isReanalyzing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "再解析")
                            }
                        }
                        IconButton(onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(currentBookmark.url))
                            )
                        }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "ブラウザで開く")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        val currentBookmark = bookmark
        if (currentBookmark == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            DetailContent(
                bookmark = currentBookmark,
                isReanalyzing = isReanalyzing,
                onReanalyze = { viewModel.reanalyze() },
                onOpenMemoDialog = { viewModel.openMemoDialog() },
                modifier = Modifier.padding(innerPadding)
            )
        }
    }

    // メモ編集ダイアログ
    if (showMemoDialog) {
        MemoEditDialog(
            memoText = editingMemo,
            onMemoChange = viewModel::onMemoTextChange,
            onConfirm = { viewModel.saveMemo() },
            onDismiss = { viewModel.dismissMemoDialog() }
        )
    }
}

@Composable
private fun DetailContent(
    bookmark: Bookmark,
    isReanalyzing: Boolean,
    onReanalyze: () -> Unit,
    onOpenMemoDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // サムネイル + タイトル
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BookmarkThumbnail(
                url = bookmark.url,
                title = bookmark.title,
                thumbnailUrl = bookmark.thumbnailUrl,
                modifier = Modifier.size(72.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(bookmark.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    bookmark.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2
                )
            }
        }

        // カテゴリ + 日付
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (bookmark.category.isNotBlank()) {
                SuggestionChip(onClick = {}, label = { Text(bookmark.category) })
            }
            Text(
                formatDate(bookmark.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // AI サマリー
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AI サマリー", style = MaterialTheme.typography.labelMedium)
                    IconButton(
                        onClick = onReanalyze,
                        enabled = !isReanalyzing,
                        modifier = Modifier.size(32.dp)
                    ) {
                        if (isReanalyzing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "再解析",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (isReanalyzing) {
                    Text(
                        "AIが解析中...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                    )
                } else if (bookmark.summary.isNotBlank()) {
                    Text(bookmark.summary, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        "サマリーがありません。再解析ボタンで取得できます。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // タグ
        if (bookmark.tags.isNotEmpty()) {
            Text("タグ", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                bookmark.tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text(tag) },
                        leadingIcon = { Icon(Icons.Default.Tag, null, Modifier.size(16.dp)) }
                    )
                }
            }
        }

        // ユーザーメモ
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("メモ", style = MaterialTheme.typography.labelMedium)
                    IconButton(
                        onClick = onOpenMemoDialog,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            if (bookmark.userMemo.isBlank()) Icons.AutoMirrored.Filled.NoteAdd else Icons.Default.Edit,
                            contentDescription = if (bookmark.userMemo.isBlank()) "メモを追加" else "メモを編集",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (bookmark.userMemo.isNotBlank()) {
                    Text(
                        text = bookmark.userMemo,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                } else {
                    Text(
                        text = "メモがありません。編集ボタンで追加できます。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

// ── メモ編集ダイアログ ─────────────────────────────────────────────────────

@Composable
private fun MemoEditDialog(
    memoText: String,
    onMemoChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("メモを編集") },
        text = {
            OutlinedTextField(
                value = memoText,
                onValueChange = onMemoChange,
                placeholder = { Text("自由にメモを入力してください...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                maxLines = 8,
                minLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}
