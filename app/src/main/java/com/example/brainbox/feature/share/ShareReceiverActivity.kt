package com.example.brainbox.feature.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.brainbox.feature.bookmark.domain.scheduler.BookmarkProcessingScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * PDF仕様書 §3.1 / §6 UXフロー に準拠した透過 Activity。
 *
 * ・UI を一切表示しない（透明テーマ適用）
 * ・Share Intent から URL を取得
 * ・ProcessUrlWorker を WorkManager にキュー登録
 * ・即座に finish() → 呼び出し元アプリへ復帰
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    @Inject
    lateinit var bookmarkProcessingScheduler: BookmarkProcessingScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharePayload = intent
            ?.takeIf { it.action == Intent.ACTION_SEND && it.type == "text/plain" }
            ?.let { shareIntent ->
                listOfNotNull(
                    shareIntent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf(String::isNotBlank),
                    shareIntent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf(String::isNotBlank)
                ).joinToString("\n").takeIf(String::isNotBlank)
            }

        val url = sharePayload?.extractUrl()
        val sourcePackage = referrer?.host?.takeIf { it.isNotBlank() }

        if (url != null) {
            bookmarkProcessingScheduler.enqueueUrl(
                url = url,
                sharedText = sharePayload,
                sourcePackage = sourcePackage
            )
        }

        // ★ setContent() を呼ばずに即終了 → 元のアプリへ戻る
        finish()
    }

    /** テキスト中から最初の https?:// URL を抽出 */
    private fun String.extractUrl(): String? =
        Regex("""https?://[^\s]+""").find(this)?.value
}
