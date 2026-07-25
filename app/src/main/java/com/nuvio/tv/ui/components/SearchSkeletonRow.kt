package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioTheme

private val SKELETON_TITLE_WIDTH = 180.dp
private val SKELETON_TITLE_HEIGHT = 24.dp
private val SKELETON_SUBTITLE_WIDTH = 90.dp
private val SKELETON_SUBTITLE_HEIGHT = 12.dp
private val SKELETON_BAR_CORNER_RADIUS = 6.dp
private const val SKELETON_POSTER_COUNT = 8

/**
 * Loading stand-in for a search result row, drawn from scratch rather than by handing a fabricated
 * [com.nuvio.tv.domain.model.CatalogRow] to the real row: a row built from blank names still renders
 * its header decorations, which shows up as a stray " - Movie" and "from " with nothing after them.
 *
 * Mirrors the two header lines this screen actually has, a catalog title and the addon name, so the
 * layout does not shift when the real rows arrive.
 */
@Composable
fun SearchSkeletonRow(
    posterCardStyle: PosterCardStyle = PosterCardDefaults.Style,
    showAddonName: Boolean = true,
    modifier: Modifier = Modifier
) {
    val shimmerOffsetState = rememberPlaceholderShimmerOffsetState(label = "searchSkeletonShimmer")

    Column(modifier = modifier) {
        Column(
            modifier = Modifier.padding(
                start = NuvioTheme.spacing.xxxl,
                end = NuvioTheme.spacing.xxxl,
                bottom = NuvioTheme.spacing.md
            ),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            SkeletonBar(
                width = SKELETON_TITLE_WIDTH,
                height = SKELETON_TITLE_HEIGHT,
                shimmerOffsetState = shimmerOffsetState
            )
            if (showAddonName) {
                SkeletonBar(
                    width = SKELETON_SUBTITLE_WIDTH,
                    height = SKELETON_SUBTITLE_HEIGHT,
                    shimmerOffsetState = shimmerOffsetState
                )
            }
        }

        Row(
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            repeat(SKELETON_POSTER_COUNT) {
                Box(
                    modifier = Modifier
                        .width(posterCardStyle.width)
                        .height(posterCardStyle.height)
                        .clip(RoundedCornerShape(posterCardStyle.cornerRadius))
                        .placeholderCardShimmer(
                            shimmerOffsetState = shimmerOffsetState,
                            backgroundColor = NuvioTheme.colors.SurfaceVariant
                        )
                )
            }
        }
    }
}

@Composable
private fun SkeletonBar(
    width: Dp,
    height: Dp,
    shimmerOffsetState: androidx.compose.runtime.State<Float>
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(SKELETON_BAR_CORNER_RADIUS))
            .placeholderCardShimmer(
                shimmerOffsetState = shimmerOffsetState,
                backgroundColor = NuvioTheme.colors.SurfaceVariant
            )
    )
}
