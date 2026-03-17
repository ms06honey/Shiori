package com.example.shiori.feature.bookmark.presentation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalViewConfiguration

/**
 * 短いタップを検知したら、表示中のテキスト選択メニューを閉じる。
 * 長押し選択そのものは邪魔しないよう、長押し相当の操作では閉じない。
 */
@Composable
fun Modifier.dismissSelectionMenuOnTap(): Modifier {
    val textToolbar = LocalTextToolbar.current
    val longPressTimeoutMillis = LocalViewConfiguration.current.longPressTimeoutMillis

    return pointerInput(textToolbar, longPressTimeoutMillis) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Final
            )
            val up = waitForUpOrCancellation(pass = PointerEventPass.Final)
                ?: return@awaitEachGesture

            val pressDuration = up.uptimeMillis - down.uptimeMillis
            if (pressDuration < longPressTimeoutMillis) {
                textToolbar.hide()
            }
        }
    }
}

