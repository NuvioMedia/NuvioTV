@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.reshaped.livetv

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvHttp
import com.nuvio.tv.reshaped.livetv.LIVE_TV_UNGROUPED
import com.nuvio.tv.reshaped.livetv.LiveTvClock
import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import coil3.request.crossfade

internal val LiveTvPillShape = RoundedCornerShape(100.dp)

/** A compact pill button in the app's quiet style: a faint glass fill and hairline, white when focused. */
@Composable
internal fun LiveTvPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconDescription: String? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = ButtonDefaults.shape(LiveTvPillShape),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.TextPrimary.copy(alpha = if (selected) 0.14f else 0.06f),
            contentColor = NuvioTheme.colors.TextPrimary.copy(alpha = 0.9f),
            focusedContainerColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = Color.Black,
        ),
        border = ButtonDefaults.border(
            border = Border(BorderStroke(1.dp, NuvioTheme.colors.TextPrimary.copy(alpha = 0.10f)), shape = LiveTvPillShape),
            focusedBorder = Border.None,
        ),
        contentPadding = PaddingValues(horizontal = if (icon != null && text.isEmpty()) 10.dp else 16.dp, vertical = 7.dp),
        scale = ButtonDefaults.scale(focusedScale = 1.03f),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = iconDescription, modifier = Modifier.size(18.dp))
        }
        if (text.isNotEmpty()) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (icon != null) Modifier.padding(start = 8.dp) else Modifier,
            )
        }
    }
}

/**
 * A one-line text field for the remote: the card takes focus without opening the keyboard;
 * OK opens it. The arrows always leave the field (left and right once the cursor is at that
 * end): Compose only does this for input devices that report a D-pad, so remotes that arrive
 * as a keyboard or through HDMI-CEC used to be stuck in the field.
 */
@Composable
internal fun LiveTvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Uri,
    password: Boolean = false,
    onDone: () -> Unit = {},
    /** Where ◀ and ▶ go from the field (a button beside it), rather than looking for something that way. */
    sideFocus: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    // The card itself (not the field inside it, which handles its own arrows).
    var cardFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // The field keeps its own cursor; the text itself follows [value].
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (fieldValue.text != value) fieldValue = TextFieldValue(value, TextRange(value.length))
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = { inputFocusRequester.requestFocus(); keyboardController?.show() },
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (sideFocus == null) Modifier else Modifier
                    .focusProperties {
                        left = sideFocus
                        right = sideFocus
                    }
                    // Taken here, before anything around the field sees it: the screen may use ▶
                    // itself (Live TV's categories go back to the guide on it) before focus moves.
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (!cardFocused) return@onPreviewKeyEvent false
                        if (native.keyCode != KeyEvent.KEYCODE_DPAD_LEFT && native.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                            return@onPreviewKeyEvent false
                        }
                        if (native.action == KeyEvent.ACTION_DOWN) runCatching { sideFocus.requestFocus() }
                        true
                    },
            )
            .onFocusChanged {
                focused = it.isFocused || it.hasFocus
                cardFocused = it.isFocused
            },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.TextPrimary.copy(alpha = 0.05f),
            focusedContainerColor = NuvioTheme.colors.TextPrimary.copy(alpha = 0.08f),
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, NuvioTheme.colors.TextPrimary.copy(alpha = 0.10f)), shape = shape),
            focusedBorder = Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape),
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f),
    ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
            BasicTextField(
                value = fieldValue,
                onValueChange = { next ->
                    fieldValue = next
                    if (next.text != value) onValueChange(next.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(inputFocusRequester)
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.action != KeyEvent.ACTION_DOWN) {
                            // The matching key-up of a handled press must not reach the field either.
                            return@onPreviewKeyEvent native.keyCode in DPAD_ARROWS
                        }
                        val selection = fieldValue.selection
                        val direction = when (native.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                            KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                            KeyEvent.KEYCODE_DPAD_LEFT -> if (selection.collapsed && selection.start == 0) FocusDirection.Left else null
                            KeyEvent.KEYCODE_DPAD_RIGHT ->
                                if (selection.collapsed && selection.end == fieldValue.text.length) FocusDirection.Right else null
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                keyboardController?.show()
                                return@onPreviewKeyEvent true
                            }
                            else -> return@onPreviewKeyEvent false
                        } ?: return@onPreviewKeyEvent false
                        keyboardController?.hide()
                        if (sideFocus != null && (direction == FocusDirection.Left || direction == FocusDirection.Right)) {
                            runCatching { sideFocus.requestFocus() }
                            return@onPreviewKeyEvent true
                        }
                        // Nothing that way (the top of the screen): stay, rather than typing an arrow.
                        focusManager.moveFocus(direction)
                        true
                    },
                singleLine = true,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide(); onDone() }),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                cursorBrush = SolidColor(if (focused) NuvioTheme.colors.Primary else Color.Transparent),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextTertiary,
                            maxLines = 1,
                        )
                    }
                    inner()
                },
            )
        }
    }
}

private val DPAD_ARROWS = intArrayOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
)

/**
 * A channel logo, decoded at the size it is drawn (channel logos are often large PNGs). The
 * channel's initials show only when there is no logo or it fails to load: most logos are
 * transparent PNGs, so initials drawn underneath would show through them.
 */
@Composable
internal fun LiveTvLogo(
    url: String?,
    name: String,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    var failed by remember(url) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(width, height)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank() || failed) {
            Text(
                text = name.initials(),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextTertiary,
            )
        } else {
            val context = LocalContext.current
            val density = LocalDensity.current
            val request = remember(url, width, height) {
                with(density) {
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(width.roundToPx(), height.roundToPx())
                        // IPTV panels often serve logos only to player-like clients, as they do streams.
                        .httpHeaders(LOGO_HEADERS)
                        .fetcherFactory<coil3.Uri>(LOGO_FETCHER)
                        .build()
                }
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
            )
        }
    }
}

/** A programme's picture from the guide, cropped to fill; nothing shows when there is none or it fails. */
@Composable
internal fun LiveTvPoster(url: String?, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    if (url.isNullOrBlank() || failed) return
    val context = LocalContext.current
    val density = LocalDensity.current
    val request = remember(url, width, height) {
        with(density) {
            ImageRequest.Builder(context)
                .data(url)
                // Decoded to fill a box half again as large: a fit-inside decode was then
                // upscaled by the crop, which made the picture soft.
                .size((width.toPx() * POSTER_OVERSAMPLE).roundToInt(), (height.toPx() * POSTER_OVERSAMPLE).roundToInt())
                .scale(coil3.size.Scale.FILL)
                .crossfade(200)
                .httpHeaders(LOGO_HEADERS)
                .fetcherFactory<coil3.Uri>(LOGO_FETCHER)
                .build()
        }
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
        onError = { failed = true },
        modifier = modifier
            .size(width, height)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.06f)),
    )
}

private const val POSTER_OVERSAMPLE = 1.5f

private val LOGO_HEADERS = NetworkHeaders.Builder().set("User-Agent", "VLC/3.0.0 LibVLC/3.0.0").build()

/** Logos load through Live TV's own client ([LiveTvHttp.logoClient]), still into Nuvio's image caches. */
private val LOGO_FETCHER = coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { LiveTvHttp.logoClient })

private fun String.initials(): String =
    split(' ', '-', '_', '.').filter { it.isNotBlank() && it.first().isLetterOrDigit() }
        .take(2).joinToString("") { it.first().uppercase() }

/** A category's name as shown; channels the playlist gives no category are "Uncategorised". */
@Composable
internal fun liveTvGroupLabel(group: String, names: Map<String, String> = emptyMap()): String =
    liveTvGroupName(group, names) ?: if (group == LIVE_TV_UNGROUPED) stringResource(R.string.live_tv_uncategorised) else group

/** The name the viewer gave [group], or null. */
internal fun liveTvGroupName(group: String, names: Map<String, String>): String? =
    names[group]?.trim()?.takeIf(String::isNotEmpty)

/** The time, updated on each minute. */
@Composable
internal fun rememberLiveTvMinuteClock(): State<Long> = produceState(LiveTvClock.nowEpochMs()) {
    while (true) {
        delay(60_000L - value % 60_000L)
        value = LiveTvClock.nowEpochMs()
    }
}

/** "23 min left" or "1 h 5 min left" for the programme on now; recomposes with the minute clock. */
@Composable
internal fun liveTvTimeLeft(programme: LiveTvProgramme, clock: State<Long>): String {
    val exact = ((programme.stopEpochMs - clock.value).coerceAtLeast(0L) + 59_999L) / 60_000L
    // The guide moves on at its own minute tick; until it does, this reads "1 min left", never "0 min".
    val minutes = exact.coerceAtLeast(1L)
    return if (minutes < 60) {
        stringResource(R.string.live_tv_minutes_left, minutes.toInt())
    } else {
        stringResource(R.string.live_tv_hours_minutes_left, (minutes / 60).toInt(), (minutes % 60).toInt())
    }
}

/** How far the programme on now has got, as a thin bar. Read at draw time: the minute tick redraws it without recomposing. */
@Composable
internal fun LiveTvProgressBar(
    programme: LiveTvProgramme,
    clock: State<Long>,
    fill: Color,
    track: Color,
    modifier: Modifier = Modifier,
) {
    val span = (programme.stopEpochMs - programme.startEpochMs).coerceAtLeast(1L)
    Box(
        modifier = modifier
            .height(3.dp)
            .clip(LiveTvPillShape)
            .background(track)
            .drawBehind {
                val fraction = ((clock.value - programme.startEpochMs).toFloat() / span).coerceIn(0f, 1f)
                drawRect(fill, size = Size(size.width * fraction, size.height))
            },
    )
}
