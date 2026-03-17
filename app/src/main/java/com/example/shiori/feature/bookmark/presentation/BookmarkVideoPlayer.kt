package com.example.shiori.feature.bookmark.presentation

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * ブックマーク詳細用の動画プレイヤー。
 *
 * - ローカル MP4 / WebM / MOV を優先再生
 * - ローカルが無ければリモート動画 URL を再生
 * - Media3 を利用するため HLS(m3u8) も比較的安定して再生可能
 */
@Composable
fun BookmarkVideoPlayer(
    videoUri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    val player = remember(videoUri.toString()) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            playWhenReady = false
            prepare()
        }
    }

    fun detachPlayerView(view: PlayerView?) {
        view?.apply {
            useController = false
            hideController()
            setKeepContentOnPlayerReset(false)
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            this.player = null
            alpha = 0f
            setBackgroundColor(android.graphics.Color.BLACK)
            invalidate()
        }
        player.clearVideoSurface()
        player.pause()
    }

    fun attachPlayerView(view: PlayerView?) {
        view?.apply {
            alpha = 1f
            useController = true
            setKeepContentOnPlayerReset(false)
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            this.player = player
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> detachPlayerView(playerViewRef)

                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME -> attachPlayerView(playerViewRef)

                Lifecycle.Event.ON_DESTROY -> detachPlayerView(playerViewRef)
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(player) {
        onDispose {
            detachPlayerView(playerViewRef)
            player.stop()
            player.clearMediaItems()
            player.release()
            playerViewRef = null
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                playerViewRef = this
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setKeepContentOnPlayerReset(false)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setBackgroundColor(android.graphics.Color.BLACK)
                runCatching {
                    PlayerView::class.java
                        .getMethod("setEnableComposeSurfaceSyncWorkaround", Boolean::class.javaPrimitiveType)
                        .invoke(this, true)
                }
                attachPlayerView(this)
            }
        },
        update = { view ->
            playerViewRef = view
            attachPlayerView(view)
        },
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(Color.Black, MaterialTheme.shapes.medium)
    )
}


