package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.ui.theme.NuvioColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@Composable
fun PauseOverlay(
    visible: Boolean,
    onClose: () -> Unit,
    title: String,
    episodeTitle: String?,
    season: Int?,
    episode: Int?,
    year: String?,
    type: String?,
    description: String?,
    cast: List<MetaCastMember>,
    modifier: Modifier = Modifier
) {
    var selectedCastMember by remember { mutableStateOf<MetaCastMember?>(null) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(250)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClose)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.88f),
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
                            colorStops = arrayOf(
                                0f to Color.Black.copy(alpha = 0.6f),
                                0.3f to Color.Black.copy(alpha = 0.4f),
                                0.6f to Color.Black.copy(alpha = 0.2f),
                                1f to Color.Transparent
                            )
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 56.dp, end = 56.dp, top = 40.dp, bottom = 120.dp)
            ) {
                PauseOverlayClock(
                    modifier = Modifier.align(Alignment.TopEnd)
                )

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    if (selectedCastMember != null) {
                        CastDetailView(
                            member = selectedCastMember!!,
                            onBack = { selectedCastMember = null }
                        )
                    } else {
                        PauseMetadataView(
                            title = title,
                            episodeTitle = episodeTitle,
                            season = season,
                            episode = episode,
                            year = year,
                            type = type,
                            description = description,
                            cast = cast,
                            onCastSelected = { selectedCastMember = it }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PauseOverlayClock(modifier: Modifier = Modifier) {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val formatter = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Text(
        text = formatter.format(Date(nowMillis)),
        style = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Normal,
            fontSize = 34.sp
        ),
        color = Color.White.copy(alpha = 0.95f),
        modifier = modifier
    )
}

@Composable
private fun PauseMetadataView(
    title: String,
    episodeTitle: String?,
    season: Int?,
    episode: Int?,
    year: String?,
    type: String?,
    description: String?,
    cast: List<MetaCastMember>,
    onCastSelected: (MetaCastMember) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Bottom
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.pause_you_are_watching),
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioColors.TextTertiary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (!year.isNullOrBlank()) {
                val episodeLabel = if (type in listOf("series", "tv") && season != null && episode != null) {
                    " • S${season}E${episode}"
                } else {
                    ""
                }

                Text(
                    text = "$year$episodeLabel",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (!episodeTitle.isNullOrBlank()) {
                Text(
                    text = episodeTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            if (cast.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.pause_cast_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioColors.TextTertiary
                )

                Spacer(modifier = Modifier.height(12.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    content = {
                        items(cast.take(8)) { member ->
                            CastChip(member = member, onClick = { onCastSelected(member) })
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun CastChip(
    member: MetaCastMember,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.1f),
            focusedContainerColor = Color.White.copy(alpha = 0.18f)
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp))
    ) {
        Text(
            text = member.name,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CastDetailView(
    member: MetaCastMember,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = NuvioColors.TextSecondary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.pause_back_to_details),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextSecondary,
                modifier = Modifier.clickable(onClick = onBack)
            )
        }

        Row(
            verticalAlignment = Alignment.Top
        ) {
            if (!member.photo.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(member.photo)
                        .crossfade(true)
                        .build(),
                    contentDescription = member.name,
                    modifier = Modifier
                        .size(width = 160.dp, height = 240.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.width(28.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (!member.character.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.pause_as_character, member.character ?: ""),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioColors.TextSecondary,
                        modifier = Modifier.padding(top = 8.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
