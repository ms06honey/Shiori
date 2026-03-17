package com.example.shiori.feature.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.shiori.feature.bookmark.domain.scheduler.BookmarkProcessingScheduler
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

    /**
     * テキスト中から URL を優先順位付きで抽出する。
     *
     * 優先度:
     * 1. x.com / twitter.com の直接ツイート URL（/status/ID 形式）
     *    → ツイート本文に記事 URL が含まれていても誤検出しない
     * 2. t.co 短縮 URL（X のツイート共有で多用）
     * 3. その他の最初の https:// URL
     */
    private fun String.extractUrl(): String? {
        // 優先 1: 直接ツイート URL
        Regex("""https?://(?:www\.|mobile\.)?(?:x\.com|twitter\.com)/[^\s]+/status/\d+[^\s]*""")
            .find(this)?.value?.let { return it }
        // 優先 2: t.co 短縮 URL
        Regex("""https?://t\.co/[^\s]+""")
            .find(this)?.value?.let { return it }
        // 優先 3: その他の最初の URL
        return Regex("""https?://[^\s]+""").find(this)?.value
    }
}
