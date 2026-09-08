package com.nuvio.tv.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.VpnConnectionState

/**
 * Small colored dot showing WireGuard tunnel status: green = connected, red = off/error,
 * yellow (pulsing) = connecting. Purely a visual indicator - tapping it does nothing;
 * the actual controls live in Settings > Integrations > VPN.
 */
@Composable
fun VpnStatusDot(
    state: VpnConnectionState,
    modifier: Modifier = Modifier
) {
    val color = when (state) {
        VpnConnectionState.CONNECTED -> Color(0xFF3DDC5B)
        VpnConnectionState.CONNECTING -> Color(0xFFFFC107)
        VpnConnectionState.DISCONNECTED, VpnConnectionState.ERROR -> Color(0xFFF44336)
    }
    val dotAlpha = if (state == VpnConnectionState.CONNECTING) {
        val transition = rememberInfiniteTransition(label = "vpnConnectingPulse")
        val pulse by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(600),
                repeatMode = RepeatMode.Reverse
            ),
            label = "vpnConnectingPulseAlpha"
        )
        pulse
    } else {
        1f
    }
    Box(
        modifier = modifier
            .padding(10.dp)
            .size(10.dp)
            .alpha(dotAlpha)
            .background(color, CircleShape)
    )
}
