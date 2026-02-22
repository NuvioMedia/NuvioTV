package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

@Composable
fun ProfileAvatarCircle(
    name: String,
    colorHex: String,
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    isSelected: Boolean = false
) {
    val avatarColor = runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
        .getOrDefault(Color(0xFF1E88E5))
    val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val fontSize = (size.value * 0.4f).sp

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarColor, CircleShape)
            .then(
                if (isSelected) {
                    Modifier.border(3.dp, Color.White, CircleShape)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold
        )
    }
}
