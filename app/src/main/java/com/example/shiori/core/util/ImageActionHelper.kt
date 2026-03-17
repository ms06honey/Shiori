package com.example.shiori.core.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ローカル保存済み画像に対する操作ユーティリティ。
 *
 * - [downloadToGallery]  : Pictures/BrainBox/ フォルダへ保存
 * - [copyToClipboard]    : FileProvider 経由で content URI を取得し
 *                          ClipboardManager に画像データとしてセット
 */
object ImageActionHelper {

    private const val TAG = "ImageActionHelper"
    private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

    /**
     * ローカルファイルをデバイスのギャラリー（Pictures/BrainBox/）へコピーする。
     * Android 10 (API 29) 以降は MediaStore API を使用。
     * @return 成功時 true
     */
    suspend fun downloadToGallery(context: Context, filePath: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val srcFile = File(filePath)
                if (!srcFile.exists()) {
                    Log.w(TAG, "Source file not found: $filePath")
                    return@withContext false
                }

                val mimeType = guessMimeType(filePath)
                val displayName = srcFile.name

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+: MediaStore 経由（WRITE_EXTERNAL_STORAGE 不要）
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                        put(MediaStore.Images.Media.RELATIVE_PATH,
                            "${Environment.DIRECTORY_PICTURES}/BrainBox")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: return@withContext false

                    resolver.openOutputStream(uri)?.use { out ->
                        srcFile.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } else {
                    // Android 9 以下: 直接 Pictures ディレクトリへ書き込み
                    @Suppress("DEPRECATION")
                    val destDir = File(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_PICTURES
                        ),
                        "BrainBox"
                    )
                    destDir.mkdirs()
                    srcFile.copyTo(File(destDir, displayName), overwrite = true)
                }

                Log.d(TAG, "downloadToGallery: saved $displayName")
                true
            } catch (e: Exception) {
                Log.e(TAG, "downloadToGallery failed for $filePath: ${e.message}", e)
                false
            }
        }

    /**
     * ローカルファイルを content:// URI に変換してクリップボードにコピーする。
     * 対応アプリ（Obsidian、Notion 等）がペーストすると画像として扱われる。
     * @return 成功時 true
     */
    fun copyToClipboard(context: Context, filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                Log.w(TAG, "File not found for clipboard: $filePath")
                return false
            }

            val authority = context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX
            val uri = FileProvider.getUriForFile(context, authority, file)
            val mimeType = guessMimeType(filePath)

            val clip = ClipData.newUri(context.contentResolver, file.name, uri)
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(clip)

            Log.d(TAG, "copyToClipboard: $uri (${mimeType})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "copyToClipboard failed for $filePath: ${e.message}", e)
            false
        }
    }

    private fun guessMimeType(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".png")  -> "image/png"
            lower.endsWith(".gif")  -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            else                    -> "image/jpeg"
        }
    }
}

