package com.example.brainbox.feature.bookmark.data.work

import androidx.work.WorkManager
import com.example.brainbox.feature.bookmark.domain.scheduler.BookmarkProcessingScheduler
import com.example.brainbox.feature.bookmark.worker.ProcessUrlWorker
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkManagerBookmarkProcessingScheduler @Inject constructor(
    private val workManager: WorkManager
) : BookmarkProcessingScheduler {
    override fun enqueueUrl(url: String) {
        workManager.enqueue(ProcessUrlWorker.buildRequest(url))
    }
}

