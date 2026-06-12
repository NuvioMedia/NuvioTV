package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items

@Composable
fun StreamsSkeletonList(
    modifier: Modifier = Modifier,
    showAddonLogo: Boolean = true,
    itemCount: Int = 6
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(NuvioTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        contentPadding = PaddingValues(vertical = NuvioTheme.spacing.sm)
    ) {
        items(itemCount) {
            StreamCardSkeleton(showAddonLogo = showAddonLogo, shimmerBrush = rememberShimmerBrush())
        }
    }
}

@Composable
fun StreamCardSkeleton(
    modifier: Modifier = Modifier,
    showAddonLogo: Boolean = true,
    shimmerBrush: Brush = rememberShimmerBrush()
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTheme.radii.xl))
            .background(NuvioTheme.colors.BackgroundElevated)
            .padding(NuvioTheme.spacing.lg)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                SkeletonBar(width = 180.dp, height = NuvioTheme.spacing.lg, brush = shimmerBrush, cornerRadius = 10.dp)
                SkeletonBar(width = 140.dp, height = NuvioTheme.spacing.md, brush = shimmerBrush, cornerRadius = 10.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    SkeletonBar(width = 64.dp, height = NuvioTheme.spacing.lg, brush = shimmerBrush, cornerRadius = 10.dp)
                    SkeletonBar(width = 52.dp, height = NuvioTheme.spacing.lg, brush = shimmerBrush, cornerRadius = 10.dp)
                }
            }

            if (showAddonLogo) {
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Box(
                        modifier = Modifier
                            .size(NuvioTheme.spacing.xxl)
                            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                            .background(shimmerBrush)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SkeletonBar(width = 64.dp, height = 10.dp, brush = shimmerBrush, cornerRadius = NuvioTheme.spacing.sm)
                }
            }
        }
    }
}

@Composable
fun MetaDetailsSkeleton(backdropAware: Boolean = false) {
    val shimmerBrush = rememberShimmerBrush(backdropAware = backdropAware)
    val episodeCardColor = if (backdropAware) {
        NuvioTheme.colors.BackgroundCard.copy(alpha = 0.40f)
    } else {
        NuvioTheme.colors.BackgroundCard
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (backdropAware) Color.Transparent else NuvioTheme.colors.Background)
    ) {
        if (backdropAware) {
            BackdropLoadingScrim()
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                MetaHeroSkeleton(shimmerBrush = shimmerBrush)
            }

            item {
                SeasonTabsSkeleton(shimmerBrush = shimmerBrush)
            }

            item {
                EpisodesRowSkeleton(
                    shimmerBrush = shimmerBrush,
                    cardColor = episodeCardColor
                )
            }

            item {
                CastSectionSkeleton(shimmerBrush = shimmerBrush)
            }

            item {
                CompanyLogosSkeleton(titleWidth = 140.dp, shimmerBrush = shimmerBrush)
            }

            item {
                CompanyLogosSkeleton(titleWidth = 110.dp, shimmerBrush = shimmerBrush)
            }
        }
    }
}

@Composable
private fun BackdropLoadingScrim() {
    val backgroundColor = NuvioTheme.colors.Background

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor.copy(alpha = 0.20f))
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        backgroundColor.copy(alpha = 0.96f),
                        backgroundColor.copy(alpha = 0.84f),
                        backgroundColor.copy(alpha = 0.56f),
                        Color.Transparent
                    )
                )
            )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        backgroundColor.copy(alpha = 0.08f),
                        Color.Transparent,
                        backgroundColor.copy(alpha = 0.90f)
                    )
                )
            )
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(540.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        backgroundColor.copy(alpha = 0.12f),
                        Color.Transparent,
                        backgroundColor.copy(alpha = 0.42f)
                    )
                )
            )
    )
}

@Composable
private fun MetaHeroSkeleton(shimmerBrush: Brush) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(540.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, bottom = NuvioTheme.spacing.lg),
            verticalArrangement = Arrangement.Bottom
        ) {
            SkeletonBar(
                width = 0.4f,
                height = 100.dp,
                brush = shimmerBrush,
                cornerRadius = NuvioTheme.spacing.md
            )

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonPill(width = 100.dp, height = NuvioTheme.spacing.xxxl, brush = shimmerBrush)
                Box(
                    modifier = Modifier
                        .size(NuvioTheme.spacing.xxxl)
                        .clip(CircleShape)
                        .background(shimmerBrush)
                )
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

            SkeletonBar(width = 0.5f, height = 14.dp, brush = shimmerBrush, cornerRadius = 10.dp)
            Spacer(modifier = Modifier.height(10.dp))
            SkeletonBar(width = 0.6f, height = NuvioTheme.spacing.lg, brush = shimmerBrush, cornerRadius = 10.dp)
            Spacer(modifier = Modifier.height(6.dp))
            SkeletonBar(width = 0.55f, height = NuvioTheme.spacing.lg, brush = shimmerBrush, cornerRadius = 10.dp)

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                SkeletonBar(width = 96.dp, height = 14.dp, brush = shimmerBrush, cornerRadius = 10.dp)
                SkeletonBar(width = 72.dp, height = 14.dp, brush = shimmerBrush, cornerRadius = 10.dp)
                SkeletonBar(width = 84.dp, height = 14.dp, brush = shimmerBrush, cornerRadius = 10.dp)
                SkeletonBar(width = 64.dp, height = 14.dp, brush = shimmerBrush, cornerRadius = 10.dp)
            }

            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))

            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                SkeletonBar(width = 72.dp, height = NuvioTheme.spacing.md, brush = shimmerBrush, cornerRadius = 10.dp)
                SkeletonBar(width = NuvioTheme.spacing.huge, height = NuvioTheme.spacing.md, brush = shimmerBrush, cornerRadius = 10.dp)
            }
        }
    }
}

@Composable
private fun SeasonTabsSkeleton(shimmerBrush: Brush) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        items(5) {
            SkeletonPill(width = 96.dp, height = 36.dp, brush = shimmerBrush)
        }
    }
}

@Composable
private fun EpisodesRowSkeleton(
    shimmerBrush: Brush,
    cardColor: Color
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        items(5) {
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .clip(RoundedCornerShape(NuvioTheme.radii.md))
                    .background(cardColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(158.dp)
                        .clip(RoundedCornerShape(topStart = NuvioTheme.spacing.md, topEnd = NuvioTheme.spacing.md))
                        .background(shimmerBrush)
                )
                Column(modifier = Modifier.padding(NuvioTheme.spacing.md)) {
                    SkeletonBar(width = 160.dp, height = 14.dp, brush = shimmerBrush, cornerRadius = 10.dp)
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                    SkeletonBar(width = 120.dp, height = NuvioTheme.spacing.md, brush = shimmerBrush, cornerRadius = 10.dp)
                }
            }
        }
    }
}

@Composable
private fun CastSectionSkeleton(shimmerBrush: Brush) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = NuvioTheme.spacing.sm)
    ) {
        Box(modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl)) {
            SkeletonBar(width = 120.dp, height = 20.dp, brush = shimmerBrush, cornerRadius = 10.dp)
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            items(7) {
                Column(
                    modifier = Modifier.width(120.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(shimmerBrush)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    SkeletonBar(width = 90.dp, height = NuvioTheme.spacing.md, brush = shimmerBrush, cornerRadius = 10.dp)
                    Spacer(modifier = Modifier.height(6.dp))
                    SkeletonBar(width = 70.dp, height = 10.dp, brush = shimmerBrush, cornerRadius = 10.dp)
                }
            }
        }
    }
}

@Composable
private fun CompanyLogosSkeleton(
    titleWidth: Dp,
    shimmerBrush: Brush
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = NuvioTheme.spacing.sm)
    ) {
        Box(modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl)) {
            SkeletonBar(width = titleWidth, height = 20.dp, brush = shimmerBrush, cornerRadius = 10.dp)
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            items(6) {
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(NuvioTheme.spacing.huge)
                        .clip(RoundedCornerShape(NuvioTheme.radii.md))
                        .background(shimmerBrush)
                )
            }
        }
    }
}

@Composable
fun SkeletonBar(
    width: Dp,
    height: Dp,
    brush: Brush,
    cornerRadius: Dp = NuvioTheme.spacing.sm
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(brush)
    )
}

@Composable
fun SkeletonBar(
    width: Float,
    height: Dp,
    brush: Brush,
    cornerRadius: Dp = NuvioTheme.spacing.sm
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(width)
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(brush)
    )
}

@Composable
fun SkeletonPill(
    width: Dp,
    height: Dp,
    brush: Brush
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(NuvioTheme.spacing.xxl))
            .background(brush)
    )
}

@Composable
fun rememberShimmerBrush(backdropAware: Boolean = false): Brush {
    val shimmerColors = if (backdropAware) {
        listOf(
            NuvioTheme.colors.TextPrimary.copy(alpha = 0.08f),
            NuvioTheme.colors.TextPrimary.copy(alpha = 0.20f),
            NuvioTheme.colors.TextPrimary.copy(alpha = 0.08f)
        )
    } else {
        listOf(
            NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.30f),
            NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.60f),
            NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.30f)
        )
    }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing)
        ),
        label = "shimmer_translate"
    )

    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translate - 1000f, 0f),
        end = Offset(translate, 0f)
    )
}
