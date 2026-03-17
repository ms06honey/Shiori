package com.example.shiori.feature.bookmark.domain.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val thumbnailUrl: String = "",
    /** X / Web ページから抽出した動画 URL（最優先候補 1 本） */
    val videoUrl: String = "",
    /** ローカル保存した動画ファイルの絶対パス */
    val localVideoPath: String = "",
    /** ローカルに保存した画像ファイルの絶対パスリスト（先頭がサムネイル用） */
    val localImagePaths: List<String> = emptyList()
)

/**
 * ブックマークを Markdown 形式の文字列に変換する。
 *
 * 出力例:
 * ```
 * ## タイトル
 *
 * **URL**: https://example.com
 * **カテゴリ**: テクノロジー
 * **タグ**: Kotlin, Android
 * **保存日**: 2024-01-01
 *
 * ### サマリー
 * ページの要約テキスト。
 *
 * ### メモ
 * ユーザーメモ。
 * ```
 */
fun Bookmark.toMarkdown(): String = buildString {
    // タイトル
    appendLine("## $title")
    appendLine()

    // メタ情報
    appendLine("**URL**: $url")
    if (category.isNotBlank()) appendLine("**カテゴリ**: $category")
    if (tags.isNotEmpty()) appendLine("**タグ**: ${tags.joinToString(", ")}")
    if (videoUrl.isNotBlank()) appendLine("**動画**: $videoUrl")
    if (localVideoPath.isNotBlank()) appendLine("**ローカル動画**: $localVideoPath")
    val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(createdAt))
    appendLine("**保存日**: $dateStr")

    // サマリー
    if (summary.isNotBlank()) {
        appendLine()
        appendLine("### サマリー")
        appendLine(summary)
    }

    // メモ
    if (userMemo.isNotBlank()) {
        appendLine()
        appendLine("### メモ")
        appendLine(userMemo)
    }
}.trimEnd()


