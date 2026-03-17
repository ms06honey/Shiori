package com.example.shiori.feature.bookmark.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkAiSummaryTest {

    @Test
    fun buildAndParseStoredAiSummary_preservesOverviewAndPoints() {
        val stored = buildStoredAiSummary(
            overview = "これは概要です。",
            points = listOf("重要な点A", "重要な点B", "重要な点C")
        )

        val parsed = parseStoredAiSummary(stored)

        assertEquals("これは概要です。", parsed.overview)
        assertEquals(listOf("重要な点A", "重要な点B", "重要な点C"), parsed.points)
    }

    @Test
    fun parseStoredAiSummary_returnsWholeTextAsOverviewForLegacySummary() {
        val parsed = parseStoredAiSummary("従来形式のサマリーだけが入っている")

        assertEquals("従来形式のサマリーだけが入っている", parsed.overview)
        assertTrue(parsed.points.isEmpty())
    }

    @Test
    fun toMarkdown_rendersOverviewAndPointsSeparately() {
        val bookmark = Bookmark(
            url = "https://example.com",
            title = "テスト",
            summary = buildStoredAiSummary(
                overview = "概要テキスト",
                points = listOf("箇条書き1", "箇条書き2")
            )
        )

        val markdown = bookmark.toMarkdown()

        assertTrue(markdown.contains("### サマリー"))
        assertTrue(markdown.contains("概要テキスト"))
        assertTrue(markdown.contains("### ポイント"))
        assertTrue(markdown.contains("- 箇条書き1"))
        assertTrue(markdown.contains("- 箇条書き2"))
    }
}

