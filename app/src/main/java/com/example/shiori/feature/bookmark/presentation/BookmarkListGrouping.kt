package com.example.shiori.feature.bookmark.presentation

import com.example.shiori.feature.bookmark.domain.model.Bookmark
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class BookmarkListViewMode(val label: String) {
    NORMAL("通常"),
    DATE_TREE("日付のグルーピングツリー"),
    MAIN_TAG_TREE("メインタグのグルーピングツリー"),
    SOURCE_TREE("取得元のグルーピングツリー")
}

data class BookmarkGroup(
    val id: String,
    val title: String,
    val bookmarks: List<Bookmark>
)

private val TREE_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy/MM/dd (E)", Locale.JAPAN)
        .withZone(ZoneId.systemDefault())

private val KNOWN_SOURCE_LABELS = linkedMapOf(
    "youtube.com" to "YouTube",
    "youtu.be" to "YouTube",
    "x.com" to "X",
    "twitter.com" to "X",
    "github.com" to "GitHub",
    "qiita.com" to "Qiita",
    "zenn.dev" to "Zenn",
    "note.com" to "note",
    "medium.com" to "Medium",
    "dev.to" to "dev.to",
    "hatenablog.com" to "はてなブログ",
    "hatena.ne.jp" to "はてな",
    "connpass.com" to "connpass",
    "speakerdeck.com" to "Speaker Deck",
    "instagram.com" to "Instagram",
    "facebook.com" to "Facebook",
    "tiktok.com" to "TikTok",
    "reddit.com" to "Reddit",
    "linkedin.com" to "LinkedIn",
    "wikipedia.org" to "Wikipedia",
    "news.yahoo.co.jp" to "Yahoo!ニュース",
    "yahoo.co.jp" to "Yahoo! JAPAN",
    "rakuten.co.jp" to "楽天",
    "mercari.com" to "メルカリ",
    "togetter.com" to "Togetter",
    "gigazine.net" to "GIGAZINE",
    "itmedia.co.jp" to "ITmedia",
    "ascii.jp" to "ASCII.jp",
    "pixiv.net" to "pixiv",
    "niconico.com" to "ニコニコ",
    "nicovideo.jp" to "ニコニコ",
    "amazon.co.jp" to "Amazon",
    "amazon.com" to "Amazon"
)

fun buildBookmarkGroups(
    bookmarks: List<Bookmark>,
    viewMode: BookmarkListViewMode
): List<BookmarkGroup> = when (viewMode) {
    BookmarkListViewMode.NORMAL -> emptyList()
    BookmarkListViewMode.DATE_TREE -> bookmarks
        .groupBy(Bookmark::dateGroupKey)
        .map { (key, items) ->
            BookmarkGroup(
                id = "date:$key",
                title = items.first().dateGroupLabel(),
                bookmarks = items
            )
        }

    BookmarkListViewMode.MAIN_TAG_TREE -> bookmarks
        .groupBy(Bookmark::mainTagGroupLabel)
        .map { (label, items) ->
            BookmarkGroup(
                id = "main-tag:${label.lowercase(Locale.ROOT)}",
                title = label,
                bookmarks = items
            )
        }

    BookmarkListViewMode.SOURCE_TREE -> bookmarks
        .groupBy(Bookmark::sourceGroupLabel)
        .map { (label, items) ->
            BookmarkGroup(
                id = "source:${label.lowercase(Locale.ROOT)}",
                title = label,
                bookmarks = items
            )
        }
}

private fun Bookmark.dateGroupKey(): String =
    Instant.ofEpochMilli(createdAt)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .toString()

private fun Bookmark.dateGroupLabel(): String = TREE_DATE_FORMATTER.format(Instant.ofEpochMilli(createdAt))

private fun Bookmark.mainTagGroupLabel(): String =
    category.trim().takeIf(String::isNotBlank)
        ?: tags.firstOrNull()?.trim()?.takeIf(String::isNotBlank)
        ?: "未分類"

fun Bookmark.sourceDisplayLabel(): String {
    if (url.startsWith("shared-video://", ignoreCase = true)) return "共有動画"

    val host = normalizedHost(url) ?: return "手動追加"

    KNOWN_SOURCE_LABELS.entries.firstOrNull { (domain, _) ->
        host == domain || host.endsWith(".$domain")
    }?.let { return it.value }

    return host
}

private fun Bookmark.sourceGroupLabel(): String = sourceDisplayLabel()

private fun normalizedHost(url: String): String? = runCatching {
    URI(url).host
}.getOrNull()
    ?.lowercase(Locale.ROOT)
    ?.removePrefix("www.")
    ?.removePrefix("m.")
    ?.removePrefix("mobile.")
    ?.trim()
    ?.takeIf(String::isNotBlank)
