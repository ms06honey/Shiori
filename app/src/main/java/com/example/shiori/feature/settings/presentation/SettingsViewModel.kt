package com.example.shiori.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shiori.core.datastore.EncryptedPrefsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val apiKeyInput: String = "",
    val isKeySet: Boolean = false,
    val isSaved: Boolean = false,
    val errorMessage: String? = null,
    /** §4.2: PIN/指紋変更で Keystore が破壊された場合 true → 再設定を促す */
    val keyInvalidated: Boolean = false,
    /** 選択中の AI モデル ID */
    val selectedModelId: String = EncryptedPrefsManager.DEFAULT_MODEL.id,
    /** スクレイパーモード */
    val scraperMode: EncryptedPrefsManager.ScraperMode = EncryptedPrefsManager.DEFAULT_SCRAPER_MODE,
    /** サムネイル以外の全画像もローカル保存するか */
    val saveAllImages: Boolean = false,
    /** 小さい画像の保存除外フィルタを有効にするか */
    val isMinImageFilterEnabled: Boolean = EncryptedPrefsManager.DEFAULT_ENABLE_MIN_IMAGE_FILTER,
    /** 小さい画像を除外する判定モード */
    val minImageSizeMode: EncryptedPrefsManager.ImageSizeFilterMode =
        EncryptedPrefsManager.DEFAULT_MIN_IMAGE_SIZE_MODE,
    /** 小さい画像を除外するしきい値(px)入力 */
    val minImageSizeThresholdInput: String =
        EncryptedPrefsManager.DEFAULT_MIN_IMAGE_SIZE_THRESHOLD.toString()
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val encryptedPrefsManager: EncryptedPrefsManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update {
            it.copy(
                isKeySet = encryptedPrefsManager.getGeminiApiKey() != null,
                keyInvalidated = encryptedPrefsManager.wasKeyInvalidated,
                selectedModelId = encryptedPrefsManager.getAiModelId(),
                scraperMode = encryptedPrefsManager.getScraperMode(),
                saveAllImages = encryptedPrefsManager.isSaveAllImages(),
                isMinImageFilterEnabled = encryptedPrefsManager.isMinImageFilterEnabled(),
                minImageSizeMode = encryptedPrefsManager.getMinImageSizeMode(),
                minImageSizeThresholdInput = encryptedPrefsManager.getMinImageSizeThresholdPx().toString()
            )
        }
    }

    // ...existing code...

    fun onApiKeyChange(value: String) {
        _uiState.update { it.copy(apiKeyInput = value, isSaved = false) }
    }

    fun saveApiKey() {
        viewModelScope.launch {
            val key = _uiState.value.apiKeyInput.trim()
            if (key.isBlank()) {
                _uiState.update { it.copy(errorMessage = "APIキーを入力してください") }
                return@launch
            }
            encryptedPrefsManager.saveGeminiApiKey(key)
            _uiState.update { it.copy(isSaved = true, isKeySet = true, apiKeyInput = "", errorMessage = null) }
        }
    }

    fun onModelSelected(modelId: String) {
        encryptedPrefsManager.saveAiModel(modelId)
        _uiState.update { it.copy(selectedModelId = modelId) }
    }

    fun onScraperModeSelected(mode: EncryptedPrefsManager.ScraperMode) {
        encryptedPrefsManager.saveScraperMode(mode)
        _uiState.update { it.copy(scraperMode = mode) }
    }

    fun onSaveAllImagesChanged(enabled: Boolean) {
        encryptedPrefsManager.setSaveAllImages(enabled)
        _uiState.update { it.copy(saveAllImages = enabled) }
    }

    fun onMinImageFilterEnabledChanged(enabled: Boolean) {
        encryptedPrefsManager.setMinImageFilterEnabled(enabled)
        _uiState.update { it.copy(isMinImageFilterEnabled = enabled) }
    }

    fun onMinImageSizeModeChanged(mode: EncryptedPrefsManager.ImageSizeFilterMode) {
        encryptedPrefsManager.setMinImageSizeMode(mode)
        _uiState.update { it.copy(minImageSizeMode = mode) }
    }

    fun onMinImageSizeThresholdChanged(input: String) {
        val digitsOnly = input.filter(Char::isDigit)
        _uiState.update { it.copy(minImageSizeThresholdInput = digitsOnly) }

        digitsOnly.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { encryptedPrefsManager.setMinImageSizeThresholdPx(it) }
    }

    /**
     * しきい値入力の編集完了時に呼ぶ。
     * 無効値（空/0）は現在保存済みの値へ戻し、有効値は正規化して反映する。
     */
    fun commitMinImageSizeThresholdInput() {
        val currentInput = _uiState.value.minImageSizeThresholdInput
        val parsed = currentInput.toIntOrNull()
        if (parsed != null && parsed > 0) {
            encryptedPrefsManager.setMinImageSizeThresholdPx(parsed)
            _uiState.update {
                it.copy(minImageSizeThresholdInput = encryptedPrefsManager.getMinImageSizeThresholdPx().toString())
            }
        } else {
            _uiState.update {
                it.copy(minImageSizeThresholdInput = encryptedPrefsManager.getMinImageSizeThresholdPx().toString())
            }
        }
    }

    fun clearSaved() = _uiState.update { it.copy(isSaved = false) }
    fun clearKeyInvalidated() = _uiState.update { it.copy(keyInvalidated = false) }
}
