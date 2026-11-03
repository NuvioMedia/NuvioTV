package com.nuvio.tv.ui.reshaped.livetv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.buildStreamInfoData
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the Now/Next card shows about the playing picture and sound: "1080p", "50 fps", "H.264", "AAC · Stereo". */
@Immutable
internal data class LiveTvStreamDetails(
    val resolution: String? = null,
    val frameRate: String? = null,
    val videoCodec: String? = null,
    val audio: String? = null,
) {
    val labels: List<String> get() = listOfNotNull(resolution, frameRate, videoCodec, audio)
}

/**
 * Reads the playing channel's details from the player, the same numbers Nuvio's stream info shows.
 * Only while [active] (the card is open), once a second: a zap or a stream switching quality shows at once.
 */
@Composable
internal fun rememberLiveTvStreamDetails(controller: PlayerRuntimeController, active: Boolean): State<LiveTvStreamDetails> =
    produceState(LiveTvStreamDetails(), controller, active) {
        while (active) {
            value = controller.liveTvStreamDetails()
            delay(1_000L)
        }
    }

private fun PlayerRuntimeController.liveTvStreamDetails(): LiveTvStreamDetails {
    val info = runCatching { buildStreamInfoData() }.getOrNull() ?: return LiveTvStreamDetails()
    val fps = info.videoFrameRate?.takeIf { it > 0f }
        ?: _exoPlayer?.videoFormat?.frameRate?.takeIf { it > 0f }
    val audio = listOfNotNull(info.audioCodec?.let(::friendlyAudioCodec), info.audioChannels)
        .distinct()
        .joinToString(" · ")
        .ifBlank { null }
    return LiveTvStreamDetails(
        resolution = resolutionLabel(info.videoWidth, info.videoHeight),
        frameRate = fps?.let(::frameRateLabel),
        videoCodec = info.videoCodec?.takeIf(String::isNotBlank),
        audio = audio,
    )
}

private fun resolutionLabel(width: Int?, height: Int?): String? {
    val h = height?.takeIf { it > 0 } ?: return null
    val w = width ?: 0
    return when {
        w >= 3800 || h >= 2100 -> "4K"
        w >= 1900 || h >= 1060 -> "1080p"
        w >= 1260 || h >= 700 -> "720p"
        else -> "${h}p"
    }
}

/** 25, 50, 59.94, 23.976 as a broadcaster would write it. */
private fun frameRateLabel(fps: Float): String {
    val rounded = fps.roundToInt()
    return if (abs(fps - rounded) < 0.02f) "$rounded fps" else "%.2f fps".format(java.util.Locale.US, fps).replace(".00", "")
}

private fun friendlyAudioCodec(codec: String): String = when (codec) {
    "AC-3" -> "Dolby Digital"
    "E-AC-3" -> "Dolby Digital+"
    "E-AC-3-JOC" -> "Dolby Atmos"
    else -> codec
}
