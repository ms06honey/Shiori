package com.example.shiori.feature.bookmark.presentation

import com.example.shiori.feature.bookmark.domain.model.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class BookmarkListGroupingTest {

    @Test
    fun buildBookmarkGroups_groupsByDayForDateTree() {
        val bookmarks = listOf(
            bookmark(id = 1, createdAt = millisAt(2026, 3, 17, 18), category = "技術"),
            bookmark(id = 2, createdAt = millisAt(2026, 3, 17, 9), category = "技術"),
            bookmark(id = 3, createdAt = millisAt(2026, 3, 16, 22), category = "生活")
        )

        val groups = buildBookmarkGroups(bookmarks, BookmarkListViewMode.DATE_TREE)

        assertEquals(2, groups.size)
        assertEquals(listOf(1L, 2L), groups[0].bookmarks.map(Bookmark::id))
        assertEquals(listOf(3L), groups[1].bookmarks.map(Bookmark::id))
        assertTrue(groups[0].title.contains("2026/03/17"))
    }

    @Test
    fun buildBookmarkGroups_usesCategoryAsMainTagAndFallsBackToFirstTag() {
        val bookmarks = listOf(
            bookmark(id = 1, category = "テクノロジー", tags = listOf("Android", "Kotlin")),
            bookmark(id = 2, category = "", tags = listOf("ニュース", "速報")),
            bookmark(id = 3, category = "", tags = emptyList())
        )

        val groups = buildBookmarkGroups(bookmarks, BookmarkListViewMode.MAIN_TAG_TREE)

        assertEquals(listOf("テクノロジー", "ニュース", "未分類"), groups.map(BookmarkGroup::title))
        assertEquals(listOf(1L), groups[0].bookmarks.map(Bookmark::id))
        assertEquals(listOf(2L), groups[1].bookmarks.map(Bookmark::id))
        assertEquals(listOf(3L), groups[2].bookmarks.map(Bookmark::id))
    }

    @Test
    fun buildBookmarkGroups_groupsByNormalizedSourceLabel() {
        val bookmarks = listOf(
            bookmark(id = 1, url = "https://www.youtube.com/watch?v=abc"),
            bookmark(id = 2, url = "https://youtu.be/xyz"),
            bookmark(id = 3, url = "https://x.com/example/status/1"),
            bookmark(id = 4, url = "shared-video://imported/123"),
            bookmark(id = 5, url = "https://example.com/manual")
        )

        val groups = buildBookmarkGroups(bookmarks, BookmarkListViewMode.SOURCE_TREE)

        assertEquals(listOf("YouTube", "X", "共有動画", "example.com"), groups.map(BookmarkGroup::title))
        assertEquals(listOf(1L, 2L), groups[0].bookmarks.map(Bookmark::id))
    }

    @Test
    fun buildBookmarkGroups_usesHostForUnknownSourceAndHandlesSubdomain() {
        val bookmarks = listOf(
            bookmark(id = 1, url = "https://subdomain.example.org/articles/1"),
            bookmark(id = 2, url = "https://mobile.github.com/features")
        )

        val groups = buildBookmarkGroups(bookmarks, BookmarkListViewMode.SOURCE_TREE)

        assertEquals(listOf("subdomain.example.org", "GitHub"), groups.map(BookmarkGroup::title))
    }

    @Test
    fun sourceDisplayLabel_recognizesAdditionalKnownSites() {
        val medium = bookmark(id = 1, url = "https://medium.com/androiddevelopers/some-post")
        val hatena = bookmark(id = 2, url = "https://sample.hatenablog.com/entry/2026/03/17")
        val yahooNews = bookmark(id = 3, url = "https://news.yahoo.co.jp/articles/example")
        val niconico = bookmark(id = 4, url = "https://www.nicovideo.jp/watch/sm9")

        assertEquals("Medium", medium.sourceDisplayLabel())
        assertEquals("はてなブログ", hatena.sourceDisplayLabel())
        assertEquals("Yahoo!ニュース", yahooNews.sourceDisplayLabel())
        assertEquals("ニコニコ", niconico.sourceDisplayLabel())
    }

    private fun bookmark(
        id: Long,
        url: String = "https://example.com/$id",
        createdAt: Long = millisAt(2026, 3, 17, 12),
        category: String = "",
        tags: List<String> = emptyList()
    ) = Bookmark(
        id = id,
        url = url,
        title = "bookmark-$id",
        category = category,
        tags = tags,
        createdAt = createdAt
    )

    private fun millisAt(year: Int, month: Int, day: Int, hour: Int): Long =
        LocalDateTime.of(year, month, day, hour, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
