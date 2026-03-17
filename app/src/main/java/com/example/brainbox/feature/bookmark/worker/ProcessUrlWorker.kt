package com.example.brainbox.feature.bookmark.worker

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.example.brainbox.BuildConfig
import com.example.brainbox.R
import com.example.brainbox.core.datastore.EncryptedPrefsManager
import com.example.brainbox.core.scraper.ScrapedContent
import com.example.brainbox.core.scraper.WebScraper
import com.example.brainbox.core.util.NotificationConstants
import com.example.brainbox.core.util.NotificationIds
import com.example.brainbox.feature.bookmark.domain.repository.BookmarkRepository
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
    private val encryptedPrefsManager: EncryptedPrefsManager
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_URL = "url"
        const val KEY_SHARED_TEXT = "shared_text"
        const val KEY_SOURCE_PACKAGE = "source_package"
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
            sourcePackage: String? = null
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ProcessUrlWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_URL to url,
                        KEY_SHARED_TEXT to sharedText,
                        KEY_SOURCE_PACKAGE to sourcePackage
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
            ?: return Result.failure()
        val sharedText = inputData.getString(KEY_SHARED_TEXT)?.trim()?.takeIf(String::isNotBlank)
        val sourcePackage = inputData.getString(KEY_SOURCE_PACKAGE)?.trim()?.takeIf(String::isNotBlank)
        val existingId = inputData.getLong(KEY_EXISTING_ID, -1L).takeIf { it != -1L }

        // ── Step 1: pending ブックマークを取得 or 新規作成 ─────────
        // 再解析の場合は既存IDを直接使ってリセット、通常登録は従来ロジック
        val bookmarkId = if (existingId != null) {
            repository.resetBookmarkToProcessing(existingId)
            existingId
        } else {
            repository.getOrCreatePendingBookmark(url)
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
            try {
                // ── Step 3 & 4: スクレイプ ────────────────────────────
                scraped = webScraper.scrape(url, sharedText).getOrNull()

                // ── Step 5: Gemini 呼び出し ───────────────────────────
                val apiKey = encryptedPrefsManager.getGeminiApiKey().toValidApiKey()
                    ?: BuildConfig.GEMINI_API_KEY.toValidApiKey()

                Log.d(TAG, "API key ${if (apiKey != null) "found (len=${apiKey.length})" else "NOT SET"}")

                val (title, summary, category, tags) = if (apiKey != null) {
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
                                    title = scraped?.title?.ifBlank { url } ?: url,
                                    summary = scraped?.description ?: "",
                                    category = "未分類",
                                    tags = ""
                                )
                            }
                            // その他エラー → OGPフォールバック
                            else -> AiResult(
                                title = scraped?.title?.ifBlank { url } ?: url,
                                summary = scraped?.description ?: "",
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
                        title = scraped?.title?.ifBlank { url } ?: url,
                        summary = scraped?.description ?: "",
                        category = "未分類",
                        tags = ""
                    )
                }

                // ── Step 6: DB 更新 ──────────────────────────────────
                repository.updateAiMetadata(bookmarkId, title, summary, category, tags)

                // ── Step 7: 完了通知 ─────────────────────────────────
                showResultNotification(title)

                Result.success()
            } catch (e: Exception) {
                // 失敗時も既に取得済みのスクレイプ結果 or URL を使って保存
                Log.e(TAG, "doWork failed for url=$url: ${e.message}", e)
                repository.updateAiMetadata(
                    bookmarkId,
                    scraped?.title?.ifBlank { url } ?: url,
                    scraped?.description ?: "",
                    "未分類",
                    ""
                )
                showResultNotification(scraped?.title ?: url)
                Result.success()
            }
        }
    }

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
          "category": "テクノロジー または ビジネス または 科学 または エンターテイメント または スポーツ または 政治 または 文化 または ライフスタイル または その他",
          "tags": ["キーワード1", "キーワード2", "キーワード3", "キーワード4"]
        }

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
            val category = Regex(""""category"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                ?: "その他"

            // tags 配列を抽出: ["a", "b", "c"] 形式 or "a,b,c" 文字列の両方に対応
            val tags = parseTagsFromJson(json)

            Log.d(TAG, "Parsed → title=$title, category=$category, tags=$tags")
            AiResult(title, summary, category, tags)
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

    // ── 通知ヘルパー ──────────────────────────────────────────────────

    private fun buildForegroundInfo(text: String): ForegroundInfo {
        val notif = NotificationCompat.Builder(appContext, NotificationConstants.CHANNEL_PROCESSING)
            .setContentTitle("BrainBox")
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

    private fun showResultNotification(title: String) {
        val notif = NotificationCompat.Builder(appContext, NotificationConstants.CHANNEL_RESULT)
            .setContentTitle("保存しました")
            .setContentText(title)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .build()
        appContext.getSystemService(NotificationManager::class.java)
            .notify(NotificationIds.RESULT, notif)
    }

    /** Gemini APIキー未設定・無効のとき設定を促す通知を表示 */
    private fun showApiKeyErrorNotification() {
        val notif = NotificationCompat.Builder(appContext, NotificationConstants.CHANNEL_RESULT)
            .setContentTitle("⚠️ Gemini APIキーを設定してください")
            .setContentText("設定画面でAPIキーを入力するとAI解析・タグ付け・日本語翻訳が有効になります")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .build()
        appContext.getSystemService(NotificationManager::class.java)
            .notify(NotificationIds.RESULT + 1, notif)
    }

    // ── 内部データクラス ──────────────────────────────────────────────

    private data class AiResult(
        val title: String,
        val summary: String,
        val category: String,
        val tags: String
    )
}


