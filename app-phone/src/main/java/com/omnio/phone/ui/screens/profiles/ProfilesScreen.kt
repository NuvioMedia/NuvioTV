package com.omnio.phone.ui.screens.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.omnio.tv.domain.model.UserProfile

private val AvatarSize = 40.dp

@Composable
fun ProfilesScreen(
    onProfileSelected: () -> Unit,
    viewModel: ProfilesViewModel = hiltViewModel()
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val avatarUrls by viewModel.avatarUrlsById.collectAsStateWithLifecycle()

    if (profiles.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No profiles",
                style = MaterialTheme.typography.bodyLarge
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        items(profiles, key = { it.id }) { profile ->
            ProfileRow(
                profile = profile,
                avatarUrl = profile.avatarId?.let { avatarUrls[it] },
                isActive = profile.id == activeProfileId,
                onClick = {
                    viewModel.selectProfile(profile.id)
                    onProfileSelected()
                }
            )
        }
    }
}

@Composable
private fun ProfileRow(
    profile: UserProfile,
    avatarUrl: String?,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfileAvatar(profile = profile, avatarUrl = avatarUrl)
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = profile.name + if (isActive) "  (active)" else "",
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun ProfileAvatar(
    profile: UserProfile,
    avatarUrl: String?
) {
    val backgroundColor = parseHex(profile.avatarColorHex) ?: MaterialTheme.colorScheme.primary
    val initial = profile.name.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty()
    Box(
        modifier = Modifier
            .size(AvatarSize)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        if (initial.isNotEmpty()) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(avatarUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = profile.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(AvatarSize)
                    .clip(CircleShape)
            )
        }
    }
}

private fun parseHex(hex: String): Color? {
    val cleaned = hex.removePrefix("#")
    return runCatching {
        when (cleaned.length) {
            6 -> Color(0xFF000000 or cleaned.toLong(16))
            8 -> Color(cleaned.toLong(16))
            else -> null
        }
    }.getOrNull()
}
