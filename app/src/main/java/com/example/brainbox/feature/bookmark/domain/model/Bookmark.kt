package com.example.brainbox.feature.bookmark.domain.model

data class Bookmark(
    val id: Long = 0,
    val url: String,
    val title: String,
    val summary: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** ユーザーが自由に入力できるメモ */
    val userMemo: String = "",
    /** og:image 等から取得したサムネイル URL */
    val thumbnailUrl: String = ""
)
