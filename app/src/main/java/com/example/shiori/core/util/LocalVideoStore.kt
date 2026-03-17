package com.example.shiori.core.util

import android.content.Context
import android.util.Log
import com.example.shiori.core.scraper.WebScraper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 動画候補 URL から、実際に保存可能な動画本体をローカルへ保存するユーティリティ。
 *
 * 保存先: context.filesDir/videos/{bookmarkId}/video.{ext}
 *
 * 方針:
 * - 候補 URL を順に試す
 * - Content-Type / URL 拡張子から「実動画ファイル」か判定
 * - HLS(m3u8) や HTML ページはローカル保存対象外としてスキップ
 */
@Singleton
class LocalVideoStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "LocalVideoStore"
    }

    suspend fun downloadFirstSupported(
        bookmarkId: Long,
        videoUrls: List<String>,
        referer: String? = null
    ): String? = withContext(Dispatchers.IO) {
        if (videoUrls.isEmpty()) return@withContext null

        val dir = File(context.filesDir, "videos/$bookmarkId")
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()

        for ((index, url) in videoUrls.distinct().withIndex()) {
            val result = runCatching {
                downloadSingle(dir, url, referer)
            }.onFailure {
                Log.w(TAG, "downloadFirstSupported: candidate[$index] failed $url : ${it.message}")
            }.getOrNull()

            if (result != null) {
                Log.d(TAG, "downloadFirstSupported: saved from candidate[$index] $url -> $result")
                return@withContext result
            }
        }

        Log.w(TAG, "downloadFirstSupported: no downloadable video candidates")
        null
    }

    suspend fun importFromSharedPath(
        bookmarkId: Long,
        sharedPath: String,
        mimeType: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val sourceFile = File(sharedPath)
        if (!sourceFile.exists() || sourceFile.length() <= 0L) {
            Log.w(TAG, "importFromSharedPath: source file missing $sharedPath")
            SharedVideoImportStore.deleteTempFile(sharedPath)
            return@withContext null
        }

        val dir = prepareBookmarkDir(bookmarkId)
        val extension = guessVideoExtension(mimeType.orEmpty(), sourceFile.name)
        val destFile = File(dir, "video.$extension")

        return@withContext runCatching {
            sourceFile.copyTo(destFile, overwrite = true)
            if (destFile.length() <= 0L) {
                destFile.delete()
                null
            } else {
                SharedVideoImportStore.deleteTempFile(sharedPath)
                destFile.absolutePath
            }
        }.onFailure {
            Log.e(TAG, "importFromSharedPath failed for $sharedPath: ${it.message}", it)
        }.getOrNull()
    }

    private fun prepareBookmarkDir(bookmarkId: Long): File {
        val dir = File(context.filesDir, "videos/$bookmarkId")
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    fun deleteAll(bookmarkId: Long) {
        val dir = File(context.filesDir, "videos/$bookmarkId")
        if (dir.exists()) {
            dir.deleteRecursively()
            Log.d(TAG, "Deleted videos dir for bookmarkId=$bookmarkId")
        }
    }

    private fun downloadSingle(
        dir: File,
        url: String,
        referer: String?
    ): String? {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", WebScraper.CHROME_UA)
            .header("Accept", "video/mp4,video/*;q=0.95,application/octet-stream;q=0.8,*/*;q=0.5")

        if (!referer.isNullOrBlank()) {
            requestBuilder.header("Referer", referer)
        }

        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "downloadSingle: HTTP ${response.code} for $url")
                return null
            }

            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val finalUrl = response.request.url.toString()

            // HLS プレイリストや HTML はローカル保存対象外
            if (contentType.contains("mpegurl") || finalUrl.contains(".m3u8", ignoreCase = true)) {
                Log.d(TAG, "downloadSingle: skip HLS candidate $finalUrl ($contentType)")
                return null
            }
            if (contentType.startsWith("text/html")) {
                Log.d(TAG, "downloadSingle: skip HTML candidate $finalUrl")
                return null
            }

            val looksLikeBinaryVideo = contentType.startsWith("video/") ||
                finalUrl.contains(".mp4", ignoreCase = true) ||
                finalUrl.contains(".mov", ignoreCase = true) ||
                finalUrl.contains(".webm", ignoreCase = true) ||
                finalUrl.contains(".m4v", ignoreCase = true)

            if (!looksLikeBinaryVideo) {
                Log.d(TAG, "downloadSingle: unsupported candidate $finalUrl ($contentType)")
                return null
            }

            val ext = guessVideoExtension(contentType, finalUrl)
            val file = File(dir, "video.$ext")
            response.body?.byteStream()?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            if (file.length() <= 0L) {
                file.delete()
                return null
            }

            return file.absolutePath
        }
    }

    private fun guessVideoExtension(contentType: String, url: String): String {
        return when {
            contentType.contains("webm") || url.contains(".webm", ignoreCase = true) -> "webm"
            contentType.contains("quicktime") || url.contains(".mov", ignoreCase = true) -> "mov"
            contentType.contains("x-m4v") || url.contains(".m4v", ignoreCase = true) -> "m4v"
            else -> "mp4"
        }
    }
}

