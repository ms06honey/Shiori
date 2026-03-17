package com.example.shiori.feature.bookmark.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shiori.feature.bookmark.domain.model.Bookmark
import com.example.shiori.feature.bookmark.domain.repository.BookmarkRepository
import com.example.shiori.feature.bookmark.domain.scheduler.BookmarkProcessingScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookmarkDetailViewModel @Inject constructor(
    private val repository: BookmarkRepository,
    private val scheduler: BookmarkProcessingScheduler
) : ViewModel() {

    private val _bookmark = MutableStateFlow<Bookmark?>(null)
    val bookmark: StateFlow<Bookmark?> = _bookmark.asStateFlow()

    private val _isReanalyzing = MutableStateFlow(false)
    val isReanalyzing: StateFlow<Boolean> = _isReanalyzing.asStateFlow()

    /** メモ編集ダイアログの表示状態 */
    private val _showMemoDialog = MutableStateFlow(false)
    val showMemoDialog: StateFlow<Boolean> = _showMemoDialog.asStateFlow()

    /** 編集中のメモテキスト（ダイアログ内で使用） */
    private val _editingMemo = MutableStateFlow("")
    val editingMemo: StateFlow<String> = _editingMemo.asStateFlow()

    private var bookmarkObserveJob: Job? = null

    fun load(id: Long) {
        bookmarkObserveJob?.cancel()
        bookmarkObserveJob = viewModelScope.launch {
            repository.observeBookmarkById(id).collectLatest { b ->
                _bookmark.value = b
                if (!_showMemoDialog.value) {
                    _editingMemo.value = b?.userMemo ?: ""
                }
            }
        }
    }

    /** 現在表示中のブックマークを再スクレイプ＆AI解析する */
    fun reanalyze() {
        val current = _bookmark.value ?: return
        _isReanalyzing.value = true
        viewModelScope.launch {
            try {
                scheduler.reanalyzeById(current.id, current.url)
            } finally {
                _isReanalyzing.value = false
            }
        }
    }

    /** メモ編集ダイアログを開く */
    fun openMemoDialog() {
        _editingMemo.value = _bookmark.value?.userMemo ?: ""
        _showMemoDialog.value = true
    }

    /** メモ編集ダイアログを閉じる */
    fun dismissMemoDialog() {
        _showMemoDialog.value = false
    }

    /** 編集中のメモテキストを更新する */
    fun onMemoTextChange(text: String) {
        _editingMemo.value = text
    }

    /** メモを保存してダイアログを閉じる */
    fun saveMemo() {
        val current = _bookmark.value ?: return
        val memoText = _editingMemo.value
        viewModelScope.launch {
            repository.updateUserMemo(current.id, memoText)
            _bookmark.value = current.copy(userMemo = memoText)
            _showMemoDialog.value = false
        }
    }
}
