package com.example.brainbox.core.datastore

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.GeneralSecurityException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EncryptedSharedPreferences を使用したセキュアなストレージ管理クラス。
 * - DB パスフレーズ
 * - Gemini API キー
 * などを暗号化して保持する。
 *
 * §4.2 準拠: 端末のPIN/指紋変更によって Keystore の鍵が無効化された場合
 * (KeyPermanentlyInvalidatedException) を捕捉し、クラッシュを防いで
 * 再初期化フローへ誘導する。
 */
@Singleton
class EncryptedPrefsManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "EncryptedPrefsManager"
        private const val PREFS_FILE = "brainbox_secure_prefs"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_AI_MODEL = "ai_model"

        /** 選択可能な AI モデル一覧 */
        val AVAILABLE_MODELS = listOf(
            AiModel("gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite", "高速・軽量（推奨）"),
            AiModel("gemma-3-12b-it",        "Gemma 3 12B",           "高品質・オープンモデル"),
        )
        val DEFAULT_MODEL = AVAILABLE_MODELS.first()
    }

    /** AI モデル定義 */
    data class AiModel(val id: String, val displayName: String, val description: String)

    /**
     * 端末の生体認証/PIN 変更で Keystore の鍵が破壊された場合に true。
     * SettingsViewModel や UI からチェックして再設定を促す。
     */
    var wasKeyInvalidated: Boolean = false
        private set

    private val prefs by lazy { openOrRecoverPrefs() }

    /**
     * EncryptedSharedPreferences を開く。
     * KeyPermanentlyInvalidatedException や GeneralSecurityException が発生した場合
     * (= PIN/指紋変更などで Keystore が破壊) は古いファイルを削除して再作成し、
     * [wasKeyInvalidated] を true に設定する。
     */
    @SuppressLint("ApplySharedPref")
    private fun openOrRecoverPrefs(): android.content.SharedPreferences {
        return try {
            buildPrefs()
        } catch (e: KeyPermanentlyInvalidatedException) {
            // §4.2: 端末PIN/指紋変更による Keystore 鍵の破壊を捕捉
            Log.w(TAG, "Keystore key permanently invalidated. Resetting secure storage.", e)
            deletePrefsFile()
            wasKeyInvalidated = true
            buildPrefs()
        } catch (e: GeneralSecurityException) {
            // より広範な暗号化エラー（鍵の不整合等）も同様にリカバリー
            Log.w(TAG, "GeneralSecurityException opening secure prefs. Resetting.", e)
            deletePrefsFile()
            wasKeyInvalidated = true
            buildPrefs()
        } catch (e: Exception) {
            // 初回起動などで稀に発生するその他の暗号化エラーへの防衛的ハンドリング
            Log.e(TAG, "Unexpected error opening secure prefs. Resetting.", e)
            deletePrefsFile()
            wasKeyInvalidated = true
            buildPrefs()
        }
    }

    private fun buildPrefs(): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * 暗号化 Prefs ファイルを削除する。
     * SharedPreferences は XML ファイルとして保存されるため直接削除する。
     */
    private fun deletePrefsFile() {
        try {
            // EncryptedSharedPreferences のバックアップファイルも含めて削除
            context.deleteSharedPreferences(PREFS_FILE)
            // Backup file が残る場合のフォールバック
            File(context.dataDir, "shared_prefs/${PREFS_FILE}.xml").delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete prefs file", e)
        }
    }

    /**
     * DB 暗号化パスフレーズを取得、なければ自動生成して保存。
     * @Synchronized でチェック→書き込みの競合状態を防ぐ（DB 初期化は一度だけ実行される）。
     */
    @Synchronized
    @SuppressLint("ApplySharedPref") // DB 初期化前に値を確実に永続化したいため commit を使う
    fun getOrCreateDbPassphrase(): String {
        return prefs.getString(KEY_DB_PASSPHRASE, null) ?: run {
            val newPassphrase = UUID.randomUUID().toString() + UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DB_PASSPHRASE, newPassphrase).commit()
            newPassphrase
        }
    }

    /** Gemini API キーを保存 */
    fun saveGeminiApiKey(apiKey: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, apiKey).apply()
    }

    /** Gemini API キーを取得 */
    fun getGeminiApiKey(): String? {
        return prefs.getString(KEY_GEMINI_API_KEY, null)
    }

    /** 選択中の AI モデルID を保存 */
    fun saveAiModel(modelId: String) {
        prefs.edit().putString(KEY_AI_MODEL, modelId).apply()
    }

    /** 選択中の AI モデルID を取得（未設定ならデフォルト） */
    fun getAiModelId(): String {
        return prefs.getString(KEY_AI_MODEL, null) ?: DEFAULT_MODEL.id
    }
}
