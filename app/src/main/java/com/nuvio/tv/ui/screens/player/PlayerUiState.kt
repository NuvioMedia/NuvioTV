package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.ui.AspectRatioFrameLayout
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.SubtitleOrganizationMode
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val playbackEnded: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val title: String = "",
    val contentName: String? = null, // Series/show name (for series content)
    val releaseYear: String? = null, // Release year for movies
    val contentType: String? = null,
    val currentStreamName: String? = null, // Name of the current stream source
    val currentStreamUrl: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val castMembers: List<MetaCastMember> = emptyList(),
    val showControls: Boolean = true,
    val showSeekOverlay: Boolean = false,
    val playbackSpeed: Float = 1f,
    val loadingOverlayEnabled: Boolean = true,
    val showLoadingOverlay: Boolean = true,
    val pauseOverlayEnabled: Boolean = true,
    val osdClockEnabled: Boolean = true,
    val showPauseOverlay: Boolean = false,
    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val selectedAudioTrackIndex: Int = -1,
    val selectedSubtitleTrackIndex: Int = -1,
    val showAudioDialog: Boolean = false,
    val showSubtitleDialog: Boolean = false,
    val showSubtitleStylePanel: Boolean = false,
    val showSubtitleDelayOverlay: Boolean = false,
    val subtitleDelayMs: Int = 0,
    val showSpeedDialog: Boolean = false,
    val showMoreDialog: Boolean = false,
    // Subtitle style settings
    val subtitleStyle: SubtitleStyleSettings = SubtitleStyleSettings(),
    val subtitleOrganizationMode: SubtitleOrganizationMode = SubtitleOrganizationMode.NONE,
    // Addon subtitles
    val addonSubtitles: List<Subtitle> = emptyList(),
    val isLoadingAddonSubtitles: Boolean = false,
    val selectedAddonSubtitle: Subtitle? = null,
    val addonSubtitlesError: String? = null,
    // Episodes/streams side panel (for series)
    val showEpisodesPanel: Boolean = false,
    val isLoadingEpisodes: Boolean = false,
    val episodesError: String? = null,
    val episodesAll: List<Video> = emptyList(),
    val episodesAvailableSeasons: List<Int> = emptyList(),
    val episodesSelectedSeason: Int? = null,
    val episodes: List<Video> = emptyList(),
    val currentSeason: Int? = null,
    val currentEpisode: Int? = null,
    val currentEpisodeTitle: String? = null,
    val blurUnwatchedEpisodes: Boolean = false,
    val episodeWatchProgressMap: Map<Pair<Int, Int>, WatchProgress> = emptyMap(),
    val watchedEpisodeKeys: Set<Pair<Int, Int>> = emptySet(),
    val showEpisodeStreams: Boolean = false,
    val isLoadingEpisodeStreams: Boolean = false,
    val episodeStreamsError: String? = null,
    val episodeAllStreams: List<Stream> = emptyList(),
    val episodeSelectedAddonFilter: String? = null, // null means "All"
    val episodeFilteredStreams: List<Stream> = emptyList(),
    val episodeAvailableAddons: List<String> = emptyList(),
    val episodeStreamsForVideoId: String? = null,
    val episodeStreamsSeason: Int? = null,
    val episodeStreamsEpisode: Int? = null,
    val episodeStreamsTitle: String? = null,
    // Stream sources side panel (for switching streams during playback)
    val showSourcesPanel: Boolean = false,
    val isLoadingSourceStreams: Boolean = false,
    val sourceStreamsError: String? = null,
    val sourceAllStreams: List<Stream> = emptyList(),
    val sourceSelectedAddonFilter: String? = null, // null means "All"
    val sourceFilteredStreams: List<Stream> = emptyList(),
    val sourceAvailableAddons: List<String> = emptyList(),
    val error: String? = null,
    val pendingSeekPosition: Long? = null,  // For resuming from saved progress
    // Parental guide overlay
    val parentalWarnings: List<ParentalWarning> = emptyList(),
    val showParentalGuide: Boolean = false,
    val parentalGuideHasShown: Boolean = false,
    // Skip intro
    val activeSkipInterval: SkipInterval? = null,
    val skipIntervalDismissed: Boolean = false,
    // Next episode card
    val nextEpisode: NextEpisodeInfo? = null,
    val showNextEpisodeCard: Boolean = false,
    val nextEpisodeCardDismissed: Boolean = false,
    val nextEpisodeAutoPlaySearching: Boolean = false,
    val nextEpisodeAutoPlaySourceName: String? = null,
    val nextEpisodeAutoPlayCountdownSec: Int? = null,
    // Stream source badge
    val showStreamSourceIndicator: Boolean = false,
    val streamSourceIndicatorText: String = "",
    // Frame rate matching
    val detectedFrameRateRaw: Float = 0f,
    val detectedFrameRateSource: FrameRateSource? = null,
    val detectedFrameRate: Float = 0f,
    val frameRateMatchingMode: FrameRateMatchingMode = FrameRateMatchingMode.OFF,
    val displayModeInfo: DisplayModeInfo? = null,
    val showDisplayModeInfo: Boolean = false,
    // Aspect ratio / resize mode
    val resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    val showAspectRatioIndicator: Boolean = false,
    val aspectRatioIndicatorText: String = ""
)

data class TrackInfo(
    val index: Int,
    val name: String,
    val language: String?,
    val trackId: String? = null,
    val codec: String? = null,
    val channelCount: Int? = null,
    val isSelected: Boolean = false
)

data class NextEpisodeInfo(
    val videoId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val thumbnail: String?,
    val overview: String?,
    val released: String?,
    val hasAired: Boolean,
    val unairedMessage: String?
)

sealed class PlayerEvent {
    data object OnPlayPause : PlayerEvent()
    data object OnSeekForward : PlayerEvent()
    data object OnSeekBackward : PlayerEvent()
    data class OnSeekBy(val deltaMs: Long) : PlayerEvent()
    data class OnPreviewSeekBy(val deltaMs: Long) : PlayerEvent()
    data object OnCommitPreviewSeek : PlayerEvent()
    data class OnSeekTo(val position: Long) : PlayerEvent()
    data class OnSelectAudioTrack(val index: Int) : PlayerEvent()
    data class OnSelectSubtitleTrack(val index: Int) : PlayerEvent()
    data object OnDisableSubtitles : PlayerEvent()
    data class OnSelectAddonSubtitle(val subtitle: Subtitle) : PlayerEvent()
    data class OnSetPlaybackSpeed(val speed: Float) : PlayerEvent()
    data object OnToggleControls : PlayerEvent()
    data object OnShowAudioDialog : PlayerEvent()
    data object OnShowSubtitleDialog : PlayerEvent()
    data object OnOpenSubtitleStylePanel : PlayerEvent()
    data object OnDismissSubtitleStylePanel : PlayerEvent()
    data object OnShowSubtitleDelayOverlay : PlayerEvent()
    data object OnHideSubtitleDelayOverlay : PlayerEvent()
    data class OnAdjustSubtitleDelay(val deltaMs: Int) : PlayerEvent()
    data object OnShowSpeedDialog : PlayerEvent()
    data object OnShowMoreDialog : PlayerEvent()
    data object OnDismissMoreDialog : PlayerEvent()
    data object OnShowEpisodesPanel : PlayerEvent()
    data object OnDismissEpisodesPanel : PlayerEvent()
    data object OnBackFromEpisodeStreams : PlayerEvent()
    data class OnEpisodeSeasonSelected(val season: Int) : PlayerEvent()
    data class OnEpisodeSelected(val video: Video) : PlayerEvent()
    data object OnReloadEpisodeStreams : PlayerEvent()
    data class OnEpisodeAddonFilterSelected(val addonName: String?) : PlayerEvent()
    data class OnEpisodeStreamSelected(val stream: Stream) : PlayerEvent()
    data object OnShowSourcesPanel : PlayerEvent()
    data object OnDismissSourcesPanel : PlayerEvent()
    data object OnReloadSourceStreams : PlayerEvent()
    data class OnSourceAddonFilterSelected(val addonName: String?) : PlayerEvent()
    data class OnSourceStreamSelected(val stream: Stream) : PlayerEvent()
    data object OnDismissDialog : PlayerEvent()
    data object OnRetry : PlayerEvent()
    data object OnParentalGuideHide : PlayerEvent()
    data class OnShowDisplayModeInfo(val info: DisplayModeInfo) : PlayerEvent()
    data object OnHideDisplayModeInfo : PlayerEvent()
    data object OnDismissPauseOverlay : PlayerEvent()
    data object OnSkipIntro : PlayerEvent()
    data object OnDismissSkipIntro : PlayerEvent()
    data object OnPlayNextEpisode : PlayerEvent()
    data object OnDismissNextEpisodeCard : PlayerEvent()
    // Subtitle style events (for in-player style tab)
    data class OnSetSubtitleSize(val size: Int) : PlayerEvent()
    data class OnSetSubtitleTextColor(val color: Int) : PlayerEvent()
    data class OnSetSubtitleBold(val bold: Boolean) : PlayerEvent()
    data class OnSetSubtitleOutlineEnabled(val enabled: Boolean) : PlayerEvent()
    data class OnSetSubtitleOutlineColor(val color: Int) : PlayerEvent()
    data class OnSetSubtitleVerticalOffset(val offset: Int) : PlayerEvent()
    data object OnResetSubtitleDefaults : PlayerEvent()
    data object OnToggleAspectRatio : PlayerEvent()
}

data class ParentalWarning(
    val label: String,
    val severity: String
)

data class DisplayModeInfo(
    val width: Int,
    val height: Int,
    val refreshRate: Float,
    val statusMessage: String? = null
)

enum class FrameRateSource {
    TRACK,
    PROBE
}

val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
