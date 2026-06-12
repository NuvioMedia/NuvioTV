@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.DolbyVisionCodecFallback
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
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
                    text = stringResource(R.string.diag_last_playback_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.Primary
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                Text(
                    text = stringResource(R.string.diag_no_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
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
        val unknownLabel = stringResource(R.string.type_unknown)
        DiagnosticsSectionCard {
            SectionHeader(
                stringResource(R.string.diag_section_source_hardware),
                stringResource(R.string.diag_section_source_hardware_sub)
            )
            DiagnosticRow(stringResource(R.string.diag_label_host), diagnostics.host)
            DiagnosticRow(stringResource(R.string.diag_label_when), formatTimestamp(diagnostics.timestampMs))
            DiagnosticRow(stringResource(R.string.diag_label_device), deviceName(unknownLabel))
            DiagnosticRow(
                stringResource(R.string.diag_label_display),
                dv(
                    if (diagnostics.hdrCapsKnown) {
                        buildString {
                            if (diagnostics.displayDv) append("DV ")
                            if (diagnostics.displayHdr10Plus) append("HDR10+ ")
                            else if (diagnostics.displayHdr10) append("HDR10 ")
                            if (!diagnostics.displayDv && !diagnostics.displayHdr10) append("SDR")
                        }.trim().ifBlank { unknownLabel }
                    } else {
                        unknownLabel
                    }
                )
            )
            DiagnosticRow(
                stringResource(R.string.diag_label_dv7_decoder),
                dv(
                    if (diagnostics.codecDv7Supported) stringResource(R.string.diag_value_available)
                    else stringResource(R.string.diag_value_not_available)
                )
            )
            DiagnosticRow(
                stringResource(R.string.diag_label_dv_decoder),
                dv(
                    diagnostics.dv81DecoderName
                        ?: findAnyDvDecoderName()?.let { stringResource(R.string.diag_value_decoder_hidden, it) }
                        ?: stringResource(R.string.diag_value_none)
                )
            )
            DiagnosticRow(
                stringResource(R.string.diag_label_dv_bridge),
                dv(
                    if (diagnostics.bridgeReady) stringResource(R.string.diag_value_ready)
                    else stringResource(R.string.diag_value_not_ready)
                )
            )
            if (dvEngaged) {
                diagnostics.bridgeVersion?.let { DiagnosticRow(stringResource(R.string.diag_label_bridge_version), it) }
                diagnostics.bridgeReason?.let { DiagnosticRow(stringResource(R.string.diag_label_bridge_reason), it) }
            }
        }
    }

    // Card 2: Decision + Settings (the configured/effective behaviour)
    item(key = "diag_card_decision") {
        DiagnosticsSectionCard {
            SectionHeader(stringResource(R.string.diag_section_decision_settings))
            DiagnosticRow(stringResource(R.string.diag_label_dv_mode_requested), dv(diagnostics.dv7ModeRequested))
            if (dvEngaged && diagnostics.dv7ModeRequested != diagnostics.dv7ModeEffective) {
                DiagnosticRow(stringResource(R.string.diag_label_dv_mode_effective), dv(diagnostics.dv7ModeEffective))
            }
            DiagnosticRow(stringResource(R.string.diag_label_auto_decision), dv(diagnostics.dv7AutoDecision))
            if (dvEngaged) {
                diagnostics.dvSourceProfile?.let { DiagnosticRow(stringResource(R.string.diag_label_source_profile), it) }
                if (diagnostics.dv7DoviCalls > 0) {
                    DiagnosticRow(
                        stringResource(R.string.diag_label_conversions),
                        stringResource(
                            R.string.diag_value_conversions_fmt,
                            diagnostics.dv7DoviSuccess,
                            diagnostics.dv7DoviCalls
                        )
                    )
                }
                if (diagnostics.dv7DoviSignalRewrites > 0) {
                    DiagnosticRow(stringResource(R.string.diag_label_signal_rewrites), diagnostics.dv7DoviSignalRewrites.toString())
                }
            }
            DiagnosticRow(
                stringResource(R.string.diag_label_custom_buffers),
                if (diagnostics.bufferEngineEnabled) stringResource(R.string.diag_value_on)
                else stringResource(R.string.diag_value_off)
            )
            DiagnosticRow(
                stringResource(R.string.diag_label_custom_network_cache),
                if (diagnostics.parallelNetworkEnabled) stringResource(R.string.diag_value_on)
                else stringResource(R.string.diag_value_off)
            )
        }
    }

    // Card 3: Outcome
    item(key = "diag_card_outcome") {
        DiagnosticsSectionCard {
            SectionHeader(stringResource(R.string.diag_section_outcome))
            DiagnosticRow(stringResource(R.string.diag_label_hdr_format_intended), diagnostics.videoHdrType?.takeIf { it.isNotBlank() } ?: "-")
            DiagnosticRow(
                stringResource(R.string.diag_label_first_frame),
                if (diagnostics.firstFrameMs >= 0) "${diagnostics.firstFrameMs} ms"
                else stringResource(R.string.diag_value_never_rendered)
            )
            DiagnosticRow(
                stringResource(R.string.diag_label_rebuffers),
                if (diagnostics.rebufferCount > 0)
                    "${diagnostics.rebufferCount} (${diagnostics.rebufferTotalMs} ms)"
                else "0"
            )
            DiagnosticRow(
                stringResource(R.string.diag_label_result),
                diagnostics.result,
                valueColor = when {
                    diagnostics.result.startsWith("Error", ignoreCase = true) -> Color(0xFFF44336)
                    diagnostics.result == "Played" -> Color(0xFF4CAF50)
                    else -> NuvioTheme.colors.TextPrimary
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
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(10.dp)
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
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
        color = NuvioTheme.colors.Primary
    )
    if (subtitle != null) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            fontSize = 11.sp
        )
    }
    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    valueColor: Color = NuvioTheme.colors.TextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
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
private fun deviceName(unknownLabel: String): String {
    val manufacturer = android.os.Build.MANUFACTURER?.trim().orEmpty()
    val model = android.os.Build.MODEL?.trim().orEmpty()
    return when {
        model.isBlank() -> manufacturer.ifBlank { unknownLabel }
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