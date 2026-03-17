package com.example.shiori.core.scraper

import android.net.Uri
import android.util.Log
import com.example.shiori.core.datastore.EncryptedPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class ScrapedContent(
    /** og:title → <title> の順で取得 */
    val title: String,
    /** og:description → <meta name=description> の順（SPA フォールバック用） */
    val description: String,
    /** ノイズ除去後の本文テキスト（SPA では空になる可能性あり） */
    val mainText: String,
    /** og:image → 最初の有効な <img> の順で取得したサムネイル URL（先頭 = allImageUrls[0]） */
    val imageUrl: String = "",
    /** ページ内で検出したすべての有効な画像 URL（先頭がサムネイル優先度最高） */
    val allImageUrls: List<String> = emptyList(),
)

/**
 * Jsoup を使って Web ページのメタ情報と本文を抽出する。
 * X/Twitter は HTML スクレイプが困難なため専用フローで対応する。
 */
@Singleton
class WebScraper @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val encryptedPrefsManager: EncryptedPrefsManager
) {

    companion object {
        private const val TIMEOUT_MS = 15_000
        /**
         * PC Chrome を偽装した UA。通常ブラウザとして扱われる。
         */
        const val CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

        /**
         * SNS クローラー UA（Facebook externalhit）。
         * 多くのサイトが SNS クローラーを検知して OGP メタタグを最適化して返すため、
         * og:image の取得成功率が Chrome UA より高い。
         */
        const val CRAWLER_UA =
            "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

        private const val MAX_CONTENT_CHARS = 3_000
        private const val MAX_TWEET_REDIRECT_HOPS = 5
        /** 1 ページから収集する最大画像枚数 */
        private const val MAX_ALL_IMAGES = 20
        private const val TAG = "WebScraper"
    }

    /**
     * 設定されたスクレイパーモードに応じた UA 文字列を返す。
     * SharedPreferences 読み取りは高速なので毎回読んで問題ない。
     */
    private val activeUserAgent: String
        get() = when (encryptedPrefsManager.getScraperMode()) {
            EncryptedPrefsManager.ScraperMode.CRAWLER_UA -> CRAWLER_UA
            EncryptedPrefsManager.ScraperMode.CHROME_UA  -> CHROME_UA
        }

    suspend fun scrape(url: String, sharedText: String? = null): Result<ScrapedContent> = withContext(Dispatchers.IO) {
        runCatching {
            // ── ① X ツイート URL を resolveFinalUrl の前に特定 ───────────
            // X のサーバーはログイン誘導等で別ツイートへリダイレクトすることがある。
            // resolveFinalUrl() の結果を X 判定に使うと誤ったツイート ID を取得する。
            // そのため finalUrl は X ツイート URL の特定に一切使わない。
            //
            // 優先度:
            //   ① sharedText 内の直接 x.com / twitter.com URL（最高信頼度）
            //   ② url 自体が直接 X ドメイン（クエリパラメータをクリーン化して使用）
            //   ③ url が短縮 URL（t.co 等）→ 手動リダイレクト追跡でツイート URL を特定
            val xTweetUrl: String? =
                findDirectXTweetUrl(sharedText)
                    ?: url.takeIf { isXLikeUrl(it) }?.let { cleanTweetUrl(it) }
                    ?: resolveToTweetUrl(url)

            Log.d(TAG, "scrape: url=$url xTweetUrl=$xTweetUrl")

            // ── ② 通常 HTML スクレイプ（X 以外のサイト用） ───────────────
            val ua = activeUserAgent
            val finalUrl = resolveFinalUrl(url, ua)
            val cleanedSharedText = cleanSharedText(sharedText, finalUrl)

            val doc = runCatching {
                Jsoup.connect(finalUrl)
                    .userAgent(ua)
                    .timeout(TIMEOUT_MS)
                    .maxBodySize(0)
                    .followRedirects(true)
                    .ignoreHttpErrors(false)
                    .get()
            }.getOrNull()

            val htmlContent = doc?.let { extractFromDocument(it) }
            val isBlocked = htmlContent?.let(::looksLikeBlockedPage) == true

            // ── ③ X コンテンツ取得（モードにより優先 API が変わる） ─────
            val xFallback = xTweetUrl?.let { fetchXContent(it, cleanedSharedText) }

            mergeContent(
                primary = htmlContent?.takeUnless { isBlocked },
                xFallback = xFallback,
                sharedText = cleanedSharedText,
                originalUrl = finalUrl
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // X/Twitter 専用ヘルパー
    // ────────────────────────────────────────────────────────────────────

    /**
     * テキスト中から直接 x.com / twitter.com のツイート URL（/status/ID 形式）を抽出する。
     * t.co は対象外。
     */
    private fun findDirectXTweetUrl(text: String?): String? {
        if (text == null) return null
        return Regex("""https?://(?:www\.|mobile\.)?(?:x\.com|twitter\.com)/[^\s]+/status/\d+[^\s]*""")
            .findAll(text)
            .firstOrNull { isXLikeUrl(it.value) }
            ?.value
    }

    private fun isXLikeUrl(url: String): Boolean = runCatching {
        val host = URL(url).host.lowercase()
        host == "x.com" || host == "www.x.com" ||
            host == "twitter.com" || host == "www.twitter.com" ||
            host == "mobile.twitter.com" || host == "mobile.x.com"
    }.getOrDefault(false)

    /**
     * t.co などの短縮 URL を手動でリダイレクトを追跡し、正確なツイート URL を返す。
     *
     * ─ アルゴリズム ─────────────────────────────────────────────────
     * 自動リダイレクト禁止クライアントでリダイレクトを 1 つずつ手動で追い、
     * ツイート URL（/status/ID）が見つかった時点で即座に確定して追跡を打ち切る。
     *   → X サーバーが後続で行うログイン誘導リダイレクトを完全に踏まない
     *
     * ─ ログイン誘導の救済 ────────────────────────────────────────────
     * X がログイン画面（/i/flow/login）へ誘導した場合でも、URL 中の
     * redirect_after_login パラメータに元のツイートパスが含まれているため、
     * そこから正しい URL を救済する。
     *
     * ─ URL クリーニング ──────────────────────────────────────────────
     * ツイート URL が確定したら ?s=20, ?t=xxx 等のトラッキング用
     * クエリパラメータを除去してクリーンな URL を返す。
     */
    private fun resolveToTweetUrl(shortUrl: String): String? {
        val noRedirectClient = okHttpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        var currentUrl = shortUrl
        var hop = 0

        while (hop < MAX_TWEET_REDIRECT_HOPS) {
            val (statusCode, location) = runCatching {
                val request = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", CHROME_UA) // PC ブラウザ UA でボット弾き回避
                    .build()
                noRedirectClient.newCall(request).execute().use { r ->
                    r.code to r.header("Location")
                }
            }.getOrElse { e ->
                Log.e(TAG, "resolveToTweetUrl: request failed hop=$hop '$currentUrl': ${e.message}")
                return null
            }

            Log.d(TAG, "resolveToTweetUrl: hop=$hop '$currentUrl' → HTTP $statusCode Location=$location")

            if (statusCode in 300..399 && location != null) {
                // ツイート URL が見つかった → 即座に確定（X の追加リダイレクトを踏まない）
                if (isTweetUrl(location)) {
                    return cleanTweetUrl(location).also {
                        Log.d(TAG, "resolveToTweetUrl: ✓ confirmed at hop=$hop: $it")
                    }
                }
                // ログイン誘導ページを検出 → redirect_after_login パラメータで救済
                extractFromLoginRedirect(location)?.let { rescued ->
                    Log.d(TAG, "resolveToTweetUrl: ✓ rescued from login redirect: $rescued")
                    return rescued
                }
                // まだ目的 URL でない → 次のリダイレクト先へ進む
                currentUrl = location
                hop++
            } else {
                // 200 OK 等、リダイレクトが終了した場合
                extractFromLoginRedirect(currentUrl)?.let { rescued ->
                    Log.d(TAG, "resolveToTweetUrl: ✓ rescued from final login page: $rescued")
                    return rescued
                }
                Log.d(TAG, "resolveToTweetUrl: ended without tweet URL at '$currentUrl'")
                return null
            }
        }

        Log.w(TAG, "resolveToTweetUrl: max hops ($MAX_TWEET_REDIRECT_HOPS) reached for $shortUrl")
        return null
    }

    /** URL が x.com / twitter.com の /status/ID 形式かどうかを判定する */
    private fun isTweetUrl(url: String): Boolean =
        Regex("""^https?://(?:www\.)?(?:twitter\.com|x\.com)/.+/status/\d+""")
            .containsMatchIn(url)

    /**
     * ツイート URL から ?s=20, ?t=xxx 等のトラッキング用クエリパラメータを除去する。
     * oEmbed API に余分なパラメータが含まれると失敗する場合があるため。
     */
    private fun cleanTweetUrl(url: String): String {
        val match = Regex("""^(https?://(?:www\.)?(?:twitter\.com|x\.com)/.+/status/\d+)""")
            .find(url)
        return match?.groupValues?.get(1) ?: url
    }

    /**
     * ログイン誘導 URL の redirect_after_login パラメータからツイートパスを救済する。
     * 例: https://x.com/i/flow/login?redirect_after_login=%2Fusername%2Fstatus%2F123
     *     → https://x.com/username/status/123
     */
    private fun extractFromLoginRedirect(url: String): String? = runCatching {
        val uri = Uri.parse(url)
        val redirectPath = uri.getQueryParameter("redirect_after_login")
        if (redirectPath != null && redirectPath.contains("/status/")) {
            "https://x.com$redirectPath"
        } else null
    }.getOrNull()

    /**
     * X ツイートのコンテンツを取得する。
     *
     * CRAWLER_UA モード:
     *   1. vxtwitter API（高信頼・画像 URL も取得可）
     *   2. Twitter oEmbed API（公式）
     *   3. Syndication API（非公式フォールバック）
     *
     * CHROME_UA モード:
     *   1. Twitter oEmbed API
     *   2. Syndication API
     */
    private fun fetchXContent(tweetUrl: String, cleanedSharedText: String?): ScrapedContent? {
        Log.d(TAG, "fetchXContent: $tweetUrl mode=${encryptedPrefsManager.getScraperMode()}")
        return when (encryptedPrefsManager.getScraperMode()) {
            EncryptedPrefsManager.ScraperMode.CRAWLER_UA ->
                fetchVxTwitter(tweetUrl)
                    ?: fetchOEmbed(tweetUrl)
                    ?: fetchSyndicationApi(tweetUrl, cleanedSharedText)
            EncryptedPrefsManager.ScraperMode.CHROME_UA ->
                fetchOEmbed(tweetUrl)
                    ?: fetchSyndicationApi(tweetUrl, cleanedSharedText)
        }
    }

    /**
     * vxtwitter API でツイートデータを取得する（CRAWLER_UA モード優先）。
     * https://api.vxtwitter.com/Twitter/status/{id}
     *
     * レスポンス例:
     * {
     *   "text": "...",
     *   "user_name": "表示名",
     *   "user_screen_name": "username",
     *   "media_extended": [{"url": "https://...", "type": "image"}]
     * }
     */
    private fun fetchVxTwitter(tweetUrl: String): ScrapedContent? {
        val tweetId = extractTweetId(tweetUrl) ?: run {
            Log.w(TAG, "vxtwitter: tweet ID not found in url=$tweetUrl")
            return null
        }
        Log.d(TAG, "vxtwitter: tweetId=$tweetId")

        val request = Request.Builder()
            .url("https://api.vxtwitter.com/Twitter/status/$tweetId")
            .header("User-Agent", CHROME_UA)
            .header("Accept", "application/json")
            .get()
            .build()

        return runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "vxtwitter: HTTP ${response.code} for tweetId=$tweetId")
                    return@use null
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@use null

                val json = runCatching { JSONObject(body) }.getOrNull() ?: return@use null

                // エラーレスポンス判定
                if (json.optString("error").isNotBlank()) {
                    Log.w(TAG, "vxtwitter: error=${json.optString("error")}")
                    return@use null
                }

                val text = json.optString("text").trim()
                val userName = json.optString("user_name").trim()
                val userScreenName = json.optString("user_screen_name").trim()

                val title = when {
                    userName.isNotBlank() && userScreenName.isNotBlank() ->
                        "$userName (@$userScreenName) の投稿"
                    userName.isNotBlank() -> "$userName の投稿"
                    else -> "Xの投稿"
                }

                // media_extended から全画像 URL を取得
                val allImageUrls = runCatching {
                    json.optJSONArray("media_extended")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            arr.getJSONObject(i).optString("url").takeIf { it.isNotBlank() }
                        }
                    } ?: emptyList()
                }.getOrDefault(emptyList())
                val imageUrl = allImageUrls.firstOrNull() ?: ""

                Log.d(TAG, "vxtwitter: ok title=$title text=${text.take(60)} images=${allImageUrls.size}")
                ScrapedContent(
                    title = title,
                    description = text,
                    mainText = text,
                    imageUrl = imageUrl,
                    allImageUrls = allImageUrls
                )
            }
        }.getOrElse { e ->
            Log.e(TAG, "vxtwitter: exception for tweetId=$tweetId: ${e.message}")
            null
        }
    }

    /**
     * Twitter 公式 oEmbed API でツイートデータを取得する。
     * https://publish.twitter.com/oembed?url=<tweet_url>
     *
     * - 公式 API のため Syndication API より信頼性が高い
     * - 認証不要（公開ツイートのみ）
     * - 返ってくる HTML から Jsoup でツイートテキストを抽出
     */
    private fun fetchOEmbed(tweetUrl: String): ScrapedContent? {
        return runCatching {
            val encoded = URLEncoder.encode(tweetUrl, "UTF-8")
            val request = Request.Builder()
                .url("https://publish.twitter.com/oembed?url=$encoded&omit_script=true")
                .header("User-Agent", CHROME_UA)
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "oEmbed: HTTP ${response.code} for $tweetUrl")
                    return@use null
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@use null

                val json = runCatching { JSONObject(body) }.getOrNull() ?: return@use null

                val authorName = json.optString("author_name").trim()
                val authorUrl = json.optString("author_url").trim()
                val html = json.optString("html")

                // author_url = "https://twitter.com/username" → username を取得
                val screenName = runCatching {
                    URL(authorUrl).path.trim('/')
                }.getOrDefault("")

                val title = when {
                    authorName.isNotBlank() && screenName.isNotBlank() ->
                        "$authorName (@$screenName) の投稿"
                    authorName.isNotBlank() -> "$authorName の投稿"
                    else -> "Xの投稿"
                }

                // oEmbed の HTML から Jsoup でツイートテキストを抽出
                // <p lang="...">ツイート本文 <a href="t.co/...">...</a></p> 構造
                val tweetText = runCatching {
                    val doc = Jsoup.parse(html)
                    val p = doc.selectFirst("p") ?: return@runCatching ""
                    // t.co / pic.twitter.com / pic.x.com のリンクは除去
                    p.select(
                        "a[href*=t.co], a[href*=pic.twitter.com], a[href*=pic.x.com]"
                    ).remove()
                    p.text().trim()
                }.getOrDefault("")

                Log.d(TAG, "oEmbed: ok title=$title text=${tweetText.take(60)}")
                ScrapedContent(
                    title = title,
                    description = tweetText,
                    mainText = tweetText,
                    imageUrl = ""
                )
            }
        }.getOrElse { e ->
            Log.e(TAG, "oEmbed: exception for $tweetUrl: ${e.message}")
            null
        }
    }

    /**
     * Syndication API（非公式）でツイートデータを取得する。
     * oEmbed が失敗した場合のフォールバック。
     * レスポンスの id_str を要求 ID と照合し、不一致なら無効とみなす。
     */
    private fun fetchSyndicationApi(url: String, cleanedSharedText: String?): ScrapedContent? {
        val tweetId = extractTweetId(url) ?: run {
            Log.w(TAG, "syndication: tweet ID not found in url=$url")
            return cleanedSharedText?.toSharedFallback(url)
        }
        Log.d(TAG, "syndication: tweetId=$tweetId")

        val request = Request.Builder()
            .url("https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&lang=ja")
            .header("User-Agent", CHROME_UA)
            .get()
            .build()

        return runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "syndication: HTTP ${response.code} for tweetId=$tweetId")
                    return@use cleanedSharedText?.toSharedFallback(url)
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@use cleanedSharedText?.toSharedFallback(url)

                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return@use cleanedSharedText?.toSharedFallback(url)

                // ── ID 照合：別ツイートのデータが返ってきた場合は拒否 ──────
                val responseId = json.optString("id_str")
                if (responseId.isNotBlank() && responseId != tweetId) {
                    Log.w(TAG, "syndication: ID mismatch! requested=$tweetId got=$responseId → fallback")
                    return@use cleanedSharedText?.toSharedFallback(url)
                }

                val text = json.optString("text").trim()
                val user = json.optJSONObject("user")
                val userName = user?.optString("name").orEmpty().trim()
                val screenName = user?.optString("screen_name").orEmpty().trim()

                val title = when {
                    userName.isNotBlank() && screenName.isNotBlank() ->
                        "$userName (@$screenName) の投稿"
                    userName.isNotBlank() -> "$userName の投稿"
                    else -> "Xの投稿"
                }
                val baseText = text.ifBlank { cleanedSharedText.orEmpty() }

                val allTweetImageUrls = runCatching {
                    json.optJSONArray("mediaDetails")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            arr.getJSONObject(i).optString("media_url_https").takeIf { it.isNotBlank() }
                        }
                    } ?: emptyList()
                }.getOrDefault(emptyList())
                val tweetImageUrl = allTweetImageUrls.firstOrNull() ?: ""

                Log.d(TAG, "syndication: ok title=$title images=${allTweetImageUrls.size}")
                ScrapedContent(
                    title = title,
                    description = baseText,
                    mainText = baseText,
                    imageUrl = tweetImageUrl,
                    allImageUrls = allTweetImageUrls
                )
            }
        }.getOrElse { e ->
            Log.e(TAG, "syndication: exception for tweetId=$tweetId: ${e.message}", e)
            cleanedSharedText?.toSharedFallback(url)
        }
    }

    /**
     * URL の「パス部分だけ」からツイート ID を抽出する。
     * クエリパラメータ中の別ツイート URL を誤ってマッチしないよう
     * java.net.URL でパースしたパスのみを対象にする。
     */
    private fun extractTweetId(url: String): String? {
        val path = runCatching { URL(url).path }.getOrDefault(url)
        return Regex("""/(?:i/web|[^/]+)/status/(\d+)""")
            .find(path)
            ?.groupValues
            ?.getOrNull(1)
    }

    // ────────────────────────────────────────────────────────────────────
    // 汎用 HTML スクレイプ
    // ────────────────────────────────────────────────────────────────────

    private fun resolveFinalUrl(url: String, ua: String = CHROME_UA): String {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
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

        val allImageUrls = extractAllImageUrls(doc)
        val imageUrl = allImageUrls.firstOrNull() ?: ""

        val mainText = extractMainText(doc)
        return ScrapedContent(
            title = title,
            description = description,
            mainText = mainText,
            imageUrl = imageUrl,
            allImageUrls = allImageUrls
        )
    }

    /**
     * ページ内のすべての有効な画像 URL を収集して返す。
     * 先頭要素が最優先のサムネイル候補（OGP メタタグ優先）。
     *
     * 優先度:
     *  1. OGP / Twitter Card メタタグ（複数セレクタで網羅）
     *  2. <link rel="image_src">
     *  3. ページ内の有意な <img> タグ（ロゴ・アバター等を除外）
     */
    private fun extractAllImageUrls(doc: Document): List<String> {
        val baseUri = doc.baseUri()
        val result = mutableListOf<String>()

        // ── 1. メタタグ（優先度順） ──────────────────────────────────
        val metaSelectors = listOf(
            "meta[property=og:image]",
            "meta[property=og:image:secure_url]",
            "meta[property=og:image:url]",
            "meta[name=og:image]",
            "meta[name=twitter:image]",
            "meta[property=twitter:image]",
            "meta[name=twitter:image:src]",
            "meta[property=twitter:image:src]",
            "link[rel=image_src]",
        )
        for (selector in metaSelectors) {
            val attr = if (selector.startsWith("link")) "href" else "content"
            val raw = doc.select(selector).attr(attr).trim()
            if (raw.isNotBlank()) {
                val normalized = normalizeImageUrl(raw, baseUri)
                if (normalized != null && normalized !in result) {
                    Log.d(TAG, "extractAllImageUrls: meta '$selector' → $normalized")
                    result.add(normalized)
                }
            }
        }

        // ── 2. すべての有意な <img> タグ ──────────────────────────────
        doc.select("img[src]").forEach { el ->
            val src = el.attr("abs:src").ifBlank { el.attr("src") }
            val normalized = normalizeImageUrl(src, baseUri) ?: return@forEach
            if (!normalized.startsWith("http")) return@forEach
            val lower = normalized.lowercase()
            if (lower.contains("logo") || lower.contains("avatar") ||
                lower.contains("icon") || lower.contains("sprite") ||
                lower.contains("1x1") || lower.contains("pixel") ||
                lower.contains("badge") || lower.contains("spacer") ||
                lower.contains("tracking") || lower.contains(".svg") ||
                normalized.length >= 600
            ) return@forEach
            if (normalized !in result) result.add(normalized)
        }

        Log.d(TAG, "extractAllImageUrls: found ${result.size} images")
        return result.take(MAX_ALL_IMAGES)
    }

    /**
     * 画像 URL の正規化。
     * - protocol-relative (`//...`) → `https://...`
     * - 相対パス → ベース URL で解決
     * - data: URI / 空文字 / SVG → null
     */
    private fun normalizeImageUrl(raw: String, baseUri: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("data:")) return null

        return when {
            // protocol-relative URL
            trimmed.startsWith("//") -> "https:$trimmed"
            // 絶対 URL
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            // 相対パス → 絶対 URL に変換
            baseUri.isNotBlank() -> runCatching {
                URL(URL(baseUri), trimmed).toString()
            }.getOrNull()
            else -> null
        }
    }

    private fun looksLikeBlockedPage(content: ScrapedContent): Boolean {
        val joined = listOf(content.title, content.description, content.mainText).joinToString(" ")
        return joined.contains("コンテンツを表示できません") ||
            joined.contains("Something went wrong", ignoreCase = true) ||
            joined.contains("JavaScript is not available", ignoreCase = true) ||
            joined.contains("ログイン", ignoreCase = true) && joined.contains("X", ignoreCase = true)
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

        val imageUrl = primary?.imageUrl?.takeIf { it.isNotBlank() }
            ?: xFallback?.imageUrl?.takeIf { it.isNotBlank() }
            ?: ""

        val allImageUrls = primary?.allImageUrls?.takeIf { it.isNotEmpty() }
            ?: xFallback?.allImageUrls?.takeIf { it.isNotEmpty() }
            ?: xFallback?.imageUrl?.takeIf { it.isNotBlank() }?.let { listOf(it) }
            ?: emptyList()

        return ScrapedContent(
            title = title,
            description = description,
            mainText = mainText,
            imageUrl = imageUrl,
            allImageUrls = allImageUrls
        )
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
        mainText = this.take(MAX_CONTENT_CHARS),
        imageUrl = ""
    )

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
