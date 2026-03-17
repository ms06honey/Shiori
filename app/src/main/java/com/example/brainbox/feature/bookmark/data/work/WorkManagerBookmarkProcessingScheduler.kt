package com.example.brainbox.feature.bookmark.data.work

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.example.brainbox.feature.bookmark.domain.scheduler.BookmarkProcessingScheduler
import com.example.brainbox.feature.bookmark.worker.ProcessUrlWorker
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkManagerBookmarkProcessingScheduler @Inject constructor(
    private val workManager: WorkManager
) : BookmarkProcessingScheduler {
    override fun enqueueUrl(
        url: String,
        sharedText: String?,
        sourcePackage: String?
    ) {
        // URL をキーに KEEP: 同じ URL の処理が既にキューにあれば重複登録しない
        workManager.enqueueUniqueWork(
            "process_url_${url.hashCode()}",
            ExistingWorkPolicy.KEEP,
            ProcessUrlWorker.buildRequest(url, sharedText, sourcePackage)
        )
    }

    override fun reanalyzeById(id: Long, url: String) {
        // REPLACE: 同一ワーク名で強制的に再投入（進行中のものをキャンセルして再開）
        workManager.enqueueUniqueWork(
            "reanalyze_${id}",
            ExistingWorkPolicy.REPLACE,
            ProcessUrlWorker.buildReanalyzeRequest(id, url)
        )
    }
}

