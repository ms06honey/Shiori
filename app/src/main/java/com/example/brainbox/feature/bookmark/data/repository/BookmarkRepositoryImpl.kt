package com.example.brainbox.feature.bookmark.data.repository

import com.example.brainbox.feature.bookmark.data.local.BookmarkDao
import com.example.brainbox.feature.bookmark.data.local.BookmarkEntity
import com.example.brainbox.feature.bookmark.domain.model.Bookmark
import com.example.brainbox.feature.bookmark.domain.repository.BookmarkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookmarkRepositoryImpl @Inject constructor(
    private val dao: BookmarkDao
) : BookmarkRepository {

    override fun getAllBookmarks(): Flow<List<Bookmark>> =
        dao.getAllBookmarks().map { it.map(BookmarkEntity::toDomain) }

    override fun getAllCategories(): Flow<List<String>> =
        dao.getAllCategories()

    override fun getBookmarksByCategory(category: String): Flow<List<Bookmark>> =
        dao.getBookmarksByCategory(category).map { it.map(BookmarkEntity::toDomain) }

    override fun searchBookmarks(query: String): Flow<List<Bookmark>> =
        dao.searchBookmarks(query).map { it.map(BookmarkEntity::toDomain) }

    override suspend fun getBookmarkById(id: Long): Bookmark? =
        dao.getBookmarkById(id)?.toDomain()

    override suspend fun saveInitialBookmark(url: String): Long =
        dao.insertBookmark(BookmarkEntity(url = url, title = "読み込み中..."))

    override suspend fun getOrCreatePendingBookmark(url: String): Long =
        dao.getPendingBookmarkByUrl(url)?.id
            ?: dao.insertBookmark(BookmarkEntity(url = url, title = "読み込み中..."))

    override suspend fun updateAiMetadata(
        id: Long, title: String, summary: String, category: String, tags: String
    ) = dao.updateAiMetadata(id, title, summary, category, tags)

    override suspend fun deleteBookmark(id: Long) = dao.deleteBookmarkById(id)

    override suspend fun getAllBookmarksForExport(): List<Bookmark> =
        dao.getAllBookmarksOnce().map(BookmarkEntity::toDomain)
}

// ── マッピング拡張関数 ────────────────────────────────────────────────────

private fun BookmarkEntity.toDomain() = Bookmark(
    id = id,
    url = url,
    title = title,
    summary = summary,
    category = category,
    tags = if (tags.isBlank()) {
        emptyList()
    } else {
        tags.split(",").map(String::trim).filter(String::isNotBlank).distinct()
    },
    createdAt = createdAt
)
