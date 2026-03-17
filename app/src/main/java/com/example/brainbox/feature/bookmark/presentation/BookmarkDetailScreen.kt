package com.example.brainbox.feature.bookmark.presentation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

@Composable
private fun DetailContent(bookmark: Bookmark, modifier: Modifier = Modifier) {
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
        if (bookmark.summary.isNotBlank()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("AI サマリー", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(bookmark.summary, style = MaterialTheme.typography.bodyMedium)
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
    }
}
