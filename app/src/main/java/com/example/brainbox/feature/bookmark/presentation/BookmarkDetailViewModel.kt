package com.example.brainbox.feature.bookmark.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.brainbox.feature.bookmark.domain.model.Bookmark
import com.example.brainbox.feature.bookmark.domain.repository.BookmarkRepository
import com.example.brainbox.feature.bookmark.domain.scheduler.BookmarkProcessingScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun load(id: Long) {
        viewModelScope.launch {
            _bookmark.value = repository.getBookmarkById(id)
        }
    }

    /** 現在表示中のブックマークを再スクレイプ＆AI解析する */
    fun reanalyze() {
        val current = _bookmark.value ?: return
        _isReanalyzing.value = true
        viewModelScope.launch {
            try {
                scheduler.reanalyzeById(current.id, current.url)
                // Workerが非同期で動くため少し待ってからDBを再読み込み
                kotlinx.coroutines.delay(500)
                _bookmark.value = repository.getBookmarkById(current.id)
            } finally {
                _isReanalyzing.value = false
            }
        }
    }
}
