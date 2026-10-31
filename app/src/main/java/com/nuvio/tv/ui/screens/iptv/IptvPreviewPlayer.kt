package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.iptv.IptvChannel
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(UnstableApi::class)
@Composable
internal fun rememberIptvPreviewPlayer(): ExoPlayer {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context.applicationContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .build()
            .apply { videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.playWhenReady = false
                Lifecycle.Event.ON_RESUME -> if (player.mediaItemCount > 0) {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.playWhenReady = true
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }
    return player
}

@OptIn(UnstableApi::class)
internal fun ExoPlayer.playIptvChannel(context: Context, channel: IptvChannel) {
    val userAgent = channel.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
    val extraHeaders = channel.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
    val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
        .setDefaultRequestProperties(extraHeaders)
    if (!userAgent.isNullOrBlank()) dataSourceFactory.setUserAgent(userAgent)
    val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory)
    val itemBuilder = MediaItem.Builder().setUri(channel.streamUrl)
    val path = channel.streamUrl.substringBefore('?').lowercase()
    when {
        path.endsWith(".m3u8") || path.contains("/hls/") || channel.streamUrl.contains("m3u8", ignoreCase = true) ->
            itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        path.endsWith(".mpd") -> itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
    }
    setMediaSource(mediaSourceFactory.createMediaSource(itemBuilder.build()))
    prepare()
    playWhenReady = true
}

internal fun ExoPlayer.stopIptvPreview() {
    playWhenReady = false
    stop()
    clearMediaItems()
}

@OptIn(UnstableApi::class)
@Composable
internal fun IptvPreviewSurface(
    player: ExoPlayer,
    channel: IptvChannel?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isBuffering by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING ||
                    (playbackState == Player.STATE_IDLE && player.mediaItemCount > 0 && player.playerError == null)
            }

            override fun onPlayerError(error: PlaybackException) {
                hasError = true
                isBuffering = false
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player, channel?.key, channel?.streamUrl) {
        hasError = false
        isBuffering = channel != null
        if (channel == null) {
            player.stopIptvPreview()
        } else {
            runCatching { player.playIptvChannel(context, channel) }
                .onFailure {
                    hasError = true
                    isBuffering = false
                }
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(false)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    this.player = player
                }
            },
            update = { view -> if (view.player !== player) view.player = player },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize()
        )

        when {
            channel == null -> Unit
            hasError -> Text(
                text = stringResource(R.string.iptv_preview_error),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
            isBuffering -> LoadingIndicator(modifier = Modifier.size(36.dp))
        }
    }
}
