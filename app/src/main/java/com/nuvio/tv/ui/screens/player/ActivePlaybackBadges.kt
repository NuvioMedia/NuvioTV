package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.util.languageCodeToName
import java.util.Locale

data class AudioFormatBadge(
    val label: String,
    val isDolby: Boolean
)

internal object ActivePlaybackBadgeUtils {

    private val IGNORED_LANGUAGE_CODES = setOf("und", "unknown", "mul", "zxx", "none", "null")

    fun formatVideoQuality(width: Int?, height: Int?): String? {
        val w = width ?: 0
        val h = height ?: 0
        return when {
            w <= 0 && h <= 0 -> null
            h >= 4320 || w >= 7600 -> "8K"
            h >= 2160 || w >= 3800 -> "4K"
            h >= 1440 || w >= 2500 -> "1440p"
            h >= 1080 || w >= 1900 -> "1080p"
            h >= 720 || w >= 1260 -> "720p"
            h >= 540 || w >= 960 -> "540p"
            h >= 480 || w >= 840 -> "480p"
            h > 0 -> "${h}p"
            else -> null
        }
    }

    fun detectHdrFormat(
        mimeType: String?,
        codecs: String?,
        colorTransfer: Int?,
        label: String? = null,
        colormatrix: String? = null,
        gamma: String? = null,
        dolbyVisionProfile: String? = null,
        streamHints: List<String?> = emptyList()
    ): String? {
        val lowerMime = mimeType?.lowercase(Locale.US).orEmpty()
        val lowerCodecs = codecs?.lowercase(Locale.US).orEmpty()
        val lowerLabel = label?.lowercase(Locale.US).orEmpty()
        val lowerMatrix = colormatrix?.lowercase(Locale.US).orEmpty()
        val lowerGamma = gamma?.lowercase(Locale.US).orEmpty()
        val hasDvProfile = !dolbyVisionProfile.isNullOrBlank()

        // 1. Dolby Vision
        val isDolbyVision = hasDvProfile ||
            lowerMime == MimeTypes.VIDEO_DOLBY_VISION ||
            lowerMatrix == "dolbyvision" ||
            lowerCodecs.contains("dvhe") ||
            lowerCodecs.contains("dvh1") ||
            lowerCodecs.contains("dav1") ||
            lowerCodecs.contains("dva1") ||
            lowerCodecs.contains("dovi") ||
            lowerLabel.contains("dolby vision") ||
            Regex("\\b(dovi|dv)\\b").containsMatchIn(lowerLabel)
        if (isDolbyVision) {
            return "Dolby Vision"
        }

        // 2. PQ / SMPTE ST 2084 (HDR10+ or HDR10)
        val isPq = colorTransfer == C.COLOR_TRANSFER_ST2084 ||
            lowerGamma == "pq" ||
            lowerGamma == "smpte2084" ||
            lowerGamma == "st2084"
        if (isPq) {
            val allHints = (listOf(lowerLabel, lowerCodecs) + streamHints.mapNotNull { it?.lowercase(Locale.US) })
            val isHdr10Plus = allHints.any { hint ->
                hint.contains("hdr10+") || hint.contains("hdr10plus")
            }
            return if (isHdr10Plus) "HDR10+" else "HDR10"
        }

        // 3. HLG (Hybrid Log-Gamma)
        val isHlg = colorTransfer == C.COLOR_TRANSFER_HLG ||
            lowerGamma == "hlg" ||
            lowerGamma == "arib-std-b67"
        if (isHlg) {
            return "HLG"
        }

        // 4. Fallback to explicit track label / codec tags
        if (lowerLabel.contains("hdr10+") || lowerLabel.contains("hdr10plus")) return "HDR10+"
        if (lowerLabel.contains("hdr10") || lowerCodecs.contains("hdr10")) return "HDR10"
        if (Regex("\\bhlg\\b").containsMatchIn(lowerLabel)) return "HLG"

        return null
    }

    fun detectAudioFormat(
        mimeType: String?,
        codecs: String?,
        label: String?,
        channelCount: Int?,
        decoderDesc: String? = null
    ): AudioFormatBadge? {
        val lowerMime = mimeType?.lowercase(Locale.US).orEmpty()
        val lowerCodecs = codecs?.lowercase(Locale.US).orEmpty()
        val lowerLabel = label?.lowercase(Locale.US).orEmpty()
        val lowerDesc = decoderDesc?.lowercase(Locale.US).orEmpty()
        val combinedMeta = "$lowerCodecs $lowerLabel $lowerDesc"

        // 1. Dolby Atmos (EAC3-JOC or TrueHD/EAC3 with Atmos metadata/label)
        if (
            lowerMime == MimeTypes.AUDIO_E_AC3_JOC.lowercase(Locale.US) ||
            lowerCodecs.contains("ec+3") ||
            lowerCodecs.contains("joc") ||
            combinedMeta.contains("atmos")
        ) {
            return AudioFormatBadge(label = "Atmos", isDolby = true)
        }

        // 2. Dolby TrueHD
        if (
            lowerMime == MimeTypes.AUDIO_TRUEHD.lowercase(Locale.US) ||
            lowerCodecs.contains("truehd") ||
            lowerCodecs.contains("mlp") ||
            combinedMeta.contains("truehd")
        ) {
            return AudioFormatBadge(label = "TrueHD", isDolby = true)
        }

        // 3. Dolby AC-4
        if (
            lowerMime == MimeTypes.AUDIO_AC4.lowercase(Locale.US) ||
            lowerCodecs.contains("ac-4") ||
            lowerCodecs.contains("ac4")
        ) {
            return AudioFormatBadge(label = "AC-4", isDolby = true)
        }

        // 4. Dolby Digital Plus (E-AC-3)
        if (
            lowerMime == MimeTypes.AUDIO_E_AC3.lowercase(Locale.US) ||
            lowerCodecs.contains("ec-3") ||
            lowerCodecs.contains("eac3") ||
            combinedMeta.contains("eac3") ||
            combinedMeta.contains("e-ac-3") ||
            combinedMeta.contains("dolby digital plus") ||
            combinedMeta.contains("dd+") ||
            Regex("\\bddp\\b").containsMatchIn(combinedMeta)
        ) {
            return AudioFormatBadge(label = "Dolby Digital+", isDolby = true)
        }

        // 5. Dolby Digital (AC-3)
        if (
            lowerMime == MimeTypes.AUDIO_AC3.lowercase(Locale.US) ||
            lowerCodecs.contains("ac-3") ||
            lowerCodecs.contains("ac3") ||
            lowerCodecs.contains("a52") ||
            combinedMeta.contains("dolby digital") ||
            Regex("\\bac-?3\\b").containsMatchIn(combinedMeta)
        ) {
            return AudioFormatBadge(label = "Dolby Digital", isDolby = true)
        }

        // 6. DTS:X
        if (
            combinedMeta.contains("dts:x") ||
            combinedMeta.contains("dtsx") ||
            combinedMeta.contains("dts-x")
        ) {
            return AudioFormatBadge(label = "DTS:X", isDolby = false)
        }

        // 7. DTS-HD
        if (
            lowerMime == MimeTypes.AUDIO_DTS_HD.lowercase(Locale.US) ||
            lowerMime == MimeTypes.AUDIO_DTS_EXPRESS.lowercase(Locale.US) ||
            lowerCodecs.contains("dtshd") ||
            lowerCodecs.contains("dtse") ||
            lowerCodecs.contains("dtsl") ||
            combinedMeta.contains("dts-hd") ||
            combinedMeta.contains("dts hd") ||
            combinedMeta.contains("dts-ma")
        ) {
            return AudioFormatBadge(label = "DTS-HD", isDolby = false)
        }

        // 8. DTS
        if (
            lowerMime == MimeTypes.AUDIO_DTS.lowercase(Locale.US) ||
            lowerMime.startsWith("audio/vnd.dts") ||
            lowerCodecs.contains("dts") ||
            lowerCodecs.contains("dca") ||
            Regex("\\bdts\\b").containsMatchIn(combinedMeta)
        ) {
            return AudioFormatBadge(label = "DTS", isDolby = false)
        }

        // 9. Lossless / PCM formats
        if (lowerMime == MimeTypes.AUDIO_FLAC.lowercase(Locale.US) || lowerCodecs.contains("flac")) {
            return AudioFormatBadge(label = "FLAC", isDolby = false)
        }
        if (lowerMime == MimeTypes.AUDIO_ALAC.lowercase(Locale.US) || lowerCodecs.contains("alac")) {
            return AudioFormatBadge(label = "ALAC", isDolby = false)
        }
        if (
            lowerMime == MimeTypes.AUDIO_RAW.lowercase(Locale.US) ||
            lowerMime == MimeTypes.AUDIO_WAV.lowercase(Locale.US) ||
            lowerCodecs.startsWith("pcm")
        ) {
            return AudioFormatBadge(label = "PCM", isDolby = false)
        }

        // 10. Standard stereo or compressed codecs (AAC, Opus, Vorbis, MP3)
        if (channelCount == 2) {
            return AudioFormatBadge(label = "Stereo", isDolby = false)
        }
        if (lowerMime == MimeTypes.AUDIO_OPUS.lowercase(Locale.US) || lowerCodecs.contains("opus")) {
            return AudioFormatBadge(label = "Opus", isDolby = false)
        }
        if (
            lowerMime == MimeTypes.AUDIO_AAC.lowercase(Locale.US) ||
            lowerCodecs.startsWith("mp4a") ||
            lowerCodecs.contains("aac")
        ) {
            return AudioFormatBadge(label = "AAC", isDolby = false)
        }
        if (lowerMime == MimeTypes.AUDIO_VORBIS.lowercase(Locale.US) || lowerCodecs.contains("vorbis")) {
            return AudioFormatBadge(label = "Vorbis", isDolby = false)
        }
        if (
            lowerMime == MimeTypes.AUDIO_MPEG.lowercase(Locale.US) ||
            lowerMime == MimeTypes.AUDIO_MPEG_L2.lowercase(Locale.US) ||
            lowerCodecs.contains("mp3")
        ) {
            return AudioFormatBadge(label = "MP3", isDolby = false)
        }
        if (channelCount == 1) {
            return AudioFormatBadge(label = "Mono", isDolby = false)
        }

        return null
    }

    fun formatChannelNumeric(channelCount: Int?): String? {
        return when (channelCount) {
            8 -> "7.1"
            7 -> "6.1"
            6 -> "5.1"
            5 -> "5.0"
            4 -> "4.0"
            3 -> "2.1"
            2 -> "2.0"
            1 -> "1.0"
            else -> if (channelCount != null && channelCount > 0) "${channelCount}ch" else null
        }
    }

    fun formatAudioLanguageAndChannels(
        languageCode: String?,
        label: String?,
        channelCount: Int?,
        contentLanguageFallback: String? = null
    ): String? {
        val cleanCode = languageCode
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.lowercase(Locale.US) !in IGNORED_LANGUAGE_CODES }
        val cleanFallback = contentLanguageFallback
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.lowercase(Locale.US) !in IGNORED_LANGUAGE_CODES }

        val resolvedLanguage = cleanCode?.let { languageCodeToName(it) }
            ?: extractLanguageFromLabel(label)
            ?: cleanFallback?.let { languageCodeToName(it) }

        val resolvedChannels = formatChannelNumeric(channelCount)
            ?: extractChannelNumericFromLabel(label)

        return when {
            !resolvedLanguage.isNullOrBlank() && !resolvedChannels.isNullOrBlank() ->
                "$resolvedLanguage $resolvedChannels"
            !resolvedLanguage.isNullOrBlank() -> resolvedLanguage
            !resolvedChannels.isNullOrBlank() -> resolvedChannels
            else -> null
        }
    }

    private fun extractLanguageFromLabel(label: String?): String? {
        if (label.isNullOrBlank()) return null
        val cleaned = label
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*]"), " ")
            .replace(
                Regex(
                    "(?i)\\b(audio|track|stream|original|default|main|dub|dubbed|undetermined|unknown|dolby|atmos|truehd|digital|plus|dd\\+?|ddp|eac3|e-ac-3|ac3|ac-3|dts(?:-hd|:x|x|-ma)?|aac|he-aac|lc|flac|alac|opus|vorbis|mp3|pcm|stereo|mono|surround|7\\.1|6\\.1|5\\.1|5\\.0|4\\.0|2\\.1|2\\.0|1\\.0|\\d+ch|\\d+\\s*channels?)\\b"
                ),
                " "
            )
            .replace(Regex("[-_•·|/:]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank() || cleaned.all { it.isDigit() }) return null
        return cleaned.takeIf { it.length in 2..24 }
    }

    private fun extractChannelNumericFromLabel(label: String?): String? {
        if (label.isNullOrBlank()) return null
        val match = Regex("\\b(7\\.1|6\\.1|5\\.1|5\\.0|4\\.0|2\\.1|2\\.0|1\\.0)\\b").find(label)
        if (match != null) return match.value
        val lower = label.lowercase(Locale.US)
        return when {
            lower.contains("stereo") -> "2.0"
            lower.contains("mono") -> "1.0"
            else -> null
        }
    }
}

@Composable
internal fun ActivePlaybackBadgesRow(
    videoQuality: String?,
    hdrFormat: String?,
    audioFormat: String?,
    isDolbyAudio: Boolean,
    audioLanguageLabel: String?,
    modifier: Modifier = Modifier
) {
    val hasQuality = !videoQuality.isNullOrBlank()
    val hasFormatPill = !hdrFormat.isNullOrBlank() || !audioFormat.isNullOrBlank()
    val hasLanguagePill = !audioLanguageLabel.isNullOrBlank()

    if (!hasQuality && !hasFormatPill && !hasLanguagePill) return

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (hasQuality) {
                PlaybackBadgePill {
                    Text(
                        text = videoQuality!!,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.3.sp
                        ),
                        color = Color.White
                    )
                }
            }

            if (hasFormatPill) {
                PlaybackBadgePill {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!hdrFormat.isNullOrBlank()) {
                            if (hdrFormat == "Dolby Vision") {
                                DolbyDoubleDIcon(
                                    modifier = Modifier.size(width = 15.dp, height = 11.dp),
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Dolby",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Vision",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Normal
                                    ),
                                    color = Color.White.copy(alpha = 0.92f)
                                )
                            } else {
                                Text(
                                    text = hdrFormat,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color.White
                                )
                            }
                        }

                        if (!hdrFormat.isNullOrBlank() && !audioFormat.isNullOrBlank()) {
                            Text(
                                text = "  •  ",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = Color.White.copy(alpha = 0.72f)
                            )
                        }

                        if (!audioFormat.isNullOrBlank()) {
                            if (isDolbyAudio) {
                                DolbyDoubleDIcon(
                                    modifier = Modifier.size(width = 15.dp, height = 11.dp),
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                            }
                            Text(
                                text = audioFormat,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color.White
                            )
                        }
                    }
                }
            }

            if ((hasQuality || hasFormatPill) && hasLanguagePill) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(Color.White.copy(alpha = 0.35f))
                )
            }

            if (hasLanguagePill) {
                PlaybackBadgePill {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AudioWaveBadgeIcon(
                            modifier = Modifier.size(width = 15.dp, height = 13.dp),
                            tint = Color.White.copy(alpha = 0.92f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = audioLanguageLabel!!,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackBadgePill(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF0E131A).copy(alpha = 0.68f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.24f),
                shape = shape
            )
            .padding(horizontal = 11.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun DolbyDoubleDIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val gap = w * 0.08f
        val dWidth = (w - gap) / 2f

        // Left 'D' (flat on left, curved on right)
        val leftPath = Path().apply {
            moveTo(0f, 0f)
            lineTo(dWidth * 0.42f, 0f)
            arcTo(
                rect = Rect(
                    left = dWidth * 0.42f - dWidth * 0.58f,
                    top = 0f,
                    right = dWidth,
                    bottom = h
                ),
                startAngleDegrees = -90f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false
            )
            lineTo(0f, h)
            close()
        }
        drawPath(path = leftPath, color = tint)

        // Right 'D' mirrored (curved on left, flat on right)
        val rightStart = dWidth + gap
        val rightPath = Path().apply {
            moveTo(w, 0f)
            lineTo(rightStart + dWidth * 0.58f, 0f)
            arcTo(
                rect = Rect(
                    left = rightStart,
                    top = 0f,
                    right = rightStart + dWidth * 1.16f,
                    bottom = h
                ),
                startAngleDegrees = -90f,
                sweepAngleDegrees = -180f,
                forceMoveTo = false
            )
            lineTo(w, h)
            close()
        }
        drawPath(path = rightPath, color = tint)
    }
}

@Composable
private fun AudioWaveBadgeIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = (h * 0.13f).coerceAtLeast(1.5f)

        // Left D-shaped speaker contour matching the mockup's D)) icon
        val dRight = w * 0.46f
        val dTop = h * 0.15f
        val dBottom = h * 0.85f
        val speakerPath = Path().apply {
            moveTo(w * 0.08f, dTop)
            lineTo(w * 0.22f, dTop)
            arcTo(
                rect = Rect(
                    left = w * 0.02f,
                    top = dTop,
                    right = dRight,
                    bottom = dBottom
                ),
                startAngleDegrees = -90f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false
            )
            lineTo(w * 0.08f, dBottom)
            close()
        }
        drawPath(
            path = speakerPath,
            color = tint,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // Inner sound wave arc
        drawArc(
            color = tint,
            startAngle = -45f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(w * 0.34f, h * 0.24f),
            size = Size(w * 0.36f, h * 0.52f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // Outer sound wave arc
        drawArc(
            color = tint,
            startAngle = -48f,
            sweepAngle = 96f,
            useCenter = false,
            topLeft = Offset(w * 0.48f, h * 0.10f),
            size = Size(w * 0.46f, h * 0.80f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}
