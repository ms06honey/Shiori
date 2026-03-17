package com.example.brainbox.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.brainbox.feature.bookmark.data.local.BookmarkDao
import com.example.brainbox.feature.bookmark.data.local.BookmarkEntity

@Database(
    entities = [BookmarkEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        const val DATABASE_NAME = "brainbox.db"
    }
}
