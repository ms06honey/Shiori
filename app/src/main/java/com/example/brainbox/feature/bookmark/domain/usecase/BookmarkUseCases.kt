package com.example.brainbox.feature.bookmark.domain.usecase

import android.net.Uri
import com.example.brainbox.feature.bookmark.domain.export.BookmarkExporter
import com.example.brainbox.feature.bookmark.domain.model.Bookmark
import com.example.brainbox.feature.bookmark.domain.repository.BookmarkRepository
import com.example.brainbox.feature.bookmark.domain.scheduler.BookmarkProcessingScheduler
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetBookmarksUseCase @Inject constructor(private val repository: BookmarkRepository) {
    operator fun invoke(): Flow<List<Bookmark>> = repository.getAllBookmarks()
}

class GetAllCategoriesUseCase @Inject constructor(private val repository: BookmarkRepository) {
    operator fun invoke(): Flow<List<String>> = repository.getAllCategories()
}

class GetBookmarksByCategoryUseCase @Inject constructor(private val repository: BookmarkRepository) {
    operator fun invoke(category: String): Flow<List<Bookmark>> =
        repository.getBookmarksByCategory(category)
}

class SearchBookmarksUseCase @Inject constructor(private val repository: BookmarkRepository) {
    operator fun invoke(query: String): Flow<List<Bookmark>> = repository.searchBookmarks(query)
}

class DeleteBookmarkUseCase @Inject constructor(private val repository: BookmarkRepository) {
    suspend operator fun invoke(id: Long) = repository.deleteBookmark(id)
}

class GetAllBookmarksForExportUseCase @Inject constructor(private val repository: BookmarkRepository) {
    suspend operator fun invoke(): List<Bookmark> = repository.getAllBookmarksForExport()
}

class EnqueueBookmarkProcessingUseCase @Inject constructor(
    private val scheduler: BookmarkProcessingScheduler
) {
    operator fun invoke(url: String) = scheduler.enqueueUrl(url)
}

class ExportBookmarksUseCase @Inject constructor(
    private val repository: BookmarkRepository,
    private val exporter: BookmarkExporter
) {
    suspend operator fun invoke(uri: Uri) {
        exporter.export(uri, repository.getAllBookmarksForExport())
    }
}

