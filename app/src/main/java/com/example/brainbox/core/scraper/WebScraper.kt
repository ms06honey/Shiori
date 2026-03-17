package com.example.brainbox.core.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.net.URL
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
class WebScraper @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    companion object {
        private const val TIMEOUT_MS = 10_000
        private const val USER_AGENT = "Mozilla/5.0 (compatible; BrainBox/1.0)"
        private const val MAX_CONTENT_CHARS = 3_000
    }

    suspend fun scrape(url: String, sharedText: String? = null): Result<ScrapedContent> = withContext(Dispatchers.IO) {
        runCatching {
            val finalUrl = resolveFinalUrl(url)
            val cleanedSharedText = cleanSharedText(sharedText, finalUrl)

            val doc = runCatching {
                Jsoup.connect(finalUrl)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .maxBodySize(0)
                    .followRedirects(true)
                    .ignoreHttpErrors(false)
                    .get()
            }.getOrNull()

            val htmlContent = doc?.let { extractFromDocument(it) }
            val isBlocked = htmlContent?.let(::looksLikeBlockedPage) == true

            val xFallback = if (isXLikeUrl(finalUrl)) {
                fetchPublicXContent(finalUrl, cleanedSharedText)
            } else {
                null
            }

            val merged = mergeContent(
                primary = htmlContent?.takeUnless { isBlocked },
                xFallback = xFallback,
                sharedText = cleanedSharedText,
                originalUrl = finalUrl
            )

            merged
        }
    }

    private fun resolveFinalUrl(url: String): String {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                response.request.url.toString()
            }
        }.getOrDefault(url)
    }

    private fun extractFromDocument(doc: Document): ScrapedContent {
        val title = doc.select("meta[property=og:title]").attr("content")
            .ifBlank { doc.title() }

        val description = doc.select("meta[property=og:description]").attr("content")
            .ifBlank { doc.select("meta[name=description]").attr("content") }

        val mainText = extractMainText(doc)
        return ScrapedContent(title = title, description = description, mainText = mainText)
    }

    private fun looksLikeBlockedPage(content: ScrapedContent): Boolean {
        val joined = listOf(content.title, content.description, content.mainText).joinToString(" ")
        return joined.contains("コンテンツを表示できません") ||
            joined.contains("Something went wrong", ignoreCase = true) ||
            joined.contains("JavaScript is not available", ignoreCase = true) ||
            joined.contains("ログイン", ignoreCase = true) && joined.contains("X", ignoreCase = true)
    }

    private fun isXLikeUrl(url: String): Boolean = runCatching {
        val host = URL(url).host.lowercase()
        host == "x.com" || host == "www.x.com" || host == "twitter.com" || host == "www.twitter.com" || host == "mobile.twitter.com" || host == "mobile.x.com"
    }.getOrDefault(false)

    private fun extractTweetId(url: String): String? {
        return Regex("""/(?:i/web|[^/]+)/status/(\d+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun fetchPublicXContent(url: String, cleanedSharedText: String?): ScrapedContent? {
        val tweetId = extractTweetId(url) ?: return cleanedSharedText?.toSharedFallback(url)
        val request = Request.Builder()
            .url("https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&lang=ja")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        return runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use cleanedSharedText?.toSharedFallback(url)
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val text = json.optString("text").trim()
                val user = json.optJSONObject("user")
                val userName = user?.optString("name").orEmpty().trim()
                val screenName = user?.optString("screen_name").orEmpty().trim()
                val title = when {
                    userName.isNotBlank() && screenName.isNotBlank() -> "$userName (@$screenName) の投稿"
                    userName.isNotBlank() -> "$userName の投稿"
                    else -> "Xの投稿"
                }
                val baseText = text.ifBlank { cleanedSharedText.orEmpty() }
                ScrapedContent(
                    title = title,
                    description = baseText,
                    mainText = baseText
                )
            }
        }.getOrNull() ?: cleanedSharedText?.toSharedFallback(url)
    }

    private fun mergeContent(
        primary: ScrapedContent?,
        xFallback: ScrapedContent?,
        sharedText: String?,
        originalUrl: String
    ): ScrapedContent {
        val fallback = xFallback ?: sharedText?.toSharedFallback(originalUrl)
        val title = primary?.title?.takeIf { it.isNotBlank() } ?: fallback?.title ?: originalUrl
        val description = primary?.description?.takeIf { it.isNotBlank() }
            ?: fallback?.description
            ?: ""
        val mainText = buildString {
            primary?.mainText?.takeIf { it.isNotBlank() }?.let { append(it) }
            if (sharedText != null && sharedText.isNotBlank()) {
                if (isNotBlank()) append("\n\n")
                append(sharedText)
            } else if (isBlank()) {
                fallback?.mainText?.takeIf { it.isNotBlank() }?.let { append(it) }
            }
        }.take(MAX_CONTENT_CHARS)

        return ScrapedContent(title = title, description = description, mainText = mainText)
    }

    private fun cleanSharedText(sharedText: String?, url: String): String? {
        return sharedText
            ?.replace(Regex("""https?://\S+"""), " ")
            ?.replace(url, " ", ignoreCase = true)
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.toSharedFallback(url: String) = ScrapedContent(
        title = "共有された投稿",
        description = this.take(200),
        mainText = this.take(MAX_CONTENT_CHARS)
    )

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
