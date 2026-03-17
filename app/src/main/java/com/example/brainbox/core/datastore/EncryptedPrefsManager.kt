package com.example.brainbox.core.datastore

import android.annotation.SuppressLint
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EncryptedSharedPreferences を使用したセキュアなストレージ管理クラス。
 * - DB パスフレーズ
 * - Gemini API キー
 * などを暗号化して保持する。
 */
@Singleton
class EncryptedPrefsManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_FILE = "brainbox_secure_prefs"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
    }

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
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
}

