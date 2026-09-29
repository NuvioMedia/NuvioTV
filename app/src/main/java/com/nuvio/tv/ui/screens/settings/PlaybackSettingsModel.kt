package com.nuvio.tv.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.ui.graphics.vector.ImageVector
import com.nuvio.tv.R
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings

internal typealias PlaybackSettingsUpdate = (suspend PlaybackSettingsViewModel.() -> Unit) -> Unit

internal enum class PlaybackSection(
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
    val icon: ImageVector
) {
    PLAYER(R.string.playback_section_player_title, R.string.playback_section_player_title_desc, Icons.Default.SmartDisplay),
    STREAM_SELECTION(R.string.playback_section_stream_selection, R.string.playback_section_stream_selection_desc, Icons.AutoMirrored.Filled.PlaylistPlay),
    UP_NEXT(R.string.playback_section_up_next, R.string.playback_section_up_next_desc, Icons.Default.QueuePlayNext),
    SKIP_SEGMENTS(R.string.playback_section_skip, R.string.playback_section_skip_desc, Icons.Default.FastForward),
    PLAYER_INTERFACE(R.string.playback_section_interface, R.string.playback_section_interface_desc, Icons.Default.Layers),
    AUDIO(R.string.audio_section, R.string.playback_section_audio_only_desc, Icons.AutoMirrored.Filled.VolumeUp),
    SUBTITLES(R.string.playback_section_subtitles, R.string.playback_section_subtitles_desc, Icons.Default.Subtitles),
    VIDEO(R.string.video_section, R.string.playback_section_video_desc, Icons.Default.VideoSettings),
    BUFFER_NETWORK(R.string.playback_section_buffer_network, R.string.playback_section_buffer_network_desc, Icons.Default.NetworkCheck),
    P2P(R.string.settings_p2p_title, R.string.settings_p2p_subtitle, Icons.Default.Hub)
}

internal fun visiblePlaybackSections(settings: PlayerSettings): List<PlaybackSection> =
    PlaybackSection.entries.filter { section ->
        section != PlaybackSection.BUFFER_NETWORK || settings.usesExoPlayerEngine
    }

internal val PlayerSettings.usesExoPlayerEngine: Boolean
    get() = internalPlayerEngine == InternalPlayerEngine.EXOPLAYER ||
        internalPlayerEngine == InternalPlayerEngine.AUTO

internal val PlayerSettings.usesMpvEngine: Boolean
    get() = internalPlayerEngine == InternalPlayerEngine.MVP_PLAYER ||
        internalPlayerEngine == InternalPlayerEngine.AUTO

internal enum class PlaybackDialog {
    PLAYER_PREFERENCE,
    INTERNAL_ENGINE,
    STREAM_AUTO_PLAY_MODE,
    STREAM_AUTO_PLAY_SOURCE,
    STREAM_AUTO_PLAY_ADDONS,
    STREAM_AUTO_PLAY_PLUGINS,
    STREAM_REGEX,
    REUSE_LAST_LINK_CACHE,
    NEXT_EPISODE_THRESHOLD_MODE,
    AUDIO_LANGUAGE,
    SECONDARY_AUDIO_LANGUAGE,
    DECODER_PRIORITY,
    AUDIO_OUTPUT_CHANNELS,
    SUBTITLE_LANGUAGE,
    SECONDARY_SUBTITLE_LANGUAGE,
    SUBTITLE_TEXT_COLOR,
    SUBTITLE_BACKGROUND_COLOR,
    SUBTITLE_OUTLINE_COLOR,
    LIBASS_RENDER_TYPE,
    FRAME_RATE_MATCHING,
    DV7_HANDLING_MODE,
    MPV_HARDWARE_DECODE_MODE,
    P2P_CONSENT,
    TORRENT_PROFILE,
    TORRENT_CACHE_SIZE
}
