package com.example.shiori.feature.bookmark.di

import com.example.shiori.feature.bookmark.data.repository.BookmarkRepositoryImpl
import com.example.shiori.feature.bookmark.domain.repository.BookmarkRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BookmarkModule {

    @Binds
    @Singleton
    abstract fun bindBookmarkRepository(
        impl: BookmarkRepositoryImpl
    ): BookmarkRepository
}

