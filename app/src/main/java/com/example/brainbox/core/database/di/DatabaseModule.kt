package com.example.brainbox.core.database.di

import android.content.Context
import androidx.room.Room
import com.example.brainbox.core.database.AppDatabase
import com.example.brainbox.core.datastore.EncryptedPrefsManager
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
            // 開発中のスキーマ変更時にデータを破棄して再作成
            .fallbackToDestructiveMigration(true)
            .build()
    }

    @Provides
    fun provideBookmarkDao(database: AppDatabase) = database.bookmarkDao()
}
