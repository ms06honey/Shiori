package com.example.brainbox.feature.bookmark.domain.scheduler

interface BookmarkProcessingScheduler {
    fun enqueueUrl(url: String)
}

