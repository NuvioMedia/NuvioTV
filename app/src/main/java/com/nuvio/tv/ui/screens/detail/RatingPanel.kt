package com.nuvio.tv.ui.screens.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRatingState
import com.nuvio.tv.core.tracking.TrackingRatings
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun RatingDialog(
    media: TrackingMediaReference,
    title: String,
    state: TrackingRatingState,
    onDismiss: () -> Unit,
    subtitle: String? = null
) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = "",
        width = 560.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.rating_your_rating),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            RatingPanel(
                media = media,
                state = state,
                onFinished = onDismiss
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun RatingPanel(
    media: TrackingMediaReference,
    state: TrackingRatingState,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RatingViewModel = hiltViewModel()
) {
    val current = state.rating
    var value by remember { mutableIntStateOf(current?.roundToInt()?.coerceIn(0, TrackingRatings.MAX) ?: 0) }
    var saving by remember { mutableStateOf(false) }
    val provider = state.provider?.let { trackingProviderName(it) }.orEmpty()
    val starsFocusRequester = remember { FocusRequester() }
    val canSave = !saving && value > 0 && value.toDouble() != current

    fun submit(rating: Int?) {
        if (saving) return
        saving = true
        viewModel.rate(media, rating, state.provider) {
            saving = false
            onFinished()
        }
    }

    LaunchedEffect(Unit) {
        starsFocusRequester.requestFocusAfterFrames()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RatingScore(value = value)
        Spacer(modifier = Modifier.height(16.dp))
        RatingStars(
            value = value,
            onValueChange = { value = it },
            onConfirm = { if (canSave) submit(value) },
            focusRequester = starsFocusRequester
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.rating_syncs_to, provider),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextTertiary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (current != null) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.rating_remove),
                    onClick = { submit(null) },
                    enabled = !saving,
                    modifier = Modifier.weight(1f)
                )
            }
            SettingsDialogActionButton(
                text = stringResource(R.string.action_save),
                onClick = { submit(value) },
                primary = true,
                enabled = canSave,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RatingScore(value: Int) {
    val color by animateColorAsState(
        targetValue = if (value > 0) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary,
        animationSpec = tween(durationMillis = 200),
        label = "rating_score_color"
    )
    Row(verticalAlignment = Alignment.Bottom) {
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                val rising = targetState > initialState
                val motion = spring<IntOffset>(
                    dampingRatio = 0.8f,
                    stiffness = Spring.StiffnessMediumLow
                )
                (slideInVertically(motion) { if (rising) it / 2 else -it / 2 } + fadeIn(tween(140))) togetherWith
                    (slideOutVertically(motion) { if (rising) -it / 2 else it / 2 } + fadeOut(tween(100))) using
                    SizeTransform(clip = false)
            },
            label = "rating_score"
        ) { score ->
            Text(
                text = if (score > 0) score.toString() else "–",
                style = MaterialTheme.typography.displayMedium.copy(fontSize = 64.sp, lineHeight = 68.sp),
                fontWeight = if (score > 0) FontWeight.Bold else FontWeight.Light,
                color = color
            )
        }
        Text(
            text = "/${TrackingRatings.MAX}",
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextTertiary
        )
    }
}

@Composable
internal fun trackingProviderName(provider: TrackingProviderId): String =
    stringResource(trackingProviderNameRes(provider))

internal fun trackingProviderNameRes(provider: TrackingProviderId): Int = when (provider) {
    TrackingProviderId.TRAKT -> R.string.trakt_name
    TrackingProviderId.SIMKL -> R.string.simkl_name
    TrackingProviderId.MDBLIST -> R.string.mdblist_name
}

internal fun formatRating(value: Double): String =
    if (value == floor(value)) value.toInt().toString() else String.format(Locale.getDefault(), "%.1f", value)
