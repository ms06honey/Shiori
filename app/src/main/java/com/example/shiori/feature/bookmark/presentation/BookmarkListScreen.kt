package com.example.shiori.feature.bookmark.presentation

import android.net.Uri
import android.webkit.URLUtil
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shiori.core.util.formatDate
import com.example.shiori.feature.bookmark.domain.model.Bookmark
import com.example.shiori.feature.bookmark.domain.model.parsedAiSummary
import com.example.shiori.feature.bookmark.domain.model.toMarkdown
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookmarkListScreen(
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: BookmarkViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val groupedBookmarks = remember(uiState.bookmarks, uiState.viewMode) {
        buildBookmarkGroups(uiState.bookmarks, uiState.viewMode)
    }
    val visibleGroupKeys = remember(groupedBookmarks) { groupedBookmarks.map(BookmarkGroup::id) }
    val isTreeMode = uiState.viewMode != BookmarkListViewMode.NORMAL
    val allGroupsCollapsed = remember(visibleGroupKeys, uiState.collapsedGroupKeys) {
        visibleGroupKeys.isNotEmpty() && visibleGroupKeys.all { it in uiState.collapsedGroupKeys }
    }
    val allGroupsExpanded = remember(visibleGroupKeys, uiState.collapsedGroupKeys) {
        visibleGroupKeys.isNotEmpty() && visibleGroupKeys.none { it in uiState.collapsedGroupKeys }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> uri?.let { viewModel.performExport(it) } }

    LaunchedEffect(uiState.exportSuccess) {
        if (uiState.exportSuccess) {
            snackbarHostState.showSnackbar("エクスポートしました")
            viewModel.clearExportSuccess()
        }
    }
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var showViewModeMenu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "栞-SHIORI-",
                        style = MaterialTheme.typography.headlineMedium
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                actions = {
                    Box {
                        IconButton(onClick = { showViewModeMenu = true }) {
                            Icon(Icons.Default.AccountTree, contentDescription = "表示形式の切り替え")
                        }
                        DropdownMenu(
                            expanded = showViewModeMenu,
                            onDismissRequest = { showViewModeMenu = false }
                        ) {
                            BookmarkListViewMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (mode == uiState.viewMode) {
                                                Icons.Default.RadioButtonChecked
                                            } else {
                                                Icons.Default.RadioButtonUnchecked
                                            },
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        viewModel.onViewModeSelected(mode)
                                        showViewModeMenu = false
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = {
                        val filename = "shiori_${System.currentTimeMillis()}.json"
                        exportLauncher.launch(filename)
                    }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "エクスポート")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.large
            ) {
                Icon(Icons.Default.Add, contentDescription = "URL追加")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .dismissSelectionMenuOnTap()
        ) {
            // ── 検索バー（macOS Spotlight 風） ───────────────────────
            TextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .shadow(
                        elevation = 2.dp,
                        shape = MaterialTheme.shapes.medium,
                        ambientColor = Color.Black.copy(alpha = 0.08f)
                    )
                    .clip(MaterialTheme.shapes.medium),
                placeholder = {
                    Text(
                        "検索…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotBlank()) {
                        IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                            Icon(Icons.Default.Clear, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge
            )

            // ── カテゴリタブ（macOS ピル型セグメント風） ──────────────
            if (uiState.categories.isNotEmpty()) {
                val allTabs = listOf("すべて") + uiState.categories
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    itemsIndexed(allTabs) { index, label ->
                        MacPillChip(
                            label = label,
                            selected = uiState.selectedCategoryIndex == index,
                            onClick = { viewModel.onCategorySelected(index) }
                        )
                    }
                }
            }

            if (isTreeMode && groupedBookmarks.isNotEmpty()) {
                TreeBulkActions(
                    allExpanded = allGroupsExpanded,
                    allCollapsed = allGroupsCollapsed,
                    onExpandAll = { viewModel.expandAllGroups(visibleGroupKeys) },
                    onCollapseAll = { viewModel.collapseAllGroups(visibleGroupKeys) }
                )
            }

            // ── ブックマーク一覧 ────────────────────────────────────
            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (uiState.bookmarks.isEmpty()) {
                EmptyState(Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (uiState.viewMode == BookmarkListViewMode.NORMAL) {
                        items(uiState.bookmarks, key = { it.id }) { bookmark ->
                            BookmarkCard(
                                bookmark = bookmark,
                                onClick = { onNavigateToDetail(bookmark.id) },
                                onDelete = { viewModel.deleteBookmark(bookmark.id) },
                                onCopyMarkdown = {
                                    clipboardManager.setText(AnnotatedString(bookmark.toMarkdown()))
                                    scope.launch { snackbarHostState.showSnackbar("MDをコピーしました") }
                                }
                            )
                        }
                    } else {
                        groupedBookmarks.forEach { group ->
                            val isExpanded = group.id !in uiState.collapsedGroupKeys
                            item(key = "group_${group.id}") {
                                BookmarkGroupHeader(
                                    title = group.title,
                                    count = group.bookmarks.size,
                                    expanded = isExpanded,
                                    onClick = { viewModel.toggleGroup(group.id) }
                                )
                            }
                            if (isExpanded) {
                                items(group.bookmarks, key = { "${group.id}_${it.id}" }) { bookmark ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp)
                                    ) {
                                        BookmarkCard(
                                            bookmark = bookmark,
                                            onClick = { onNavigateToDetail(bookmark.id) },
                                            onDelete = { viewModel.deleteBookmark(bookmark.id) },
                                            onCopyMarkdown = {
                                                clipboardManager.setText(AnnotatedString(bookmark.toMarkdown()))
                                                scope.launch { snackbarHostState.showSnackbar("MDをコピーしました") }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // 末尾にFABと被らないようスペーサー
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showAddDialog) {
        AddUrlDialog(
            onConfirm = { url -> viewModel.enqueueUrl(url); showAddDialog = false },
            onDismiss = { showAddDialog = false }
        )
    }
}

// ── macOS ピル型セグメントチップ ──────────────────────────────────────────

@Composable
private fun TreeBulkActions(
    allExpanded: Boolean,
    allCollapsed: Boolean,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiniIconAction(
            icon = Icons.Default.UnfoldMore,
            label = "全部開く",
            enabled = !allExpanded,
            onClick = onExpandAll
        )
        Spacer(Modifier.width(6.dp))
        MiniIconAction(
            icon = Icons.Default.UnfoldLess,
            label = "全部閉じる",
            enabled = !allCollapsed,
            onClick = onCollapseAll
        )
    }
}

@Composable
private fun MiniIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(14.dp),
                tint = contentColor
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor
            )
        }
    }
}

@Composable
private fun BookmarkGroupHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${count}件",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MacPillChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bgColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary
        else Color.Transparent,
        label = "chipBg"
    )
    val textColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "chipText"
    )
    val shape = RoundedCornerShape(50)

    Box(
        modifier = Modifier
            .clip(shape)
            .then(
                if (!selected) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
                else Modifier
            )
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = textColor)
    }
}

// ── ブックマークカード（macOS 風 frost glass カード） ─────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookmarkCard(
    bookmark: Bookmark,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onCopyMarkdown: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val aiSummary = remember(bookmark.summary) { bookmark.parsedAiSummary() }
    val sourceLabel = remember(bookmark.url) { bookmark.sourceDisplayLabel() }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                ambientColor = Color.Black.copy(alpha = 0.06f)
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            BookmarkThumbnail(
                url = bookmark.url,
                title = bookmark.title,
                thumbnailUrl = bookmark.thumbnailUrl,
                hasVideo = bookmark.localVideoPath.isNotBlank() || bookmark.videoUrl.isNotBlank(),
                localThumbnailPath = bookmark.localImagePaths.firstOrNull() ?: "",
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                SelectionContainer {
                    Text(
                        text = bookmark.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (aiSummary.overview.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            text = aiSummary.overview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // タグ + カテゴリ + 日付 ── FlowRow で横幅に収まらない場合に折り返す
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (bookmark.category.isNotBlank()) {
                        MacTagBadge(text = bookmark.category, isPrimary = true)
                    }
                    bookmark.tags.forEach { tag ->
                        MacTagBadge(text = tag, isPrimary = false)
                    }
                    SelectionContainer {
                        Text(
                            text = formatDate(bookmark.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
                if (sourceLabel.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "取得元: $sourceLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            // アクションボタン列（コピー + 削除）
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // MD コピーボタン
                IconButton(
                    onClick = onCopyMarkdown,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "MDとしてコピー",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 削除ボタン
                IconButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "削除",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            shape = MaterialTheme.shapes.large,
            title = { Text("ブックマークを削除") },
            text = { Text("「${bookmark.title}」を削除してもよろしいですか？") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("キャンセル") }
            }
        )
    }
}

// ── macOS 風タグバッジ ──────────────────────────────────────────────────

@Composable
fun MacTagBadge(text: String, isPrimary: Boolean) {
    val bg = if (isPrimary)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isPrimary)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        SelectionContainer {
            Text(text = text, style = MaterialTheme.typography.labelSmall, color = fg)
        }
    }
}

// ── 空状態 ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.BookmarkBorder,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "ブックマークがありません",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "ブラウザや X の「共有」から追加できます",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

// ── URL 追加ダイアログ ──────────────────────────────────────────────────

@Composable
private fun AddUrlDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    val normalizedUrl = remember(url) {
        url.trim().takeIf { URLUtil.isHttpUrl(it) || URLUtil.isHttpsUrl(it) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text("URLを追加") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text("https://...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            )
        },
        confirmButton = {
            TextButton(
                onClick = { normalizedUrl?.let(onConfirm) },
                enabled = normalizedUrl != null
            ) { Text("追加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}
