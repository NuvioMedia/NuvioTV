@file:OptIn(ExperimentalTvMaterial3Api::class)
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.nuvio.tv.ui.screens.live

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import kotlinx.coroutines.delay

private const val OSD_TIMEOUT_MS = 6_000L
private const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android) NuvioTV"

@Composable
fun LiveTvPlayerScreen(startIndex: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by LiveTvRepository.state.collectAsStateWithLifecycle()
    val startChannel = state.channels.getOrNull(startIndex)
    if (startChannel == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    // Zapping and the channel strip stay inside the server the channel came from.
    val channels = remember(state.channels, startChannel.sourceId) {
        state.channels.filter { it.sourceId == startChannel.sourceId }
    }

    var current by remember { mutableIntStateOf(channels.indexOfFirst { it.index == startChannel.index }.coerceAtLeast(0)) }
    var target by remember { mutableIntStateOf(current) }
    var osdVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var toast by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val httpFactory = remember {
        DefaultHttpDataSource.Factory()
            .setUserAgent(DEFAULT_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
    }
    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory))
            .build()
            .apply { playWhenReady = true }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    val sourceLabel = stringResource(R.string.live_source, channels[current].name)
    LaunchedEffect(current) {
        val channel = channels[current]
        LiveTvRepository.rememberChannel(context, channel)
        val headers = channel.headers.toMutableMap()
        httpFactory.setUserAgent(headers.remove("User-Agent") ?: DEFAULT_UA)
        httpFactory.setDefaultRequestProperties(headers)
        player.setMediaItem(MediaItem.fromUri(channel.url))
        player.prepare()
        toast = sourceLabel
        delay(2_500)
        toast = null
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(osdVisible, lastInteraction) {
        if (osdVisible) {
            delay(OSD_TIMEOUT_MS)
            osdVisible = false
        }
    }

    fun zap(delta: Int) {
        current = (current + delta).mod(channels.size)
        target = current
        osdVisible = true
        lastInteraction = System.currentTimeMillis()
    }

    val rootRequester = remember { FocusRequester() }
    val stripRequester = remember { FocusRequester() }
    val stripState = rememberLazyListState()

    LaunchedEffect(osdVisible) {
        if (osdVisible) {
            stripState.scrollToItem((target - 3).coerceAtLeast(0))
            delay(60)
            runCatching { stripRequester.requestFocus() }
        } else {
            target = current
            runCatching { rootRequester.requestFocus() }
        }
    }

    BackHandler {
        if (osdVisible) osdVisible = false else onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastInteraction = System.currentTimeMillis()
                if (osdVisible) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp, Key.ChannelUp, Key.PageUp -> { zap(1); true }
                    Key.DirectionDown, Key.ChannelDown, Key.PageDown -> { zap(-1); true }
                    Key.DirectionCenter, Key.Enter, Key.DirectionLeft, Key.DirectionRight, Key.Menu -> {
                        osdVisible = true
                        true
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        toast?.let {
            Text(
                text = it,
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 110.dp)
                    .background(Color(0xE00A0C10), RoundedCornerShape(22.dp))
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            )
        }

        AnimatedVisibility(
            visible = osdVisible,
            enter = fadeIn() + slideInVertically { it / 6 },
            exit = fadeOut() + slideOutVertically { it / 6 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(Modifier.padding(start = 28.dp, end = 28.dp, bottom = 18.dp)) {
                OsdCard(
                    channels = channels,
                    current = current,
                    target = target,
                    now = now,
                    epg = state.epg
                )
                Spacer(Modifier.height(20.dp))
                LazyRow(
                    state = stripState,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    itemsIndexed(channels, key = { _, ch -> ch.index }) { i, channel ->
                        StripItem(
                            channel = channel,
                            onClick = {
                                current = i
                                target = i
                                lastInteraction = System.currentTimeMillis()
                            },
                            modifier = Modifier
                                .then(if (i == target) Modifier.focusRequester(stripRequester) else Modifier)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        target = i
                                        lastInteraction = System.currentTimeMillis()
                                    }
                                }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OsdCard(
    channels: List<LiveChannel>,
    current: Int,
    target: Int,
    now: Long,
    epg: Map<String, List<EpgProgram>>
) {
    val channel = channels[current]
    val targetChannel = channels[target]
    val nextChannel = channels[(current + 1) % channels.size]
    val (nowProgram, nextProgram) = LiveTvRepository.nowAndNext(channel, now, epg)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(IntrinsicSize.Min)
            .background(LiveColors.Osd, RoundedCornerShape(18.dp))
            .border(1.dp, Color(0x12FFFFFF), RoundedCornerShape(18.dp))
    ) {
        // Now / next programme (start side)
        Column(
            modifier = Modifier
                .weight(1.5f)
                .padding(horizontal = 30.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.Center
        ) {
            nowProgram?.let {
                Text("[${it.range()}] ${it.title}", color = LiveColors.Accent, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                channel.name,
                color = LiveColors.Text,
                fontSize = 30.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 6.dp)
            )
            Text(
                nowProgram?.let { "[${it.range()}] ${it.title}" } ?: stringResource(R.string.live_no_epg),
                color = Color(0xFFC9D0D8),
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            nextProgram?.let {
                Text(
                    stringResource(R.string.live_next_prefix, "[${it.range()}] ${it.title}"),
                    color = Color(0xFFC9D0D8),
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        // "Switch to" hint
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 20.dp, vertical = 22.dp)
        ) {
            Text(stringResource(R.string.live_switch_to), color = LiveColors.TextDim, fontSize = 16.sp)
            if (target != current) {
                Text(
                    targetChannel.name,
                    color = Color(0xFF6B737D),
                    fontSize = 28.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 20.dp)
                )
            }
        }

        VerticalDivider()
        Row(
            modifier = Modifier.padding(horizontal = 28.dp).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Text("${target + 1}", color = LiveColors.Text, fontSize = 42.sp)
            ChannelLogo(
                channel = targetChannel,
                corner = 8.dp,
                modifier = Modifier
                    .size(84.dp)
                    .border(4.dp, Color.White, RoundedCornerShape(8.dp))
            )
        }
        VerticalDivider()

        Column(
            modifier = Modifier
                .width(170.dp)
                .fillMaxHeight()
                .padding(horizontal = 26.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(stringResource(R.string.live_next_channel), color = LiveColors.TextDim, fontSize = 15.sp)
            Text(
                nextChannel.name,
                color = LiveColors.Text,
                fontSize = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        Modifier
            .fillMaxHeight()
            .padding(vertical = 18.dp)
            .width(1.dp)
            .background(LiveColors.Divider)
    )
}

@Composable
private fun StripItem(channel: LiveChannel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier = Modifier.width(116.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(width = 116.dp, height = 104.dp),
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.White, focusedContainerColor = Color.White),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(4.dp, Color.White), shape = shape)
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f)
        ) {
            ChannelLogo(channel, Modifier.fillMaxSize(), corner = 12.dp, textSize = 20.sp)
        }
        Text(
            text = channel.name,
            color = Color(0xFFE3E7EC),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
