package com.example.brainbox.feature.bookmark.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * PDF仕様書 §5 データモデルに準拠。
 * id / url / title / summary / category / tags / createdAt(Unix ms) / userMemo / thumbnailUrl
 */
@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    /** AI が生成したタイトル（処理中は "読み込み中..."） */
    val title: String,
    /** AI が生成した 2〜3 文の日本語要約 */
    val summary: String = "",
    /** AI が分類した単一カテゴリ */
    val category: String = "",
    /** カンマ区切りシリアライズ済みタグ配列 */
    val tags: String = "",
    /** 保存日時 Unix Timestamp (ms) */
    val createdAt: Long = System.currentTimeMillis(),
    /** ユーザーが自由に入力できるメモ */
    val userMemo: String = "",
    /** スクレイプで取得したサイトのサムネイル画像 URL (og:image 等) */
    val thumbnailUrl: String = ""
)
