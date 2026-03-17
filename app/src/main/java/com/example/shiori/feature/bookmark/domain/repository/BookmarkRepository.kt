package com.example.shiori.feature.bookmark.domain.repository

import com.example.shiori.feature.bookmark.domain.model.Bookmark
import kotlinx.coroutines.flow.Flow

interface BookmarkRepository {
    fun getAllBookmarks(): Flow<List<Bookmark>>
    fun getAllCategories(): Flow<List<String>>
    fun getBookmarksByCategory(category: String): Flow<List<Bookmark>>
    fun searchBookmarks(query: String): Flow<List<Bookmark>>
    suspend fun getBookmarkById(id: Long): Bookmark?
    /** Share Intent 受信直後に placeholder を保存し ID を返す（Worker が使用） */
    suspend fun saveInitialBookmark(url: String): Long
    /**
     * リトライ重複防止版: 同じ URL の未処理ブックマーク(title="読み込み中...")が
     * 既に存在すればその ID を、なければ新規作成して ID を返す。
     * WorkManager がリトライするたびに余分なエントリが増えるのを防ぐ。
     */
    suspend fun getOrCreatePendingBookmark(url: String): Long
    /** Worker の AI 解析完了後に呼ぶ */
    suspend fun updateAiMetadata(
        id: Long,
        title: String,
        summary: String,
        category: String,
        tags: String,
        thumbnailUrl: String = "",
        videoUrl: String = "",
        localVideoPath: String = "",
        localImagePaths: String = ""
    )
    /** 再解析のため既存ブックマークを "読み込み中..." 状態にリセットする */
    suspend fun resetBookmarkToProcessing(id: Long)
    suspend fun deleteBookmark(id: Long)
    /** JSON エクスポート用 */
    suspend fun getAllBookmarksForExport(): List<Bookmark>
    /** ユーザーメモを更新する */
    suspend fun updateUserMemo(id: Long, memo: String)
}
