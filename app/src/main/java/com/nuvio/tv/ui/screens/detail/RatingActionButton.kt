package com.nuvio.tv.ui.screens.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun RatingActionButton(
    rating: Double?,
    onClick: () -> Unit,
    onFocused: () -> Unit = {}
) {
    val rated = rating != null
    val twirl = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(rating) }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(percent = 50)
    val label = rating?.let { stringResource(R.string.rating_value, formatRating(it)) }
        ?: stringResource(R.string.rating_rate)
    val description = "${stringResource(R.string.rating_your_rating)}, $label"

    LaunchedEffect(rating) {
        if (rating == shown) return@LaunchedEffect
        shown = rating
        if (rating == null) return@LaunchedEffect
        twirl.snapTo(0f)
        twirl.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) onFocused()
            }
            .focusProperties { up = FocusRequester.Cancel }
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.Secondary,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = NuvioTheme.colors.OnSecondary
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = shape
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
    ) {
        Row(
            modifier = Modifier
                .height(NuvioTheme.spacing.xxxl)
                .defaultMinSize(minWidth = NuvioTheme.spacing.xxxl)
                .animateContentSize(
                    animationSpec = tween(
                        durationMillis = NuvioMotion.tokens.durations.fast,
                        easing = NuvioMotion.tokens.easings.standard
                    )
                )
                .padding(horizontal = if (rated) 16.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (rated) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                contentDescription = null,
                tint = if (rated && !focused) NuvioTheme.colors.Secondary else LocalContentColor.current,
                modifier = Modifier
                    .size(NuvioTheme.spacing.xl)
                    .graphicsLayer {
                        val progress = twirl.value
                        val pop = 1f + 0.3f * sin(PI * progress.coerceIn(0f, 1f)).toFloat()
                        rotationZ = 144f * progress
                        scaleX = pop
                        scaleY = pop
                    }
            )
            AnimatedContent(
                targetState = rating,
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn(tween(160))) togetherWith
                        (slideOutVertically { -it / 2 } + fadeOut(tween(120))) using
                        SizeTransform(clip = false)
                },
                label = "rating_action_value"
            ) { value ->
                if (value != null) {
                    Text(
                        text = formatRating(value),
                        modifier = Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
