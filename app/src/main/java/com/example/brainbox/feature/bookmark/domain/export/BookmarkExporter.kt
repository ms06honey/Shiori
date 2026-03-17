package com.example.brainbox.feature.bookmark.domain.export

import android.net.Uri
import com.example.brainbox.feature.bookmark.domain.model.Bookmark

interface BookmarkExporter {
    suspend fun export(uri: Uri, bookmarks: List<Bookmark>)
}

