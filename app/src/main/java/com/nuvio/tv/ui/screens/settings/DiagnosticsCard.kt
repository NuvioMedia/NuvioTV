@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.player.DolbyVisionCodecFallback
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.ui.theme.NuvioColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Emits the diagnostics card content as 3 dense focusable Cards.
 *
 * Goal: minimize scrolling by packing related fields tightly per Card.
 * Each Card is one D-pad stop containing 6-10 rows of related info.
 */
internal fun LazyListScope.diagnosticsCardItems(
    diagnostics: LastPlaybackDiagnostics,
    dvCurrentlyEnabled: Boolean = true
) {
    if (diagnostics.timestampMs == 0L || diagnostics.host.isBlank()) {
        item(key = "diagnostics_empty_intro") {
            DiagnosticsSectionCard {
                Text(
                    text = "Last Playback Diagnostics",
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioColors.Primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "No playback data yet. Play a stream and return here to see DV decision details for that playback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
            }
        }
        return
    }

    // Only show DV rows when the last playback actually involved DV. Requested mode isn't
    // enough: AUTO is the default, so "requested != OFF" holds even for SDR/HDR10. Use real
    // evidence: a detected profile, a successful conversion, a codec rewrite, or DV output.
    // Not dv7DoviCalls: the startup self-test makes it >= 1 on every playback.
    val dvContentPlayed = diagnostics.dvSourceProfile != null ||
        diagnostics.dv7DoviSuccess > 0 ||
        diagnostics.dv7DoviSignalRewrites > 0 ||
        (diagnostics.videoHdrType?.contains("Dolby Vision", ignoreCase = true) == true)
    val dvEngaged = dvCurrentlyEnabled &&
        diagnostics.dv7ModeRequested.isNotBlank() &&
        diagnostics.dv7ModeRequested != "OFF" &&
        dvContentPlayed
    fun dv(value: String?): String =
        if (dvEngaged) (value?.takeIf { it.isNotBlank() } ?: "-") else "-"

    // Card 1: Source + Display + Decoder + Bridge (the input side)
    item(key = "diag_card_input") {
        DiagnosticsSectionCard {
            SectionHeader("Source & Hardware", "Take a photo of this card if reporting an issue.")
            DiagnosticRow("Host", diagnostics.host)
            DiagnosticRow("When", formatTimestamp(diagnostics.timestampMs))
            DiagnosticRow("Device", deviceName())
            DiagnosticRow(
                "Display",
                dv(
                    if (diagnostics.hdrCapsKnown) {
                        buildString {
                            if (diagnostics.displayDv) append("DV ")
                            if (diagnostics.displayHdr10Plus) append("HDR10+ ")
                            else if (diagnostics.displayHdr10) append("HDR10 ")
                            if (!diagnostics.displayDv && !diagnostics.displayHdr10) append("SDR")
                        }.trim().ifBlank { "Unknown" }
                    } else {
                        "Unknown"
                    }
                )
            )
            DiagnosticRow(
                "DV7 Decoder",
                dv(if (diagnostics.codecDv7Supported) "Available" else "Not available")
            )
            DiagnosticRow(
                "DV Decoder",
                dv(
                    diagnostics.dv81DecoderName
                        ?: findAnyDvDecoderName()?.let { "$it (hidden)" }
                        ?: "None"
                )
            )
            DiagnosticRow(
                "DV Bridge",
                dv(if (diagnostics.bridgeReady) "Ready" else "Not ready")
            )
            if (dvEngaged) {
                diagnostics.bridgeVersion?.let { DiagnosticRow("Bridge Version", it) }
                diagnostics.bridgeReason?.let { DiagnosticRow("Bridge Reason", it) }
            }
        }
    }

    // Card 2: Decision + Settings (the configured/effective behaviour)
    item(key = "diag_card_decision") {
        DiagnosticsSectionCard {
            SectionHeader("Decision & Settings")
            DiagnosticRow("DV Mode (requested)", dv(diagnostics.dv7ModeRequested))
            if (dvEngaged && diagnostics.dv7ModeRequested != diagnostics.dv7ModeEffective) {
                DiagnosticRow("DV Mode (effective)", dv(diagnostics.dv7ModeEffective))
            }
            DiagnosticRow("AUTO Decision", dv(diagnostics.dv7AutoDecision))
            if (dvEngaged) {
                diagnostics.dvSourceProfile?.let { DiagnosticRow("Source Profile", it) }
                if (diagnostics.dv7DoviCalls > 0) {
                    DiagnosticRow(
                        "Conversions",
                        "${diagnostics.dv7DoviSuccess} of ${diagnostics.dv7DoviCalls} successful"
                    )
                }
                if (diagnostics.dv7DoviSignalRewrites > 0) {
                    DiagnosticRow("Signal Rewrites", diagnostics.dv7DoviSignalRewrites.toString())
                }
            }
            DiagnosticRow(
                "Custom Buffers",
                if (diagnostics.bufferEngineEnabled) "On" else "Off"
            )
            DiagnosticRow(
                "Custom Network/Cache",
                if (diagnostics.parallelNetworkEnabled) "On" else "Off"
            )
        }
    }

    // Card 3: Outcome
    item(key = "diag_card_outcome") {
        DiagnosticsSectionCard {
            SectionHeader("Outcome")
            DiagnosticRow("HDR Format (intended)", diagnostics.videoHdrType?.takeIf { it.isNotBlank() } ?: "-")
            DiagnosticRow(
                "First Frame",
                if (diagnostics.firstFrameMs >= 0) "${diagnostics.firstFrameMs} ms" else "Never rendered"
            )
            DiagnosticRow(
                "Rebuffers",
                if (diagnostics.rebufferCount > 0)
                    "${diagnostics.rebufferCount} (${diagnostics.rebufferTotalMs} ms)"
                else "0"
            )
            DiagnosticRow(
                "Result",
                diagnostics.result,
                valueColor = when {
                    diagnostics.result.startsWith("Error", ignoreCase = true) -> Color(0xFFF44336)
                    diagnostics.result == "Played" -> Color(0xFF4CAF50)
                    else -> NuvioColors.TextPrimary
                }
            )
        }
    }
}

@Composable
private fun DiagnosticsSectionCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        onClick = { /* read-only */ },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(10.dp)
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SectionHeader(label: String, subtitle: String? = null) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = NuvioColors.Primary
    )
    if (subtitle != null) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextSecondary,
            fontSize = 11.sp
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    valueColor: Color = NuvioColors.TextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextSecondary,
            modifier = Modifier.width(170.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun formatTimestamp(ms: Long): String {
    if (ms == 0L) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}

/** "Manufacturer Model", de-duplicated (e.g. avoids "Xiaomi Xiaomi ..."). */
private fun deviceName(): String {
    val manufacturer = android.os.Build.MANUFACTURER?.trim().orEmpty()
    val model = android.os.Build.MODEL?.trim().orEmpty()
    return when {
        model.isBlank() -> manufacturer.ifBlank { "Unknown" }
        manufacturer.isBlank() -> model
        model.startsWith(manufacturer, ignoreCase = true) -> model
        else -> "$manufacturer $model"
    }.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
}

private fun findAnyDvDecoderName(): String? {
    return runCatching {
        val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
        list.codecInfos
            .filter { !it.isEncoder }
            .firstOrNull { info ->
                info.supportedTypes.any { it.equals("video/dolby-vision", ignoreCase = true) }
            }
            ?.name
    }.getOrNull()
}