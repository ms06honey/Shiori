package com.example.shiori.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.shiori.feature.bookmark.data.local.BookmarkDao
import com.example.shiori.feature.bookmark.data.local.BookmarkEntity

@Database(
    entities = [BookmarkEntity::class],
    version = 7,
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

        /**
         * v3 → v4 マイグレーション
         * ・localImagePaths カラム追加（ローカル保存した画像パスのカンマ区切りリスト）
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE bookmarks ADD COLUMN localImagePaths TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v4 → v5 マイグレーション
         * ・videoUrl カラム追加（X / Web から抽出した動画 URL）
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE bookmarks ADD COLUMN videoUrl TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v5 → v6 マイグレーション
         * ・localVideoPath カラム追加（ローカル保存した動画ファイルパス）
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE bookmarks ADD COLUMN localVideoPath TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v6 → v7 マイグレーション
         * ・sourcePackage カラム追加（共有元アプリのパッケージ名）
         * ・sourceAppName カラム追加（共有元アプリの表示名）
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE bookmarks ADD COLUMN sourcePackage TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE bookmarks ADD COLUMN sourceAppName TEXT NOT NULL DEFAULT ''"
                )
            }
        }
    }
}
