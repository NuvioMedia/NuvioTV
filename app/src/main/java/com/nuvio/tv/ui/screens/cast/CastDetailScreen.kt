package com.nuvio.tv.ui.screens.cast

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.theme.NuvioColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CastDetailScreen(
    viewModel: CastDetailViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    BackHandler { onBackPress() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
    ) {
        Crossfade(
            targetState = uiState,
            label = "CastDetailStateCrossfade"
        ) { state ->
            when (state) {
                is CastDetailUiState.Loading -> {
                    CastDetailSkeleton(personName = viewModel.personName)
                }
                is CastDetailUiState.Error -> {
                    CastDetailError(
                        message = state.message,
                        onRetry = { viewModel.retry() }
                    )
                }
                is CastDetailUiState.Success -> {
                    CastDetailContent(
                        person = state.personDetail,
                        onNavigateToDetail = onNavigateToDetail
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CastDetailContent(
    person: PersonDetail,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit
) {
    val backgroundColor = NuvioColors.Background
    val accentColor = NuvioColors.Secondary

    val allCredits = remember(person.movieCredits, person.tvCredits) {
        (person.movieCredits + person.tvCredits)
            .distinctBy { it.id }
            .sortedByDescending { releaseYearSortKey(it.releaseInfo) }
    }

    val filmographyPosterStyle = remember {
        PosterCardStyle(
            width = 112.dp,
            height = 168.dp,
            cornerRadius = PosterCardDefaults.Style.cornerRadius,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    val firstPosterFocusRequester = remember { FocusRequester() }

    Box(modifier = Modifier.fillMaxSize()) {
        // Left accent gradient overlay
        val accentGradient = remember(accentColor, backgroundColor) {
            Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.0f to accentColor.copy(alpha = 0.26f),
                    0.12f to accentColor.copy(alpha = 0.18f),
                    0.28f to accentColor.copy(alpha = 0.10f),
                    0.45f to accentColor.copy(alpha = 0.04f),
                    0.60f to Color.Transparent
                )
            )
        }
        // Left-to-right dark gradient for readability
        val leftGradient = remember(backgroundColor) {
            Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.0f to backgroundColor.copy(alpha = 0.74f),
                    0.22f to backgroundColor.copy(alpha = 0.70f),
                    0.38f to backgroundColor.copy(alpha = 0.60f),
                    0.55f to backgroundColor.copy(alpha = 0.46f),
                    0.72f to backgroundColor.copy(alpha = 0.26f),
                    1.0f to Color.Transparent
                )
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(leftGradient)
        )

        // Accent goes on top of the dark gradient so it stays visible.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(accentGradient)
        )

        // Bottom gradient
        val bottomGradient = remember(backgroundColor) {
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to Color.Transparent,
                    0.6f to Color.Transparent,
                    0.85f to backgroundColor.copy(alpha = 0.85f),
                    1.0f to backgroundColor
                )
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bottomGradient)
        )

        // Main content
        AnimatedVisibility(
            visible = true,
            enter = fadeIn()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                HeroSection(person = person)

                if (allCredits.isNotEmpty()) {
                    SectionHeader(
                        title = stringResource(R.string.cast_detail_filmography),
                        count = allCredits.size
                    )
                    FilmographyRow(
                        credits = allCredits,
                        posterCardStyle = filmographyPosterStyle,
                        firstItemFocusRequester = firstPosterFocusRequester,
                        onItemClick = { item ->
                            onNavigateToDetail(item.id, item.apiType, null)
                        }
                    )
                }
            }
        }
    }
}

private fun releaseYearSortKey(releaseInfo: String?): Int {
    return releaseInfo
        ?.trim()
        ?.take(4)
        ?.toIntOrNull()
        ?: 0
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroSection(person: PersonDetail) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Avatar / Profile Photo
        Card(
            onClick = { },
            modifier = Modifier
                .width(160.dp)
                .height(240.dp)
                .focusable(false),
            shape = CardDefaults.shape(
                shape = RoundedCornerShape(16.dp)
            ),
            colors = CardDefaults.colors(
                containerColor = NuvioColors.SurfaceVariant,
                focusedContainerColor = NuvioColors.SurfaceVariant
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(1.dp, NuvioColors.Border),
                    shape = RoundedCornerShape(16.dp)
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = RoundedCornerShape(16.dp)
                )
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                val photo = person.profilePhoto
                if (!photo.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(photo)
                            .crossfade(true)
                            .size(
                                width = with(LocalDensity.current) { 160.dp.roundToPx() },
                                height = with(LocalDensity.current) { 240.dp.roundToPx() }
                            )
                            .build(),
                        contentDescription = person.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = person.name.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.displayLarge,
                        color = NuvioColors.TextTertiary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(24.dp))

        // Bio Column
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 4.dp)
        ) {
            // Name
            Text(
                text = person.name,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                ),
                color = NuvioColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Spacer(modifier = Modifier.height(10.dp))

            // Personal Info Row
            val infoItems = buildList {
                person.birthday?.let { bday ->
                    val age = calculateAge(bday, person.deathday)
                    val ageStr = if (age != null) " (age $age)" else ""
                    val bdayDisplay = formatDateForDisplay(bday) ?: bday
                    val deathDisplay = person.deathday?.let { formatDateForDisplay(it) ?: it }
                    val deathStr = deathDisplay?.let { " — †$it" } ?: ""
                    add("Born: $bdayDisplay$deathStr$ageStr")
                }
                person.placeOfBirth?.let { add(it) }
            }
            if (infoItems.isNotEmpty()) {
                infoItems.forEach { info ->
                    Text(
                        text = info,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Biography
            person.biography?.let { bio ->
                Text(
                    text = bio,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = 20.sp
                    ),
                    color = NuvioColors.TextSecondary,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = NuvioColors.TextPrimary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = NuvioColors.TextTertiary,
            modifier = Modifier
                .background(
                    color = NuvioColors.SurfaceVariant,
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun FilmographyRow(
    credits: List<MetaPreview>,
    posterCardStyle: PosterCardStyle,
    firstItemFocusRequester: FocusRequester,
    onItemClick: (MetaPreview) -> Unit
) {
    val hasRequestedInitialFocus = remember(credits) { mutableStateOf(false) }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(
            items = credits,
            key = { _, item -> item.id + item.name }
        ) { index, item ->
            GridContentCard(
                item = item,
                onClick = { onItemClick(item) },
                modifier = if (index == 0) {
                    Modifier.onGloballyPositioned {
                        if (!hasRequestedInitialFocus.value) {
                            hasRequestedInitialFocus.value = true
                            runCatching { firstItemFocusRequester.requestFocus() }
                        }
                    }
                } else {
                    Modifier
                },
                posterCardStyle = posterCardStyle,
                showLabel = true,
                focusRequester = if (index == 0) firstItemFocusRequester else null
            )
        }
    }
}

// ─── Loading / Error States ───

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CastDetailSkeleton(personName: String) {
    val backgroundColor = NuvioColors.Background
    val accentColor = NuvioColors.Secondary

    Box(modifier = Modifier.fillMaxSize()) {
        val accentGradient = remember(accentColor, backgroundColor) {
            Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.0f to accentColor.copy(alpha = 0.26f),
                    0.12f to accentColor.copy(alpha = 0.18f),
                    0.28f to accentColor.copy(alpha = 0.10f),
                    0.45f to accentColor.copy(alpha = 0.04f),
                    0.60f to Color.Transparent
                )
            )
        }
        val leftGradient = remember(backgroundColor) {
            Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.0f to backgroundColor.copy(alpha = 0.74f),
                    0.22f to backgroundColor.copy(alpha = 0.70f),
                    0.38f to backgroundColor.copy(alpha = 0.60f),
                    0.55f to backgroundColor.copy(alpha = 0.46f),
                    0.72f to backgroundColor.copy(alpha = 0.26f),
                    1.0f to Color.Transparent
                )
            )
        }
        Box(modifier = Modifier.fillMaxSize().background(leftGradient))

        Box(modifier = Modifier.fillMaxSize().background(accentGradient))

        val bottomGradient = remember(backgroundColor) {
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to Color.Transparent,
                    0.6f to Color.Transparent,
                    0.85f to backgroundColor.copy(alpha = 0.85f),
                    1.0f to backgroundColor
                )
            )
        }
        Box(modifier = Modifier.fillMaxSize().background(bottomGradient))

        Column(modifier = Modifier.fillMaxSize()) {
            // Hero skeleton
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .width(160.dp)
                        .height(240.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(NuvioColors.SurfaceVariant)
                )

                Spacer(modifier = Modifier.width(24.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 4.dp)
                ) {
                    Text(
                        text = personName,
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                        color = NuvioColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(if (it == 0) 0.60f else if (it == 1) 0.48f else 0.72f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(NuvioColors.SurfaceVariant)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.86f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(NuvioColors.SurfaceVariant)
                    )
                }
            }

            // Filmography header skeleton
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(NuvioColors.SurfaceVariant)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(NuvioColors.SurfaceVariant)
                )
            }

            // Filmography row skeleton
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, end = 48.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                repeat(7) {
                    Column(modifier = Modifier.width(112.dp)) {
                        Box(
                            modifier = Modifier
                                .width(112.dp)
                                .height(168.dp)
                                .clip(RoundedCornerShape(PosterCardDefaults.Style.cornerRadius))
                                .background(NuvioColors.SurfaceVariant)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(NuvioColors.SurfaceVariant)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CastDetailError(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.cast_detail_error),
                style = MaterialTheme.typography.titleLarge,
                color = NuvioColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.Secondary,
                    contentColor = NuvioColors.OnSecondary,
                    focusedContainerColor = NuvioColors.SecondaryVariant,
                    focusedContentColor = NuvioColors.OnSecondaryVariant
                )
            ) {
                Text(stringResource(R.string.cast_detail_retry))
            }
        }
    }
}

// ─── Utility ───

private fun calculateAge(birthday: String, deathday: String?): Int? {
    val birth = parseDateFlexible(birthday) ?: return null
    val end = deathday?.let { parseDateFlexible(it) } ?: Date()

    val birthCal = Calendar.getInstance().apply { time = birth }
    val endCal = Calendar.getInstance().apply { time = end }

    var age = endCal.get(Calendar.YEAR) - birthCal.get(Calendar.YEAR)
    val endMonth = endCal.get(Calendar.MONTH)
    val birthMonth = birthCal.get(Calendar.MONTH)
    val endDay = endCal.get(Calendar.DAY_OF_MONTH)
    val birthDay = birthCal.get(Calendar.DAY_OF_MONTH)

    if (endMonth < birthMonth || (endMonth == birthMonth && endDay < birthDay)) {
        age--
    }
    return age.takeIf { it >= 0 }
}

private fun parseDateFlexible(date: String?): Date? {
    val raw = date?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val patterns = arrayOf("yyyy-MM-dd", "dd-MM-yyyy")
    for (pattern in patterns) {
        try {
            val sdf = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
            return sdf.parse(raw)
        } catch (_: Exception) {
            // try next
        }
    }
    return null
}

private fun formatDateForDisplay(date: String?): String? {
    val parsed = parseDateFlexible(date) ?: return null
    return try {
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(parsed)
    } catch (_: Exception) {
        null
    }
}
