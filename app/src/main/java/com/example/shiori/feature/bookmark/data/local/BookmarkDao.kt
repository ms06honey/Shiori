package com.example.shiori.feature.bookmark.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    // ── 全件取得（新しい順）──────────────────────────────────────────
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAllBookmarks(): Flow<List<BookmarkEntity>>

    // ── AI 生成カテゴリ一覧（重複排除）──────────────────────────────
    @Query("SELECT DISTINCT category FROM bookmarks WHERE category != '' ORDER BY category")
    fun getAllCategories(): Flow<List<String>>

    // ── カテゴリで絞り込み ──────────────────────────────────────────
    @Query("SELECT * FROM bookmarks WHERE category = :category ORDER BY createdAt DESC")
    fun getBookmarksByCategory(category: String): Flow<List<BookmarkEntity>>

    // ── フリーワード検索（title / summary / tags 対象）──────────────
    @Query(
        """
        SELECT * FROM bookmarks
        WHERE title   LIKE '%' || :query || '%'
           OR summary LIKE '%' || :query || '%'
           OR tags    LIKE '%' || :query || '%'
        ORDER BY createdAt DESC
        """
    )
    fun searchBookmarks(query: String): Flow<List<BookmarkEntity>>

    // ── ID 指定取得（Worker が更新用に使用）─────────────────────────
    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun getBookmarkById(id: Long): BookmarkEntity?

    // ── 挿入（Worker が最初に placeholder を保存）──────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    /**
     * リトライ重複防止用: 同じ URL で title が "読み込み中..." のまま
     * 未処理のブックマークを返す。なければ null。
     */
    @Query("SELECT * FROM bookmarks WHERE url = :url AND title = '読み込み中...' LIMIT 1")
    suspend fun getPendingBookmarkByUrl(url: String): BookmarkEntity?

    // ── AI 解析完了後の更新 ─────────────────────────────────────────
    @Query(
        """
        UPDATE bookmarks
        SET title = :title, summary = :summary, category = :category, tags = :tags,
            thumbnailUrl = :thumbnailUrl, localImagePaths = :localImagePaths
        WHERE id = :id
        """
    )
    suspend fun updateAiMetadata(
        id: Long,
        title: String,
        summary: String,
        category: String,
        tags: String,
        thumbnailUrl: String = "",
        localImagePaths: String = ""
    )

    // ── ユーザーメモの更新 ──────────────────────────────────────────
    @Query("UPDATE bookmarks SET userMemo = :memo WHERE id = :id")
    suspend fun updateUserMemo(id: Long, memo: String)

    // ── 再解析: title を "読み込み中..." にリセット ────────────────
    @Query("UPDATE bookmarks SET title = '読み込み中...', summary = '', category = '', tags = '' WHERE id = :id")
    suspend fun resetToProcessing(id: Long)

    // ── 削除 ────────────────────────────────────────────────────────
    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmarkById(id: Long)

    // ── JSON エクスポート用（一括取得）──────────────────────────────
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    suspend fun getAllBookmarksOnce(): List<BookmarkEntity>
}
