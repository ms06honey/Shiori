package com.example.shiori.feature.bookmark.data.export

import android.content.Context
import android.net.Uri
import com.example.shiori.feature.bookmark.domain.export.BookmarkExporter
import com.example.shiori.feature.bookmark.domain.model.Bookmark
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JsonBookmarkExporter @Inject constructor(
    @param:ApplicationContext private val context: Context
) : BookmarkExporter {

    override suspend fun export(uri: Uri, bookmarks: List<Bookmark>) {
        val json = buildExportJson(bookmarks)
        context.contentResolver.openOutputStream(uri)?.use {
            it.write(json.toByteArray(StandardCharsets.UTF_8))
        } ?: error("出力先を開けませんでした")
    }

    private fun buildExportJson(bookmarks: List<Bookmark>): String {
        val array = JSONArray().apply {
            bookmarks.forEach { b ->
                put(JSONObject().apply {
                    put("id", b.id)
                    put("url", b.url)
                    put("title", b.title)
                    put("summary", b.summary)
                    put("category", b.category)
                    put("tags", JSONArray(b.tags))
                    put("createdAt", b.createdAt)
                })
            }
        }
        return JSONObject().apply {
            put("exportedAt", System.currentTimeMillis())
            put("count", bookmarks.size)
            put("bookmarks", array)
        }.toString(2)
    }
}

