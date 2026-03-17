package com.example.shiori.core.database.di

import android.content.Context
import androidx.room.Room
import com.example.shiori.core.database.AppDatabase
import com.example.shiori.core.datastore.EncryptedPrefsManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
        encryptedPrefsManager: EncryptedPrefsManager
    ): AppDatabase {
        // SQLCipher によるデータベース暗号化
        val passphrase: ByteArray = SQLiteDatabase.getBytes(
            encryptedPrefsManager.getOrCreateDbPassphrase().toCharArray()
        )
        val factory = SupportFactory(passphrase)

        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .openHelperFactory(factory)
            // v2→v6 の各マイグレーション（既存データを保持するカラム追加のみ）
            .addMigrations(
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7
            )
            // 定義されていないパス（v1→v3 等）への安全策
            .fallbackToDestructiveMigration(true)
            .build()
    }

    @Provides
    fun provideBookmarkDao(database: AppDatabase) = database.bookmarkDao()
}
