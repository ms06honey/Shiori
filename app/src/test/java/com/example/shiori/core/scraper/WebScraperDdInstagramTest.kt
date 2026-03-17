package com.example.shiori.core.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebScraperDdInstagramTest {

    @Test
    fun buildDdInstagramUrl_rewritesInstagramHostAndPreservesPathAndQuery() {
        val rewritten = WebScraper.buildDdInstagramUrl(
            "https://www.instagram.com/reel/ABC123/?utm_source=ig_web_copy_link"
        )

        assertEquals(
            "https://ddinstagram.com/reel/ABC123/?utm_source=ig_web_copy_link",
            rewritten
        )
    }

    @Test
    fun buildDdInstagramUrl_supportsInstagrAmShortDomain() {
        val rewritten = WebScraper.buildDdInstagramUrl("https://instagr.am/p/HELLO789/")

        assertEquals("https://ddinstagram.com/p/HELLO789/", rewritten)
    }

    @Test
    fun buildDdInstagramUrl_returnsNullForNonInstagramUrls() {
        assertNull(WebScraper.buildDdInstagramUrl("https://example.com/video/123"))
    }
}

