package com.example.brainbox.core.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

data class ScrapedContent(
    /** og:title → <title> の順で取得 */
    val title: String,
    /** og:description → <meta name=description> の順（SPA フォールバック用） */
    val description: String,
    /** ノイズ除去後の本文テキスト（SPA では空になる可能性あり） */
    val mainText: String,
)

/**
 * Jsoup を使って Web ページのメタ情報と本文を抽出する。
 * SPA/動的サイト: JS 実行なしのため本文が空になりうる → OGP をフォールバックとして使用。
 */
@Singleton
class WebScraper @Inject constructor() {

    companion object {
        private const val TIMEOUT_MS = 10_000
        private const val USER_AGENT = "Mozilla/5.0 (compatible; BrainBox/1.0)"
        private const val MAX_CONTENT_CHARS = 3_000
    }

    suspend fun scrape(url: String): Result<ScrapedContent> = withContext(Dispatchers.IO) {
        runCatching {
            val doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .maxBodySize(0)          // ページサイズ上限なし（デフォルト 1MB）
                .followRedirects(true)
                .ignoreHttpErrors(false) // 4xx/5xx は例外として扱う
                .get()

            val title = doc.select("meta[property=og:title]").attr("content")
                .ifBlank { doc.title() }

            val description = doc.select("meta[property=og:description]").attr("content")
                .ifBlank { doc.select("meta[name=description]").attr("content") }

            // NOTE: extractMainText は doc を直接変更するため必ず最後に呼ぶ
            val mainText = extractMainText(doc)

            ScrapedContent(title = title, description = description, mainText = mainText)
        }
    }

    /**
     * ノイズ要素（nav / header / footer / aside / script / style / .ad）を除去して
     * 本文テキストを抽出する。3000 字に切り詰めて返す。
     *
     * NOTE: scrape() 内で最後に呼ばれるため、doc を直接変更する（clone 不要）。
     */
    private fun extractMainText(doc: Document): String {
        doc.select(
            "nav, header, footer, aside, script, style, noscript, iframe," +
            " .ad, #ad, [class*=banner], [id*=banner]"
        ).remove()

        val target = doc.selectFirst("article, main, [role=main]") ?: doc.body()
        val text = target?.text() ?: ""
        return text.take(MAX_CONTENT_CHARS)
    }
}
