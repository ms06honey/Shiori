package com.example.shiori.feature.bookmark.data.repository

import com.example.shiori.core.util.LocalImageStore
import com.example.shiori.core.util.LocalVideoStore
import com.example.shiori.feature.bookmark.data.local.BookmarkDao
import com.example.shiori.feature.bookmark.data.local.BookmarkEntity
import com.example.shiori.feature.bookmark.domain.model.Bookmark
import com.example.shiori.feature.bookmark.domain.repository.BookmarkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookmarkRepositoryImpl @Inject constructor(
    private val dao: BookmarkDao,
    private val localImageStore: LocalImageStore,
    private val localVideoStore: LocalVideoStore
) : BookmarkRepository {

    override fun getAllBookmarks(): Flow<List<Bookmark>> =
        dao.getAllBookmarks().map { it.map(BookmarkEntity::toDomain) }

    override fun getAllCategories(): Flow<List<String>> =
        dao.getAllCategories()

    override fun getBookmarksByCategory(category: String): Flow<List<Bookmark>> =
        dao.getBookmarksByCategory(category).map { it.map(BookmarkEntity::toDomain) }

    override fun searchBookmarks(query: String): Flow<List<Bookmark>> =
        dao.searchBookmarks(query).map { it.map(BookmarkEntity::toDomain) }

    override fun observeBookmarkById(id: Long): Flow<Bookmark?> =
        dao.observeBookmarkById(id).map { it?.toDomain() }

    override suspend fun getBookmarkById(id: Long): Bookmark? =
        dao.getBookmarkById(id)?.toDomain()

    override suspend fun saveInitialBookmark(url: String): Long =
        dao.insertBookmark(BookmarkEntity(url = url, title = "読み込み中..."))

    override suspend fun getOrCreatePendingBookmark(url: String): Long =
        dao.getPendingBookmarkByUrl(url)?.id
            ?: dao.insertBookmark(BookmarkEntity(url = url, title = "読み込み中..."))

    override suspend fun updateAiMetadata(
        id: Long, title: String, summary: String, category: String, tags: String,
        sourcePackage: String, sourceAppName: String,
        thumbnailUrl: String, videoUrl: String, localVideoPath: String, localImagePaths: String
    ) = dao.updateAiMetadata(
        id,
        title,
        summary,
        category,
        tags,
        sourcePackage,
        sourceAppName,
        thumbnailUrl,
        videoUrl,
        localVideoPath,
        localImagePaths
    )

    override suspend fun resetBookmarkToProcessing(id: Long) {
        // 再解析時は旧ローカル画像を削除（新しい画像で上書き）
        localImageStore.deleteAll(id)
        localVideoStore.deleteAll(id)
        dao.resetToProcessing(id)
    }

    override suspend fun deleteBookmark(id: Long) {
        // ブックマーク削除時にローカル保存画像も削除
        localImageStore.deleteAll(id)
        localVideoStore.deleteAll(id)
        dao.deleteBookmarkById(id)
    }

    override suspend fun getAllBookmarksForExport(): List<Bookmark> =
        dao.getAllBookmarksOnce().map(BookmarkEntity::toDomain)

    override suspend fun updateUserMemo(id: Long, memo: String) =
        dao.updateUserMemo(id, memo)
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
    createdAt = createdAt,
    sourcePackage = sourcePackage,
    sourceAppName = sourceAppName,
    userMemo = userMemo,
    thumbnailUrl = thumbnailUrl,
    videoUrl = videoUrl,
    localVideoPath = localVideoPath,
    localImagePaths = if (localImagePaths.isBlank()) {
        emptyList()
    } else {
        localImagePaths.split(",").map(String::trim).filter(String::isNotBlank)
    }
)


