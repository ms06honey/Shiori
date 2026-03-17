package com.example.shiori.core.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/**
 * 他アプリから共有された動画 content:// を、権限切れ前にアプリ内ストレージへ退避する。
 *
 * ShareReceiverActivity は即終了するため、生の content URI を Worker に渡すのではなく
 * 先に filesDir/shared-videos/incoming/ へコピーしてからローカルパスを渡す。
 */
object SharedVideoImportStore {

    data class ImportedVideo(
        val localPath: String,
        val mimeType: String,
        val displayName: String
    )

    suspend fun importToTempFile(
        context: Context,
        uri: Uri,
        mimeTypeHint: String? = null
    ): ImportedVideo? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = (mimeTypeHint ?: resolver.getType(uri)).orEmpty().ifBlank { "video/mp4" }
        val displayName = queryDisplayName(context, uri)
            ?: "shared_video_${System.currentTimeMillis()}"
        val extension = guessExtension(displayName, mimeType)
        val dir = File(context.filesDir, "shared-videos/incoming").apply { mkdirs() }
        val file = File(
            dir,
            "incoming_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}.$extension"
        )

        resolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: return@withContext null

        if (file.length() <= 0L) {
            file.delete()
            return@withContext null
        }

        ImportedVideo(
            localPath = file.absolutePath,
            mimeType = mimeType,
            displayName = displayName
        )
    }

    fun deleteTempFile(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            val file = File(path)
            if (file.exists() && file.absolutePath.contains("shared-videos${File.separator}incoming")) {
                file.delete()
            }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        }.getOrNull()
    }

    private fun guessExtension(displayName: String, mimeType: String): String {
        val fromName = displayName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it.isNotBlank() }
        if (fromName != null) return fromName

        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: "mp4"
    }
}

