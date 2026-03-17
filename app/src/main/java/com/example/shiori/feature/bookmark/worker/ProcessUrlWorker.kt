package com.example.shiori.feature.bookmark.worker

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.example.shiori.BuildConfig
import com.example.shiori.MainActivity
import com.example.shiori.R
import com.example.shiori.core.datastore.EncryptedPrefsManager
import com.example.shiori.core.scraper.ScrapedContent
import com.example.shiori.core.scraper.WebScraper
import com.example.shiori.core.util.LocalImageStore
import com.example.shiori.core.util.LocalVideoStore
import com.example.shiori.core.util.NotificationConstants
import com.example.shiori.core.util.NotificationIds
import com.example.shiori.feature.bookmark.domain.model.buildStoredAiSummary
import com.example.shiori.feature.bookmark.domain.repository.BookmarkRepository
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PDF仕様書 §3.1 / §3.2 に準拠したバックグラウンド処理 Worker。
 *
 * 処理フロー:
 *  1. placeholder を DB に保存（URL だけ即保存）
 *  2. フォアグラウンド通知「AIが解析中...」を表示（Expedited WorkManager）
 *  3. Jsoup でスクレイプ → ノイズ除去 → 本文テキスト取得
 *  4. SPA/JS サイトは OGP フォールバック（og:title / og:description）
 *  5. Gemini API に送信 → JSON レスポンス解析
 *  6. DB を最終データで更新
 *  7. 完了通知「保存しました: [title]」
 */
@HiltWorker
class ProcessUrlWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: BookmarkRepository,
    private val webScraper: WebScraper,
    private val encryptedPrefsManager: EncryptedPrefsManager,
    private val localImageStore: LocalImageStore,
    private val localVideoStore: LocalVideoStore
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_URL = "url"
        const val KEY_SHARED_TEXT = "shared_text"
        const val KEY_SOURCE_PACKAGE = "source_package"
        const val KEY_SHARED_LOCAL_VIDEO_PATH = "shared_local_video_path"
        const val KEY_SHARED_MIME_TYPE = "shared_mime_type"
        const val KEY_SHARED_TITLE_HINT = "shared_title_hint"
        /** 再解析時に既存ブックマークIDを渡すキー。設定されていれば新規作成しない。 */
        const val KEY_EXISTING_ID = "existing_id"
        private const val TAG = "ProcessUrlWorker"

        /** プレースホルダーや空のキーを弾いて有効なAPIキーのみ返す */
        private fun String?.toValidApiKey(): String? =
            this?.trim()?.takeIf { key ->
                key.isNotBlank() &&
                !key.startsWith("YOUR_") &&
                !key.contains("placeholder", ignoreCase = true) &&
                key.length > 10
            }

        fun buildRequest(
            url: String,
            sharedText: String? = null,
            sourcePackage: String? = null,
            sharedLocalVideoPath: String? = null,
            sharedMimeType: String? = null,
            sharedTitleHint: String? = null
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ProcessUrlWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_URL to url,
                        KEY_SHARED_TEXT to sharedText,
                        KEY_SOURCE_PACKAGE to sourcePackage,
                        KEY_SHARED_LOCAL_VIDEO_PATH to sharedLocalVideoPath,
                        KEY_SHARED_MIME_TYPE to sharedMimeType,
                        KEY_SHARED_TITLE_HINT to sharedTitleHint
                    )
                )
                .build()

        /** 再解析用リクエスト: 既存ブックマークIDを渡して既存エントリを上書きする */
        fun buildReanalyzeRequest(
            existingId: Long,
            url: String
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ProcessUrlWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_URL to url,
                        KEY_EXISTING_ID to existingId
                    )
                )
                .build()
    }

    // Expedited Worker に必須
    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo("AIが解析中...")

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL)?.trim()
            ?: run {
                showSaveFailedNotification(
                    title = "保存できませんでした",
                    detail = "保存対象のURLを取得できませんでした。"
                )
                return Result.failure()
            }
        val sharedText = inputData.getString(KEY_SHARED_TEXT)?.trim()?.takeIf(String::isNotBlank)
        val sourcePackage = inputData.getString(KEY_SOURCE_PACKAGE)?.trim()?.takeIf(String::isNotBlank)
        val sharedLocalVideoPath = inputData.getString(KEY_SHARED_LOCAL_VIDEO_PATH)?.trim()?.takeIf(String::isNotBlank)
        val sharedMimeType = inputData.getString(KEY_SHARED_MIME_TYPE)?.trim()?.takeIf(String::isNotBlank)
        val sharedTitleHint = inputData.getString(KEY_SHARED_TITLE_HINT)?.trim()?.takeIf(String::isNotBlank)
        val existingId = inputData.getLong(KEY_EXISTING_ID, -1L).takeIf { it != -1L }
        val canScrapeUrl = url.isHttpUrl()
        val fallbackTitle = sharedTitleHint?.substringBeforeLast('.')?.takeIf(String::isNotBlank) ?: url
        val fallbackSummary = sharedText ?: sharedTitleHint.orEmpty()

        // ── Step 1: pending ブックマークを取得 or 新規作成 ─────────
        // 再解析の場合は既存IDを直接使ってリセット、通常登録は従来ロジック
        val bookmarkId = try {
            if (existingId != null) {
                repository.resetBookmarkToProcessing(existingId)
                existingId
            } else {
                repository.getOrCreatePendingBookmark(url)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare bookmark for url=$url: ${e.message}", e)
            showSaveFailedNotification(
                title = fallbackTitle,
                detail = "保存の準備に失敗しました。しばらくしてから再度お試しください。"
            )
            return Result.failure()
        }

        // ── Step 2: フォアグラウンド通知 ────────────────────────────
        // リトライ時はバックグラウンドからフォアグラウンドサービスを起動できないため try-catch
        try {
            setForeground(buildForegroundInfo("AIが解析中..."))
        } catch (e: Exception) {
            Log.w(TAG, "setForeground skipped (background retry): ${e.message}")
        }

        return withContext(Dispatchers.IO) {
            var scraped: ScrapedContent? = null
            var localImagePathsStr = ""
            var localVideoPath = ""
            try {
                // ── Step 3 & 4: スクレイプ ────────────────────────────
                scraped = if (canScrapeUrl) webScraper.scrape(url, sharedText).getOrNull() else null

                // ── Step 4.5: 画像をローカルに保存 ───────────────────
                // 先頭1枚（サムネイル用）は常に保存し、2枚目以降は設定で切り替える
                val imageUrlsToDownload = scraped?.allImageUrls?.let { urls ->
                    if (encryptedPrefsManager.isSaveAllImages()) urls else urls.take(1)
                }.orEmpty()

                val localPaths: List<String> = if (imageUrlsToDownload.isNotEmpty()) {
                    Log.d(TAG, "Downloading ${imageUrlsToDownload.size} images for bookmarkId=$bookmarkId")
                    localImageStore.downloadAll(
                        bookmarkId = bookmarkId,
                        imageUrls = imageUrlsToDownload,
                        referer = url
                    )
                } else {
                    emptyList()
                }
                localImagePathsStr = localPaths.joinToString(",")
                Log.d(TAG, "Saved ${localPaths.size} images locally for bookmarkId=$bookmarkId")

                // ── Step 4.6: 動画本体をローカル保存 ─────────────────
                localVideoPath = if (sharedLocalVideoPath != null) {
                    localVideoStore.importFromSharedPath(
                        bookmarkId = bookmarkId,
                        sharedPath = sharedLocalVideoPath,
                        mimeType = sharedMimeType
                    ).orEmpty()
                } else {
                    ""
                }

                val videoCandidates = scraped?.allVideoUrls.orEmpty()
                if (localVideoPath.isBlank() && videoCandidates.isNotEmpty()) {
                    Log.d(TAG, "Trying ${videoCandidates.size} video candidates for bookmarkId=$bookmarkId")
                    localVideoPath = localVideoStore.downloadFirstSupported(
                        bookmarkId = bookmarkId,
                        videoUrls = videoCandidates,
                        referer = url
                    ).orEmpty()
                }
                Log.d(TAG, "Saved local video for bookmarkId=$bookmarkId -> ${localVideoPath.isNotBlank()}")

                // ── Step 5: Gemini 呼び出し ───────────────────────────
                val apiKey = encryptedPrefsManager.getGeminiApiKey().toValidApiKey()
                    ?: BuildConfig.GEMINI_API_KEY.toValidApiKey()

                Log.d(TAG, "API key ${if (apiKey != null) "found (len=${apiKey.length})" else "NOT SET"}")

                val shouldCallAi = apiKey != null && (scraped != null || !sharedText.isNullOrBlank() || !sharedTitleHint.isNullOrBlank())

                val (title, summary, category, tags) = if (shouldCallAi) {
                    try {
                        callGemini(apiKey, url, scraped, sharedText, sourcePackage)
                    } catch (e: Exception) {
                        Log.e(TAG, "Gemini API call failed: ${e.message}", e)
                        when {
                            // クォータ超過・一時エラー → WorkManager にリトライさせる（最大3回）
                            (e.javaClass.simpleName.contains("QuotaExceeded") ||
                            e.message?.contains("quota", ignoreCase = true) == true ||
                            e.message?.contains("429", ignoreCase = true) == true ||
                            e.message?.contains("RESOURCE_EXHAUSTED", ignoreCase = true) == true) &&
                            runAttemptCount < 3 -> {
                                Log.w(TAG, "Rate limit hit (attempt $runAttemptCount/3), will retry via WorkManager")
                                return@withContext Result.retry()
                            }
                            // APIキー無効 → 設定を促す通知してOGPフォールバック
                            e.message?.contains("API_KEY", ignoreCase = true) == true ||
                            e.message?.contains("401") == true ||
                            e.message?.contains("403") == true ||
                            e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true -> {
                                showApiKeyErrorNotification()
                                AiResult(
                                    title = scraped?.title?.ifBlank { fallbackTitle } ?: fallbackTitle,
                                    summary = scraped?.description ?: fallbackSummary,
                                    category = "未分類",
                                    tags = ""
                                )
                            }
                            // その他エラー → OGPフォールバック
                            else -> AiResult(
                                title = scraped?.title?.ifBlank { fallbackTitle } ?: fallbackTitle,
                                summary = scraped?.description ?: fallbackSummary,
                                category = "未分類",
                                tags = ""
                            )
                        }
                    }
                } else {
                    // API キー未設定 → OGP データで最低限保存 + 設定を促す通知
                    Log.w(TAG, "Gemini API key is not set. Saved with OGP data only.")
                    showApiKeyErrorNotification()
                    AiResult(
                        title = scraped?.title?.ifBlank { fallbackTitle } ?: fallbackTitle,
                        summary = scraped?.description ?: fallbackSummary,
                        category = "未分類",
                        tags = ""
                    )
                }

                // ── Step 6: DB 更新 ──────────────────────────────────
                repository.updateAiMetadata(bookmarkId, title, summary, category, tags,
                    thumbnailUrl = scraped?.imageUrl ?: "",
                    videoUrl = scraped?.videoUrl ?: "",
                    localVideoPath = localVideoPath,
                    localImagePaths = localImagePathsStr)

                // ── Step 7: 完了通知 ─────────────────────────────────
                showResultNotification(title)

                Result.success()
            } catch (e: Exception) {
                // 失敗時も既に取得済みのスクレイプ結果 or URL を使って保存
                Log.e(TAG, "doWork failed for url=$url: ${e.message}", e)
                runCatching {
                    repository.updateAiMetadata(
                        bookmarkId,
                        scraped?.title?.ifBlank { fallbackTitle } ?: fallbackTitle,
                        scraped?.description ?: fallbackSummary,
                        "未分類",
                        "",
                        thumbnailUrl = scraped?.imageUrl ?: "",
                        videoUrl = scraped?.videoUrl ?: "",
                        localVideoPath = localVideoPath,
                        localImagePaths = localImagePathsStr
                    )
                }.onSuccess {
                    showResultNotification(scraped?.title ?: fallbackTitle)
                    return@withContext Result.success()
                }.onFailure { saveError ->
                    Log.e(TAG, "Failed to persist bookmark for url=$url: ${saveError.message}", saveError)
                    showSaveFailedNotification(
                        title = scraped?.title?.ifBlank { fallbackTitle } ?: fallbackTitle,
                        detail = saveError.message ?: e.message ?: "ブックマークを保存できませんでした。"
                    )
                }
                Result.failure()
            }
        }
    }

    private fun String.isHttpUrl(): Boolean =
        startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

    // ── Gemini 呼び出し ───────────────────────────────────────────────

    private suspend fun callGemini(
        apiKey: String,
        url: String,
        scraped: ScrapedContent?,
        sharedText: String?,
        sourcePackage: String?
    ): AiResult {
        val selectedModelId = encryptedPrefsManager.getAiModelId()
        Log.d(TAG, "Using AI model: $selectedModelId")

        val model = GenerativeModel(
            modelName = selectedModelId,
            apiKey = apiKey,
            generationConfig = generationConfig {
                temperature = 0.3f
                maxOutputTokens = 1024
            }
        )

        val content = buildString {
            scraped?.title?.takeIf { it.isNotBlank() }?.let { append("タイトル: $it\n") }
            scraped?.description?.takeIf { it.isNotBlank() }?.let { append("説明: $it\n") }
            // 本文が取得できた場合のみ追加（SPA はここが空）
            scraped?.mainText?.takeIf { it.isNotBlank() }?.let { append("本文: $it\n") }
            sharedText?.takeIf { it.isNotBlank() }?.let { append("共有元テキスト(最優先で解釈): $it\n") }
            sourcePackage?.takeIf { it.isNotBlank() }?.let { append("共有元アプリ: $it\n") }
        }

        val prompt = buildPrompt(url, content)
        val response = model.generateContent(prompt)
        val text = response.text ?: throw IllegalStateException("Gemini から空レスポンス")
        Log.d(TAG, "Gemini raw response: $text")

        return parseAiJson(text, scraped)
    }

    private fun buildPrompt(url: String, content: String): String = """
        以下のWebページの内容を分析してください。
        ページの言語（英語・中国語・その他）に関わらず、すべての項目を必ず日本語で回答してください。
        JSONのみで回答してください（説明文・コードブロック・マークダウン記号は不要）。
        共有元テキストが含まれている場合は、Webページ本文よりも共有元テキストを優先して解釈してください。
        特に X / Twitter 共有では、共有元テキストや埋め込み情報を優先して「投稿内容」を要約・タグ付けしてください。

        URL: $url
        $content

        回答フォーマット（このJSONのみ返すこと）:
        {
          "title": "50文字以内の日本語タイトル（元が英語・他言語の場合は日本語に翻訳）",
          "summary": "共有元テキストまたはページ主旨を2〜3文で要約（元が英語・他言語の場合も必ず日本語に翻訳して記述）",
          "points": ["重要ポイント1", "重要ポイント2", "重要ポイント3"],
          "category": "テクノロジー または ビジネス または 科学 または エンターテイメント または スポーツ または 政治 または 文化 または ライフスタイル または その他",
          "tags": ["キーワード1", "キーワード2", "キーワード3", "キーワード4"]
        }

        pointsのルール（厳守）:
        - 必ず2〜4個の箇条書きを返す
        - 各項目は15〜35文字程度で簡潔にする
        - summaryの言い換えではなく、読み手が押さえるべき具体的ポイントを抽出する
        - 元が英語・他言語でも必ず日本語に翻訳して記述する

        tagsのルール（厳守）:
        - 必ず2〜4個のタグを付ける
        - 検索に役立つ具体的な固有名詞・技術用語・人名・製品名・トピックを選ぶ
        - 「情報」「記事」「内容」などの汎用すぎる単語は避ける
        - 英語の技術用語はそのまま使ってよい（例: "React", "ChatGPT", "AWS"）
        - 日本語・英語どちらでも可（ただし読みやすさを優先）
    """.trimIndent()

    /** Gemini レスポンスからJSONを抽出してパース */
    private fun parseAiJson(text: String, scraped: ScrapedContent?): AiResult {
        return runCatching {
            // コードブロック（```json ... ```）を除去してからJSONを抽出
            val cleaned = text
                .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("```\\s*"), "")
                .trim()

            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            require(start != -1 && end != -1) { "JSON not found in response" }
            val json = cleaned.substring(start, end + 1)

            val title = Regex(""""title"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                ?: scraped?.title ?: ""
            val summary = Regex(""""summary"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                ?: scraped?.description ?: ""
            val points = parsePointsFromJson(json)
            val category = Regex(""""category"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                ?: "その他"

            // tags 配列を抽出: ["a", "b", "c"] 形式 or "a,b,c" 文字列の両方に対応
            val tags = parseTagsFromJson(json)

            Log.d(TAG, "Parsed → title=$title, category=$category, tags=$tags")
            AiResult(title, buildStoredAiSummary(summary, points), category, tags)
        }.getOrElse {
            AiResult(
                title = scraped?.title?.ifBlank { "" } ?: "",
                summary = scraped?.description ?: "",
                category = "その他",
                tags = ""
            )
        }
    }

    /**
     * JSON 文字列から tags 配列を抽出する。
     * ["タグ1", "タグ2"] 形式と "タグ1,タグ2" 形式の両方を処理し、
     * 2〜4 個の有効なタグを返す（カンマ区切り文字列として）。
     */
    private fun parseTagsFromJson(json: String): String {
        val arrayMatch = Regex(""""tags"\s*:\s*\[([^\]]*)]""").find(json)
            ?.groupValues?.get(1) ?: ""

        val tags = if (arrayMatch.isNotBlank()) {
            // JSON 配列形式: "タグ1", "タグ2", ... を抽出
            Regex(""""([^"]+)"""").findAll(arrayMatch)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .toList()
        } else {
            // フォールバック: カンマ区切り文字列
            Regex(""""tags"\s*:\s*"([^"]+)"""").find(json)
                ?.groupValues?.get(1)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        }

        // 2〜4 個に制限して返す
        return tags.take(4).joinToString(",")
    }

    private fun parsePointsFromJson(json: String): List<String> {
        val arrayMatch = Regex(""""points"\s*:\s*\[([^\]]*)]""").find(json)
            ?.groupValues?.get(1)
            .orEmpty()

        val points = if (arrayMatch.isNotBlank()) {
            Regex(""""([^"]+)"""").findAll(arrayMatch)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .toList()
        } else {
            Regex(""""points"\s*:\s*"([^"]+)"""").find(json)
                ?.groupValues?.get(1)
                ?.split("\n", ",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        }

        return points.take(4)
    }

    // ── 通知ヘルパー ──────────────────────────────────────────────────

    private fun buildForegroundInfo(text: String): ForegroundInfo {
        val notif = NotificationCompat.Builder(appContext, NotificationConstants.CHANNEL_PROCESSING)
            .setContentTitle("栞-SHIORI-")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        return ForegroundInfo(
            NotificationIds.FOREGROUND,
            notif,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        )
    }

    private fun buildNotificationPendingIntent(): PendingIntent = PendingIntent.getActivity(
        appContext,
        0,
        Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun canPostResultNotifications(): Boolean {
        val hasPermission =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

        return hasPermission && NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    @SuppressLint("MissingPermission")
    private fun notifyResult(notificationId: Int, builder: NotificationCompat.Builder) {
        if (!canPostResultNotifications()) return
        NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
    }

    private fun showResultNotification(title: String) {
        val notif = NotificationCompat.Builder(appContext, NotificationConstants.CHANNEL_RESULT)
            .setContentTitle("保存しました")
            .setContentText(title)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(buildNotificationPendingIntent())
        notifyResult(NotificationIds.RESULT_SUCCESS, notif)
    }

    private fun showSaveFailedNotification(title: String, detail: String) {
        val notif = NotificationCompat.Builder(appContext, NotificationConstants.CHANNEL_RESULT)
            .setContentTitle("保存できませんでした")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(buildNotificationPendingIntent())
        notifyResult(NotificationIds.RESULT_FAILURE, notif)
    }

    /** Gemini APIキー未設定・無効のとき設定を促す通知を表示 */
    private fun showApiKeyErrorNotification() {
        val notif = NotificationCompat.Builder(appContext, NotificationConstants.CHANNEL_RESULT)
            .setContentTitle("⚠️ Gemini APIキーを設定してください")
            .setContentText("設定画面でAPIキーを入力するとAI解析・タグ付け・日本語翻訳が有効になります")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(buildNotificationPendingIntent())
        notifyResult(NotificationIds.API_KEY_WARNING, notif)
    }

    // ── 内部データクラス ──────────────────────────────────────────────

    private data class AiResult(
        val title: String,
        val summary: String,
        val category: String,
        val tags: String
    )
}


