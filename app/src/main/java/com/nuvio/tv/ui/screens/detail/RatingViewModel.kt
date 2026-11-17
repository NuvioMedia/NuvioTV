package com.nuvio.tv.ui.screens.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRatingState
import com.nuvio.tv.core.tracking.TrackingRatings
import com.nuvio.tv.core.tracking.buildTrackingMediaReference
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

data class RatingResult(
    val saved: Boolean,
    val removed: Boolean,
    val provider: TrackingProviderId?
)

@HiltViewModel
class RatingViewModel @Inject constructor(
    private val ratings: TrackingRatings
) : ViewModel() {
    private val _results = MutableSharedFlow<RatingResult>(extraBufferCapacity = 1)
    val results: SharedFlow<RatingResult> = _results.asSharedFlow()

    fun observe(media: TrackingMediaReference): Flow<TrackingRatingState> = ratings.observe(media)

    fun current(media: TrackingMediaReference): TrackingRatingState = ratings.current(media)

    fun rate(
        media: TrackingMediaReference,
        rating: Int?,
        provider: TrackingProviderId?,
        onFinished: () -> Unit
    ) {
        viewModelScope.launch {
            val saved = try {
                ratings.rate(media, rating)
            } finally {
                onFinished()
            }
            _results.emit(RatingResult(saved = saved, removed = rating == null, provider = provider))
        }
    }
}

@Composable
internal fun rememberRatingState(
    media: TrackingMediaReference,
    viewModel: RatingViewModel = hiltViewModel()
): State<TrackingRatingState> {
    val flow = remember(media) { viewModel.observe(media) }
    val initial = remember(media) { viewModel.current(media) }
    return flow.collectAsStateWithLifecycle(initial)
}

internal fun Meta.ratingReference(episode: Video? = null): TrackingMediaReference {
    val reference = buildTrackingMediaReference(
        contentType = apiType,
        parentMetaId = id,
        videoId = episode?.id,
        title = name,
        releaseInfo = releaseInfo,
        seasonNumber = episode?.season,
        episodeNumber = episode?.episode,
        episodeTitle = episode?.title
    )
    return reference.copy(ids = reference.ids.mergeMissing(TrackingExternalIds(imdb = imdbId)))
}
