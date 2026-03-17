package com.example.brainbox.feature.bookmark.worker

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
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

        fun buildRequest(url: String): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ProcessUrlWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_URL to url))
                .build()
    }

    // Expedited Worker に必須
    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo("AIが解析中...")

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL)?.trim()
            ?: return Result.failure()

        // ── Step 1: placeholder を DB に保存 ─────────────────────────
        val bookmarkId = repository.saveInitialBookmark(url)

        // ── Step 2: フォアグラウンド通知 ────────────────────────────
        setForeground(buildForegroundInfo("AIが解析中..."))

        return withContext(Dispatchers.IO) {
            var scraped: ScrapedContent? = null
            try {
                // ── Step 3 & 4: スクレイプ ────────────────────────────
                scraped = webScraper.scrape(url).getOrNull()

                // ── Step 5: Gemini 呼び出し ───────────────────────────
                val apiKey = encryptedPrefsManager.getGeminiApiKey()
                    ?.ifBlank { null }
                    ?: BuildConfig.GEMINI_API_KEY.ifBlank { null }

                val (title, summary, category, tags) = if (apiKey != null) {
                    callGemini(apiKey, url, scraped)
                } else {
                    // API キー未設定 → OGP データで最低限保存
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
        scraped: ScrapedContent?
    ): AiResult {
        val model = GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = apiKey,
            generationConfig = generationConfig {
                temperature = 0.3f
                maxOutputTokens = 512
            }
        )

        val content = buildString {
            scraped?.title?.takeIf { it.isNotBlank() }?.let { append("タイトル: $it\n") }
            scraped?.description?.takeIf { it.isNotBlank() }?.let { append("説明: $it\n") }
            // 本文が取得できた場合のみ追加（SPA はここが空）
            scraped?.mainText?.takeIf { it.isNotBlank() }?.let { append("本文: $it\n") }
        }

        val prompt = buildPrompt(url, content)
        val response = model.generateContent(prompt)
        val text = response.text ?: throw IllegalStateException("Gemini から空レスポンス")

        return parseAiJson(text, scraped)
    }

    private fun buildPrompt(url: String, content: String): String = """
        以下のWebページを分析し、JSONのみで回答してください（説明文・マークダウン不要）。
        
        URL: $url
        $content
        
        回答フォーマット（このJSONのみ返すこと）:
        {
          "title": "適切なタイトル（50文字以内の日本語）",
          "summary": "2〜3文の日本語要約",
          "category": "テクノロジー または ビジネス または 科学 または エンターテイメント または スポーツ または 政治 または 文化 または ライフスタイル または その他",
          "tags": ["タグ1", "タグ2", "タグ3"]
        }
    """.trimIndent()

    /** Gemini レスポンスからJSONを抽出してパース */
    private fun parseAiJson(text: String, scraped: ScrapedContent?): AiResult {
        return runCatching {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            require(start != -1 && end != -1)
            val json = text.substring(start, end + 1)

            val title = Regex(""""title"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                ?: scraped?.title ?: ""
            val summary = Regex(""""summary"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                ?: scraped?.description ?: ""
            val category = Regex(""""category"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                ?: "その他"
            val tagsRaw = Regex(""""tags"\s*:\s*\[([^\]]*)]""").find(json)?.groupValues?.get(1) ?: ""
            val tags = tagsRaw.split(",")
                .map { it.trim().trim('"') }
                .filter { it.isNotBlank() }
                .joinToString(",")

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

    // ── 内部データクラス ──────────────────────────────────────────────

    private data class AiResult(
        val title: String,
        val summary: String,
        val category: String,
        val tags: String
    )
}


