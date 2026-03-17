package com.example.brainbox.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.brainbox.core.datastore.EncryptedPrefsManager
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
    val selectedModelId: String = EncryptedPrefsManager.DEFAULT_MODEL.id
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
                selectedModelId = encryptedPrefsManager.getAiModelId()
            )
        }
    }

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

    fun clearSaved() = _uiState.update { it.copy(isSaved = false) }
    fun clearKeyInvalidated() = _uiState.update { it.copy(keyInvalidated = false) }
}
