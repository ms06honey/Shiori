package com.example.shiori.core.scraper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URL

/**
 * Instagram の公開投稿ページから、OGP サムネイルではなく実メディア URL を優先抽出するヘルパー。
 *
 * 背景:
 * - og:image / twitter:image は中央切り抜き済みの正方形プレビューになりやすい
 * - 投稿本文の埋め込み JSON には display_resources / image_versions2 などの実画像候補が残ることがある
 *
 * 方針:
 * 1. script[type=application/ld+json] を解析
 * 2. 各 <script> 内から shortcode_media / xdt_shortcode_media などの JSON を抽出
 * 3. 候補配列は面積の大きい順に並べ、最大解像度を先頭にする
 */
internal object InstagramMediaExtractor {

    private const val MAX_IMAGES = 20
    private const val MAX_VIDEOS = 8
    private const val MAX_TEXT_CHARS = 3_000

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val mediaRootKeys = listOf(
        "xdt_shortcode_media",
        "shortcode_media",
        "xdt_api__v1__media__shortcode__web_info"
    )

    private val primaryImageKeys = listOf(
        "display_url",
        "display_src",
        "display_uri",
        "image_url",
        "imageUrl"
    )

    private val fallbackImageKeys = listOf(
        "thumbnail_src",
        "thumbnail_url",
        "thumbnailUrl"
    )

    private val videoKeys = listOf(
        "video_url",
        "videoUrl",
        "contentUrl",
        "content_url",
        "playback_url",
        "playbackUrl"
    )

    private val videoContainerKeys = listOf(
        "video_versions",
        "videoVariants",
        "playback_options",
        "playbackOptions",
        "clips_metadata",
        "clipsMetadata",
        "dash_info",
        "dashInfo",
        "video_resources",
        "videoResources"
    )

    fun isInstagramUrl(url: String?): Boolean = runCatching {
        val host = URL(url).host.lowercase()
        host == "instagram.com" ||
            host == "www.instagram.com" ||
            host == "m.instagram.com" ||
            host == "instagr.am" ||
            host == "www.instagr.am"
    }.getOrDefault(false)

    fun extract(doc: Document, pageUrl: String = doc.baseUri()): ScrapedContent? {
        val effectiveUrl = pageUrl.ifBlank { doc.baseUri() }
        if (!isInstagramUrl(effectiveUrl) && !isInstagramUrl(doc.baseUri())) return null

        val primaryImages = linkedSetOf<String>()
        val fallbackImages = linkedSetOf<String>()
        val videos = linkedSetOf<String>()
        val descriptions = mutableListOf<String>()

        doc.select("script[type=application/ld+json]").forEach { script ->
            parseJsonNode(script.data().ifBlank { script.html() })?.let { node ->
                collectMedia(
                    node = node,
                    baseUri = effectiveUrl,
                    primaryImages = primaryImages,
                    fallbackImages = fallbackImages,
                    videos = videos,
                    descriptions = descriptions
                )
            }
        }

        doc.select("script").forEach { script ->
            val scriptText = script.data().ifBlank { script.html() }.trim()
            if (scriptText.isBlank()) return@forEach

            extractEmbeddedJsonNodes(scriptText).forEach { node ->
                collectMedia(
                    node = node,
                    baseUri = effectiveUrl,
                    primaryImages = primaryImages,
                    fallbackImages = fallbackImages,
                    videos = videos,
                    descriptions = descriptions
                )
            }
        }

        val allImageUrls = (primaryImages + fallbackImages).take(MAX_IMAGES)
        val allVideoUrls = videos.take(MAX_VIDEOS)
        val description = descriptions.firstOrNull { it.isNotBlank() }.orEmpty()
        val mainText = descriptions
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .take(MAX_TEXT_CHARS)

        if (allImageUrls.isEmpty() && allVideoUrls.isEmpty()) return null

        val title = doc.select("meta[property=og:title]").attr("content")
            .ifBlank { doc.title() }
            .ifBlank { "Instagramの投稿" }

        return ScrapedContent(
            title = title,
            description = description,
            mainText = mainText,
            imageUrl = allImageUrls.firstOrNull().orEmpty(),
            allImageUrls = allImageUrls,
            videoUrl = allVideoUrls.firstOrNull().orEmpty(),
            allVideoUrls = allVideoUrls
        )
    }

    private fun extractEmbeddedJsonNodes(scriptText: String): List<JsonElement> {
        val results = mutableListOf<JsonElement>()

        parseJsonNode(scriptText)?.let(results::add)

        for (rootKey in mediaRootKeys) {
            val pattern = Regex("""[\"']?$rootKey[\"']?\s*:\s*\{""")
            pattern.findAll(scriptText).forEach { match ->
                val objectStart = scriptText.indexOf('{', match.range.first)
                if (objectStart < 0) return@forEach

                extractBalancedJsonObject(scriptText, objectStart)
                    ?.let(::parseJsonNode)
                    ?.let(results::add)
            }
        }

        return results
    }

    private fun parseJsonNode(raw: String): JsonElement? {
        val trimmed = raw.trim().removeSuffix(";")
        if (trimmed.isBlank()) return null

        return when {
            trimmed.startsWith("{") || trimmed.startsWith("[") ->
                runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
            else -> null
        }
    }

    private fun extractBalancedJsonObject(source: String, objectStart: Int): String? {
        if (objectStart !in source.indices || source[objectStart] != '{') return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in objectStart until source.length) {
            val char = source[index]
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                !inString && char == '{' -> depth++
                !inString && char == '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(objectStart, index + 1)
                    }
                }
            }
        }

        return null
    }

    private fun collectMedia(
        node: JsonElement?,
        baseUri: String,
        primaryImages: MutableSet<String>,
        fallbackImages: MutableSet<String>,
        videos: MutableSet<String>,
        descriptions: MutableList<String>,
        sourceKey: String? = null,
        depth: Int = 0
    ) {
        if (node == null || depth > 14) return

        when (node) {
            is JsonObject -> {
                val nodeLooksLikeVideo = sourceKey.looksLikeVideoKey() || node.looksLikeVideoObject()

                collectDescriptions(node, descriptions)

                collectCandidateArray(
                    node.objectOrNull("image_versions2")?.arrayOrNull("candidates"),
                    baseUri,
                    primaryImages
                )
                collectCandidateArray(node.arrayOrNull("display_resources"), baseUri, primaryImages)
                collectCandidateArray(node.arrayOrNull("thumbnail_resources"), baseUri, fallbackImages)

                primaryImageKeys.forEach { key ->
                    addImage(primaryImages, node.stringOrEmpty(key), baseUri)
                }
                fallbackImageKeys.forEach { key ->
                    addImage(fallbackImages, node.stringOrEmpty(key), baseUri)
                }

                collectImageField(node["image"], baseUri, primaryImages, fallbackImages)
                collectImageField(node["images"], baseUri, primaryImages, fallbackImages)

                videoKeys.forEach { key ->
                    addVideo(videos, node.stringOrEmpty(key), baseUri, force = true)
                }

                if (nodeLooksLikeVideo) {
                    addVideo(videos, node.stringOrEmpty("url"), baseUri, force = true)
                    addVideo(videos, node.stringOrEmpty("src"), baseUri, force = true)
                }

                videoContainerKeys.forEach { key ->
                    collectVideoField(node[key], baseUri, videos, key, force = true)
                }

                if (node.booleanOrFalse("is_video")) {
                    addVideo(videos, node.stringOrEmpty("video_url"), baseUri, force = true)
                }

                val iterator = node.keys.iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    collectMedia(
                        node = node[key],
                        baseUri = baseUri,
                        primaryImages = primaryImages,
                        fallbackImages = fallbackImages,
                        videos = videos,
                        descriptions = descriptions,
                        sourceKey = key,
                        depth = depth + 1
                    )
                }
            }

            is JsonArray -> {
                for (index in node.indices) {
                    collectMedia(
                        node = node[index],
                        baseUri = baseUri,
                        primaryImages = primaryImages,
                        fallbackImages = fallbackImages,
                        videos = videos,
                        descriptions = descriptions,
                        sourceKey = sourceKey,
                        depth = depth + 1
                    )
                }
            }

            else -> Unit
        }
    }

    private fun collectDescriptions(node: JsonObject, descriptions: MutableList<String>) {
        addDescription(descriptions, node.stringOrEmpty("caption"))
        addDescription(descriptions, node.stringOrEmpty("accessibility_caption"))
        addDescription(descriptions, node.stringOrEmpty("title"))
        addDescription(descriptions, node.stringOrEmpty("description"))

        node.objectOrNull("edge_media_to_caption")
            ?.arrayOrNull("edges")
            ?.let { edges ->
                for (index in edges.indices) {
                    val text = (edges[index] as? JsonObject)
                        ?.objectOrNull("node")
                        ?.stringOrEmpty("text")
                        .orEmpty()
                    addDescription(descriptions, text)
                }
            }
    }

    private fun collectImageField(
        value: JsonElement?,
        baseUri: String,
        primaryImages: MutableSet<String>,
        fallbackImages: MutableSet<String>
    ) {
        when (value) {
            is JsonPrimitive -> value.contentOrNull?.let { addImage(primaryImages, it, baseUri) }
            is JsonArray -> {
                for (index in value.indices) {
                    collectImageField(value[index], baseUri, primaryImages, fallbackImages)
                }
            }

            is JsonObject -> {
                addImage(primaryImages, value.stringOrEmpty("url"), baseUri)
                addImage(primaryImages, value.stringOrEmpty("src"), baseUri)
                addImage(fallbackImages, value.stringOrEmpty("thumbnailUrl"), baseUri)
            }

            else -> Unit
        }
    }

    private fun collectVideoField(
        value: JsonElement?,
        baseUri: String,
        videos: MutableSet<String>,
        sourceKey: String? = null,
        force: Boolean = false
    ) {
        when (value) {
            is JsonPrimitive -> value.contentOrNull?.let {
                addVideo(videos, it, baseUri, force = force || sourceKey.looksLikeVideoKey())
            }

            is JsonArray -> {
                for (index in value.indices) {
                    collectVideoField(
                        value = value[index],
                        baseUri = baseUri,
                        videos = videos,
                        sourceKey = sourceKey,
                        force = force
                    )
                }
            }

            is JsonObject -> {
                val objectLooksLikeVideo = force || sourceKey.looksLikeVideoKey() || value.looksLikeVideoObject()
                addVideo(videos, value.stringOrEmpty("url"), baseUri, force = objectLooksLikeVideo)
                addVideo(videos, value.stringOrEmpty("src"), baseUri, force = objectLooksLikeVideo)
                addVideo(videos, value.stringOrEmpty("playback_url"), baseUri, force = true)
                addVideo(videos, value.stringOrEmpty("playbackUrl"), baseUri, force = true)
                addVideo(videos, value.stringOrEmpty("video_url"), baseUri, force = true)

                val iterator = value.keys.iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    collectVideoField(
                        value = value[key],
                        baseUri = baseUri,
                        videos = videos,
                        sourceKey = key,
                        force = objectLooksLikeVideo
                    )
                }
            }

            else -> Unit
        }
    }

    private fun collectCandidateArray(
        array: JsonArray?,
        baseUri: String,
        target: MutableSet<String>
    ) {
        if (array == null) return

        val ordered = mutableListOf<Pair<Long, String>>()
        for (index in array.indices) {
            val candidate = array[index] as? JsonObject ?: continue
            val normalized = normalizeUrl(
                raw = candidate.stringOrEmpty("url").ifBlank {
                    candidate.stringOrEmpty("src").ifBlank {
                        candidate.stringOrEmpty("display_url")
                    }
                },
                baseUri = baseUri
            ) ?: continue

            val width = candidate.intOrZero("width").takeIf { it > 0 }
                ?: candidate.intOrZero("config_width")
            val height = candidate.intOrZero("height").takeIf { it > 0 }
                ?: candidate.intOrZero("config_height")
            ordered += ((width.toLong() * height.toLong()).coerceAtLeast(0L)) to normalized
        }

        ordered
            .sortedByDescending { it.first }
            .forEach { (_, url) -> target.add(url) }
    }

    private fun addImage(target: MutableSet<String>, raw: String, baseUri: String) {
        normalizeUrl(raw, baseUri)
            ?.takeUnless(::looksLikeVideoUrl)
            ?.let(target::add)
    }

    private fun addVideo(
        target: MutableSet<String>,
        raw: String,
        baseUri: String,
        force: Boolean = false
    ) {
        normalizeUrl(raw, baseUri)
            ?.takeIf { force || looksLikeVideoUrl(it) }
            ?.let(target::add)
    }

    private fun addDescription(target: MutableList<String>, value: String) {
        val normalized = value
            .replace(Regex("""\s+"""), " ")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return

        if (normalized !in target) {
            target += normalized
        }
    }

    private fun normalizeUrl(raw: String, baseUri: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("data:")) return null
        if (trimmed.startsWith("javascript:", ignoreCase = true)) return null

        val resolved = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            baseUri.isNotBlank() -> runCatching {
                URL(URL(baseUri), trimmed).toString()
            }.getOrNull()
            else -> null
        } ?: return null

        return runCatching {
            val uri = URI(resolved)
            URI(uri.scheme, uri.authority, uri.path, uri.query, null).toString()
        }.getOrElse {
            resolved.substringBefore('#')
        }
    }

    private fun looksLikeVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".mp4") ||
            lower.contains(".mov") ||
            lower.contains(".webm") ||
            lower.contains(".m4v") ||
            lower.contains(".m3u8") ||
            lower.contains("mime_type=video") ||
            lower.contains("content_type=video") ||
            lower.contains("playback") ||
            lower.contains("video_dash") ||
            lower.contains("/vp/") ||
            lower.contains("instagram.f") && lower.contains("video")
    }

    private fun String?.looksLikeVideoKey(): Boolean {
        val lower = this?.lowercase().orEmpty()
        return lower.contains("video") ||
            lower.contains("playback") ||
            lower.contains("dash") ||
            lower.contains("stream") ||
            lower.contains("variant")
    }

    private fun JsonObject.looksLikeVideoObject(): Boolean {
        if (booleanOrFalse("is_video")) return true

        val hints = listOf(
            stringOrEmpty("mime_type"),
            stringOrEmpty("content_type"),
            stringOrEmpty("contentType"),
            stringOrEmpty("type")
        ).joinToString(" ").lowercase()

        return hints.contains("video") ||
            hints.contains("mp4") ||
            hints.contains("dash") ||
            hints.contains("mpegurl") ||
            containsKey("video_versions") ||
            containsKey("playback_url") ||
            containsKey("playbackUrl") ||
            containsKey("video_url")
    }

    private fun JsonObject.stringOrEmpty(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.booleanOrFalse(key: String): Boolean =
        this[key]?.jsonPrimitive?.booleanOrNull ?: false

    private fun JsonObject.intOrZero(key: String): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: 0
}
