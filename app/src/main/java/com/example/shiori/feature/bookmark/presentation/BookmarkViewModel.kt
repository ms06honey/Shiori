package com.example.shiori.feature.bookmark.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shiori.core.datastore.EncryptedPrefsManager
import com.example.shiori.feature.bookmark.domain.model.Bookmark
import com.example.shiori.feature.bookmark.domain.usecase.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BookmarkUiState(
    val bookmarks: List<Bookmark> = emptyList(),
    val categories: List<String> = emptyList(),
    val selectedCategoryIndex: Int = 0,   // 0 = "すべて"
    val searchQuery: String = "",
    val viewMode: BookmarkListViewMode = BookmarkListViewMode.NORMAL,
    val collapsedGroupKeys: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val exportSuccess: Boolean = false
)

@HiltViewModel
class BookmarkViewModel @Inject constructor(
    private val encryptedPrefsManager: EncryptedPrefsManager,
    private val getBookmarksUseCase: GetBookmarksUseCase,
    private val getAllCategoriesUseCase: GetAllCategoriesUseCase,
    private val getBookmarksByCategoryUseCase: GetBookmarksByCategoryUseCase,
    private val searchBookmarksUseCase: SearchBookmarksUseCase,
    private val deleteBookmarkUseCase: DeleteBookmarkUseCase,
    private val exportBookmarksUseCase: ExportBookmarksUseCase,
    private val enqueueBookmarkProcessingUseCase: EnqueueBookmarkProcessingUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookmarkUiState(isLoading = true))
    val uiState: StateFlow<BookmarkUiState> = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")
    private val selectedCategory = MutableStateFlow<String?>(null)  // null = すべて

    init {
        restoreViewMode()
        observeCategories()
        observeBookmarks()
    }

    private fun restoreViewMode() {
        val restored = encryptedPrefsManager.getBookmarkListViewModeName()
            ?.let { modeName -> runCatching { BookmarkListViewMode.valueOf(modeName) }.getOrNull() }
            ?: BookmarkListViewMode.NORMAL
        _uiState.update { it.copy(viewMode = restored) }
    }

    private fun observeCategories() {
        viewModelScope.launch {
            getAllCategoriesUseCase().collect { cats ->
                _uiState.update { it.copy(categories = cats) }
            }
        }
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun observeBookmarks() {
        viewModelScope.launch {
            combine(searchQuery.debounce(300), selectedCategory) { q, cat -> q to cat }
                .flatMapLatest { (query, category) ->
                    when {
                        query.isNotBlank() -> searchBookmarksUseCase(query)
                        category != null   -> getBookmarksByCategoryUseCase(category)
                        else               -> getBookmarksUseCase()
                    }
                }
                .catch { e -> _uiState.update { it.copy(errorMessage = e.message) } }
                .collect { bookmarks ->
                    _uiState.update { it.copy(bookmarks = bookmarks, isLoading = false) }
                }
        }
    }

    // ── イベントハンドラ ──────────────────────────────────────────────

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query, selectedCategoryIndex = 0) }
        if (query.isNotBlank()) selectedCategory.value = null
    }

    fun onCategorySelected(index: Int) {
        _uiState.update { it.copy(selectedCategoryIndex = index, searchQuery = "") }
        searchQuery.value = ""
        selectedCategory.value = if (index == 0) null
        else _uiState.value.categories.getOrNull(index - 1)
    }

    fun onViewModeSelected(viewMode: BookmarkListViewMode) {
        _uiState.update { current ->
            if (current.viewMode == viewMode) current
            else current.copy(viewMode = viewMode, collapsedGroupKeys = emptySet())
        }
        encryptedPrefsManager.saveBookmarkListViewModeName(viewMode.name)
    }

    fun toggleGroup(groupKey: String) {
        _uiState.update { current ->
            val collapsed = current.collapsedGroupKeys.toMutableSet()
            if (!collapsed.add(groupKey)) {
                collapsed.remove(groupKey)
            }
            current.copy(collapsedGroupKeys = collapsed)
        }
    }

    fun expandAllGroups(groupKeys: Collection<String>) {
        val visibleKeys = groupKeys.toSet()
        if (visibleKeys.isEmpty()) return
        _uiState.update { current ->
            current.copy(collapsedGroupKeys = current.collapsedGroupKeys - visibleKeys)
        }
    }

    fun collapseAllGroups(groupKeys: Collection<String>) {
        val visibleKeys = groupKeys.toSet()
        if (visibleKeys.isEmpty()) return
        _uiState.update { current ->
            current.copy(collapsedGroupKeys = current.collapsedGroupKeys + visibleKeys)
        }
    }

    /** FAB や URL ダイアログから手動で URL を WorkManager にキュー登録 */
    fun enqueueUrl(url: String) {
        enqueueBookmarkProcessingUseCase(url)
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { deleteBookmarkUseCase(id) }
    }

    /** SAF で選択された Uri に JSON エクスポート */
    fun performExport(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                exportBookmarksUseCase(uri)
                _uiState.update { it.copy(exportSuccess = true) }
            }.onFailure { e ->
                _uiState.update { it.copy(errorMessage = "エクスポート失敗: ${e.message}") }
            }
        }
    }

    fun clearExportSuccess() = _uiState.update { it.copy(exportSuccess = false) }
    fun clearError() = _uiState.update { it.copy(errorMessage = null) }
}
