package com.example.shiori.feature.bookmark.domain.scheduler

interface BookmarkProcessingScheduler {
    fun enqueueUrl(
        url: String,
        sharedText: String? = null,
        sourcePackage: String? = null,
        sharedLocalVideoPath: String? = null,
        sharedMimeType: String? = null,
        sharedTitleHint: String? = null
    )
    /** 既存ブックマークを再解析する（REPLACE ポリシーで強制再投入） */
    fun reanalyzeById(id: Long, url: String)
}

