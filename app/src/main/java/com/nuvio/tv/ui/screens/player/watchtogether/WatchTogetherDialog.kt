package com.nuvio.tv.ui.screens.player.watchtogether

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.player.DialogButton
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WatchTogetherDialog(
    state: WatchTogetherState,
    onJoin: (String, String) -> Unit,
    onCreate: (String) -> Unit,
    onLeave: () -> Unit,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onDismiss: () -> Unit
) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.wt_title),
        subtitle = if (state.role == RoomRole.NONE) stringResource(R.string.wt_join_desc) else null,
        width = if (state.role == RoomRole.NONE) 520.dp else 800.dp
    ) {
        if (state.role == RoomRole.NONE) {
            var roomCode by remember { mutableStateOf("") }
            var username by remember { mutableStateOf("TV User") }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Your Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NuvioColors.Primary,
                        unfocusedBorderColor = NuvioColors.Border,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                OutlinedTextField(
                    value = roomCode,
                    onValueChange = { roomCode = it.uppercase() },
                    label = { Text(stringResource(R.string.wt_enter_code)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NuvioColors.Primary,
                        unfocusedBorderColor = NuvioColors.Border,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                DialogButton(
                    text = if (roomCode.isBlank()) stringResource(R.string.wt_create_room) else stringResource(R.string.wt_join),
                    onClick = {
                        if (roomCode.isBlank()) onCreate(username) else onJoin(roomCode, username)
                    },
                    isPrimary = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (state.error != null) {
                    Text(
                        text = state.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // Left Column: Room Info & QR
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.wt_room_code, state.roomCode ?: ""),
                        style = MaterialTheme.typography.titleLarge,
                        color = NuvioColors.Primary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    val qrBitmap = remember(state.roomCode) {
                        state.roomCode?.let { QrCodeGenerator.generate(it, 256) }
                    }
                    qrBitmap?.let {
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .padding(8.dp)
                        ) {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "QR Code",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    DialogButton(
                        text = stringResource(R.string.wt_leave_room),
                        onClick = onLeave,
                        isPrimary = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Right Column: Participants & Content
                Column(modifier = Modifier.weight(1.2f)) {
                    state.currentContent?.let { content ->
                        Text(
                            text = stringResource(R.string.wt_now_playing),
                            style = MaterialTheme.typography.labelMedium,
                            color = NuvioColors.TextTertiary
                        )
                        Text(
                            text = content.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        content.fingerprint?.filename?.let { filename ->
                            Text(
                                text = filename,
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioColors.TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    Text(
                        text = "Participants (${state.participants.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioColors.TextTertiary
                    )
                    
                    LazyColumn(
                        modifier = Modifier.height(150.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(state.participants) { user ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = user.username,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (user.userId == state.userId) NuvioColors.Secondary else Color.White
                                )
                                if (user.isHost) {
                                    Text(
                                        text = " (Host)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NuvioColors.TextTertiary
                                    )
                                }
                            }
                        }
                    }

                    if (state.isHost && state.joinRequests.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Join Requests",
                            style = MaterialTheme.typography.labelMedium,
                            color = NuvioColors.Secondary
                        )
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.joinRequests) { req ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(req.username, style = MaterialTheme.typography.bodyMedium)
                                    Row {
                                        IconButton(
                                            onClick = { onApprove(req.userId) },
                                            colors = IconButtonDefaults.colors(contentColor = Color.Green)
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = "Approve")
                                        }
                                        IconButton(
                                            onClick = { onReject(req.userId) },
                                            colors = IconButtonDefaults.colors(contentColor = Color.Red)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Reject")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
