package com.example.shiori.feature.bookmark.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val exportSuccess: Boolean = false
)

@HiltViewModel
class BookmarkViewModel @Inject constructor(
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
        observeCategories()
        observeBookmarks()
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
