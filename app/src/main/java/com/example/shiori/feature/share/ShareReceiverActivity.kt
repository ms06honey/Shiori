package com.example.shiori.feature.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.example.shiori.core.util.SharedVideoImportStore
import com.example.shiori.feature.bookmark.domain.scheduler.BookmarkProcessingScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PDF仕様書 §3.1 / §6 UXフロー に準拠した透過 Activity。
 *
 * ・UI を一切表示しない（透明テーマ適用）
 * ・Share Intent から URL または共有動画本体を取得
 * ・ProcessUrlWorker を WorkManager にキュー登録
 * ・即座に finish() → 呼び出し元アプリへ復帰
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    @Inject
    lateinit var bookmarkProcessingScheduler: BookmarkProcessingScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val shareIntent = intent?.takeIf { it.action == Intent.ACTION_SEND }
            val mimeType = shareIntent?.type.orEmpty()
            val sharePayload = shareIntent?.toSharePayload()
            val url = sharePayload?.extractUrl()
            val sourcePackage = referrer?.host?.takeIf { it.isNotBlank() }
            val sourceAppName = resolveSourceAppName(sourcePackage)
            val importedVideo = shareIntent
                ?.takeIf { mimeType.startsWith("video/") || it.hasStreamExtra() }
                ?.extractStreamUri()
                ?.let { uri -> SharedVideoImportStore.importToTempFile(this@ShareReceiverActivity, uri, mimeType) }

            if (url != null || importedVideo != null) {
                val effectiveUrl = url ?: "shared-video://imported/${System.currentTimeMillis()}"
                val effectiveSharedText = sharePayload
                    ?: importedVideo?.displayName
                        ?.substringBeforeLast('.')
                        ?.takeIf(String::isNotBlank)

                bookmarkProcessingScheduler.enqueueUrl(
                    url = effectiveUrl,
                    sharedText = effectiveSharedText,
                    sourcePackage = sourcePackage,
                    sourceAppName = sourceAppName,
                    sharedLocalVideoPath = importedVideo?.localPath,
                    sharedMimeType = importedVideo?.mimeType,
                    sharedTitleHint = importedVideo?.displayName
                )
            }

            // ★ setContent() を呼ばずに即終了 → 元のアプリへ戻る
            finish()
        }
    }

    private fun Intent.toSharePayload(): String? =
        listOfNotNull(
            getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf(String::isNotBlank),
            getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf(String::isNotBlank)
        ).joinToString("\n").takeIf(String::isNotBlank)

    private fun Intent.hasStreamExtra(): Boolean = extractStreamUri() != null

    private fun Intent.extractStreamUri(): Uri? =
        IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)

    private fun resolveSourceAppName(packageName: String?): String? {
        val normalizedPackage = packageName?.trim()?.takeIf(String::isNotBlank) ?: return null
        return runCatching {
            val appInfo = packageManager.getApplicationInfo(normalizedPackage, 0)
            packageManager.getApplicationLabel(appInfo)
                ?.toString()
                ?.trim()
                ?.takeIf(String::isNotBlank)
        }.getOrNull() ?: normalizedPackage
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
