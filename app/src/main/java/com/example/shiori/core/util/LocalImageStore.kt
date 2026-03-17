package com.example.shiori.core.util

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.example.shiori.core.datastore.EncryptedPrefsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ページから取得した画像を端末内ストレージに保存するユーティリティ。
 * 保存先: context.filesDir/images/{bookmarkId}/{index}.{ext}
 * - 先頭ファイル (index=0) がサムネイル用
 */
@Singleton
class LocalImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val encryptedPrefsManager: EncryptedPrefsManager
) {
    companion object {
        private const val TAG = "LocalImageStore"
        /** 1 ブックマークあたりの最大保存枚数 */
        private const val MAX_IMAGES = 20
    }

    /**
     * 画像 URL リストを並列ではなく順番にダウンロードしてローカルに保存する。
     * @param bookmarkId 保存先ディレクトリ名に使用するブックマーク ID
     * @param imageUrls  ダウンロードする画像 URL リスト（先頭がサムネイル優先度最高）
     * @return 保存に成功したファイルの絶対パスリスト（先頭がサムネイル用）
     */
    suspend fun downloadAll(bookmarkId: Long, imageUrls: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            if (imageUrls.isEmpty()) return@withContext emptyList()

            val dir = File(context.filesDir, "images/$bookmarkId")
            dir.mkdirs()

            imageUrls.take(MAX_IMAGES).mapIndexedNotNull { index, url ->
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/126.0.0.0 Safari/537.36"
                        )
                        .header(
                            "Accept",
                            "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
                        )
                        .build()

                    val bytes = okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.w(TAG, "HTTP ${response.code} for image[$index]: $url")
                            null
                        } else {
                            val contentType = response.header("Content-Type").orEmpty().lowercase()
                            val finalUrl = response.request.url.toString().lowercase()
                            val isImage = contentType.startsWith("image/") ||
                                finalUrl.endsWith(".jpg") || finalUrl.endsWith(".jpeg") ||
                                finalUrl.endsWith(".png") || finalUrl.endsWith(".gif") ||
                                finalUrl.endsWith(".webp")
                            val looksLikeVideo = contentType.startsWith("video/") ||
                                finalUrl.contains(".mp4") || finalUrl.contains(".m3u8") ||
                                finalUrl.contains(".mov") || finalUrl.contains(".webm")

                            if (!isImage || looksLikeVideo || contentType.startsWith("text/html")) {
                                Log.d(TAG, "Skip non-image candidate image[$index]: $finalUrl ($contentType)")
                                null
                            } else {
                                response.body?.bytes()
                            }
                        }
                    } ?: return@mapIndexedNotNull null

                    if (bytes.isEmpty()) return@mapIndexedNotNull null

                    val dimensions = decodeImageDimensions(bytes)
                    val isThumbnailCandidate = index == 0
                    val isFilterEnabled = encryptedPrefsManager.isMinImageFilterEnabled()
                    if (!isThumbnailCandidate && isFilterEnabled && dimensions != null) {
                        val (width, height) = dimensions
                        val threshold = encryptedPrefsManager.getMinImageSizeThresholdPx()
                        val mode = encryptedPrefsManager.getMinImageSizeMode()
                        if (shouldSkipImage(width, height, threshold, mode)) {
                            Log.d(
                                TAG,
                                "Skip small image[$index] ${width}x${height}px threshold=$threshold mode=$mode url=$url"
                            )
                            return@mapIndexedNotNull null
                        }
                    }

                    val ext = guessExtension(url)
                    val file = File(dir, "$index.$ext")
                    file.writeBytes(bytes)
                    Log.d(TAG, "Saved image[$index] ${bytes.size}B → ${file.absolutePath}")
                    file.absolutePath
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download image[$index] $url: ${e.message}")
                    null
                }
            }
        }

    /**
     * ブックマーク削除時にローカル保存済み画像をすべて削除する。
     */
    fun deleteAll(bookmarkId: Long) {
        val dir = File(context.filesDir, "images/$bookmarkId")
        if (dir.exists()) {
            dir.deleteRecursively()
            Log.d(TAG, "Deleted images dir for bookmarkId=$bookmarkId")
        }
    }

    private fun guessExtension(url: String): String {
        val path = url.substringBefore("?").lowercase()
        return when {
            path.endsWith(".png")  -> "png"
            path.endsWith(".gif")  -> "gif"
            path.endsWith(".webp") -> "webp"
            else                   -> "jpg"
        }
    }

    private fun decodeImageDimensions(bytes: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val width = options.outWidth
        val height = options.outHeight
        return if (width > 0 && height > 0) width to height else null
    }

    private fun shouldSkipImage(
        width: Int,
        height: Int,
        threshold: Int,
        mode: EncryptedPrefsManager.ImageSizeFilterMode
    ): Boolean {
        val normalizedThreshold = threshold.coerceAtLeast(1)
        return when (mode) {
            EncryptedPrefsManager.ImageSizeFilterMode.BOTH ->
                width <= normalizedThreshold && height <= normalizedThreshold

            EncryptedPrefsManager.ImageSizeFilterMode.WIDTH ->
                width <= normalizedThreshold

            EncryptedPrefsManager.ImageSizeFilterMode.HEIGHT ->
                height <= normalizedThreshold
        }
    }
}

