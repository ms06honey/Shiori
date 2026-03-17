package com.example.shiori.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.shiori.feature.bookmark.data.local.BookmarkDao
import com.example.shiori.feature.bookmark.data.local.BookmarkEntity

@Database(
    entities = [BookmarkEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        const val DATABASE_NAME = "brainbox.db"

        /**
         * v2 → v3 マイグレーション
         * ・userMemo    カラム追加（ユーザーメモ）
         * ・thumbnailUrl カラム追加（サイト画像 URL）
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE bookmarks ADD COLUMN userMemo TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE bookmarks ADD COLUMN thumbnailUrl TEXT NOT NULL DEFAULT ''"
                )
            }
        }
    }
}
