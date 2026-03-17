package com.example.brainbox.feature.bookmark.di

import com.example.brainbox.feature.bookmark.data.export.JsonBookmarkExporter
import com.example.brainbox.feature.bookmark.data.work.WorkManagerBookmarkProcessingScheduler
import com.example.brainbox.feature.bookmark.domain.export.BookmarkExporter
import com.example.brainbox.feature.bookmark.domain.scheduler.BookmarkProcessingScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BookmarkPlatformModule {

    @Binds
    @Singleton
    abstract fun bindBookmarkProcessingScheduler(
        impl: WorkManagerBookmarkProcessingScheduler
    ): BookmarkProcessingScheduler

    @Binds
    @Singleton
    abstract fun bindBookmarkExporter(
        impl: JsonBookmarkExporter
    ): BookmarkExporter
}

