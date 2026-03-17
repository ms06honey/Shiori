package com.example.brainbox.feature.settings.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            snackbarHostState.showSnackbar("APIキーを安全に保存しました")
            viewModel.clearSaved()
        }
    }

    // §4.2: PIN/指紋変更で Keystore が破壊された場合に警告を表示
    LaunchedEffect(uiState.keyInvalidated) {
        if (uiState.keyInvalidated) {
            snackbarHostState.showSnackbar(
                "端末のセキュリティ設定変更により、保存データがリセットされました。APIキーを再入力してください。"
            )
            viewModel.clearKeyInvalidated()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ── Gemini APIキー設定 ──────────────────────────────────
            Text("Gemini API キー", style = MaterialTheme.typography.titleMedium)

            // 現在の設定状態
            if (uiState.isKeySet) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "APIキー設定済み",
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            OutlinedTextField(
                value = uiState.apiKeyInput,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text(if (uiState.isKeySet) "新しいAPIキー（変更する場合のみ）" else "Gemini APIキー") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                },
                supportingText = {
                    Text("Google AI Studio (aistudio.google.com) で取得したキーを入力")
                },
                isError = uiState.errorMessage != null
            )

            uiState.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = viewModel::saveApiKey,
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.apiKeyInput.isNotBlank()
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("保存")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── アプリ情報 ──────────────────────────────────────────
            Text("このアプリについて", style = MaterialTheme.typography.titleMedium)
            Text(
                "BrainBox v1.0\nスマートブックマーク & ナレッジベース\n\n" +
                        "技術スタック:\n" +
                        "• Jetpack Compose + Material3\n" +
                        "• Room + SQLCipher (暗号化DB)\n" +
                        "• Hilt (DI) + WorkManager (Expedited)\n" +
                        "• Jsoup + Google Gemini AI",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
