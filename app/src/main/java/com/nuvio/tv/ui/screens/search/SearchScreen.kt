package com.nuvio.tv.ui.screens.search

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.ui.components.DropdownOption
import com.nuvio.tv.ui.components.DropdownPicker
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.components.rememberShimmerBrush
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import com.nuvio.tv.ui.util.recompositionHighlighter
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Width of the WuPlay-style left panel: a 6-key row (6 * key size) plus key gaps and
 * screen padding, so keys keep the reference app's proportions. */
private val SEARCH_LEFT_PANEL_WIDTH =
    SearchVirtualKeyboardKeySize * 6 + NuvioTheme.spacing.xs * 5 + NuvioTheme.spacing.lg * 2

/** How many retries (50ms apart) for the initial focus grab before giving up. */
private const val MAX_INITIAL_FOCUS_ATTEMPTS = 20

/** Start inset of the search banner while the glass sidebar panel is open: the
 *  panel spans ≈14dp…254dp, so without this the first button ("Scopri") would
 *  be half-covered by the glass. */
private val SIDEBAR_PANEL_CLEARANCE = 240.dp

/** Start inset of the search banner with the icon rail collapsed: 48dp (xxxl)
 *  let the black rail overlap the first button ("Scopri" half-covered), so the
 *  row shifts a bit further right while staying aligned with the page. */
private val SIDEBAR_COLLAPSED_CLEARANCE = 72.dp

/** Maximum recent-search entries shown under the virtual keyboard. */
private const val PANEL_RECENT_SEARCH_LIMIT = 4

/** One cell of the merged single results grid, carrying the addon that produced it. */
private data class SearchGridEntry(
    val item: MetaPreview,
    val addonBaseUrl: String
) {
    fun key(): String = "${item.apiType}:${item.id}"

    /** True when this copy carries better metadata than [other] (same key). */
    fun isRicherThan(other: SearchGridEntry): Boolean {
        val poster = !item.poster.isNullOrBlank()
        val otherPoster = !other.item.poster.isNullOrBlank()
        if (poster != otherPoster) return poster
        return (item.imdbRating ?: -1f) > (other.item.imdbRating ?: -1f)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (catalogId: String, addonId: String, type: String) -> Unit = { _, _, _ -> },
    onOpenDiscover: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val watchedMovieIds by viewModel.watchedMovieIds.collectAsState()
    val watchedSeriesIds by viewModel.watchedSeriesIds.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val strVoiceNoSpeech = stringResource(R.string.search_voice_no_speech)
    val strVoiceMicPermission = stringResource(R.string.search_voice_mic_permission)
    val strVoiceFailed = stringResource(R.string.search_voice_failed)
    val strVoiceUnavailable = stringResource(R.string.search_voice_unavailable)
    val voiceFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val recentFirstItemFocusRequester = remember { FocusRequester() }
    var isSearchFieldFocused by remember { mutableStateOf(false) }
    var isRecentSearchSectionFocused by remember { mutableStateOf(false) }
    var focusResults by remember { mutableStateOf(false) }
    var pendingFocusMoveToResultsQuery by remember { mutableStateOf<String?>(null) }
    var pendingFocusMoveSawSearching by remember { mutableStateOf(false) }
    var pendingFocusMoveHadExistingSearchRows by remember { mutableStateOf(false) }
    var isVoiceListening by remember { mutableStateOf(false) }
    var voiceRmsLevel by remember { mutableStateOf(0f) }
    var discoverFocusedItemIndex by rememberSaveable { mutableStateOf(0) }
    var restoreDiscoverFocus by rememberSaveable { mutableStateOf(false) }
    var pendingDiscoverRestoreOnResume by rememberSaveable { mutableStateOf(false) }
    val restoringSearchFocus = remember { mutableStateOf(viewModel.hasSavedSearchFocus) }
    val didRestoreSearchFocus = remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val onVoiceQueryResultState = rememberUpdatedState<(String) -> Unit> { recognized ->
        if (recognized.isNotBlank()) {
            viewModel.onEvent(SearchEvent.QueryChanged(recognized))
            viewModel.onEvent(SearchEvent.SubmitSearch)
            focusResults = false
            pendingFocusMoveToResultsQuery = recognized
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows =
                uiState.submittedQuery.trim().length >= MIN_SEARCH_QUERY_LENGTH &&
                    uiState.catalogRows.any { it.items.isNotEmpty() }
        } else {
            Toast.makeText(context, strVoiceNoSpeech, Toast.LENGTH_SHORT).show()
        }
    }
    // SpeechRecognizer availability + creation are synchronous binder calls
    // into system_server: doing them in the first composition froze search
    // for over a second. Defer until the screen has painted.
    var isVoiceSearchAvailable by remember { mutableStateOf(false) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    LaunchedEffect(context) {
        delay(600)
        val available = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        isVoiceSearchAvailable = available
        if (available) {
            speechRecognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        }
    }
    val buildRecognizeIntent: () -> Intent = {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
    }
    val hasRecordAudioPermission by remember(context) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var recordAudioPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val requestAudioPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        recordAudioPermissionGranted = granted
        if (granted) {
            isVoiceListening = true
            runCatching {
                speechRecognizer?.cancel()
                speechRecognizer?.startListening(buildRecognizeIntent())
            }.onFailure {
                isVoiceListening = false
                Toast.makeText(context, strVoiceUnavailable, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, strVoiceMicPermission, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(hasRecordAudioPermission) {
        recordAudioPermissionGranted = hasRecordAudioPermission
    }
    DisposableEffect(speechRecognizer) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                // Normalize RMS dB to 0..1 range. Typical values: -2 (silence) to 10 (loud).
                voiceRmsLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                isVoiceListening = false
                voiceRmsLevel = 0f
                Log.w("SearchScreen", "Voice recognition error: $error")
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        Toast.makeText(context, strVoiceNoSpeech, Toast.LENGTH_SHORT).show()
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> Unit
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        Toast.makeText(context, strVoiceMicPermission, Toast.LENGTH_SHORT).show()
                    else ->
                        Toast.makeText(context, strVoiceFailed, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResults(results: Bundle?) {
                isVoiceListening = false
                voiceRmsLevel = 0f
                val recognized = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                onVoiceQueryResultState.value(recognized)
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
        }

        speechRecognizer?.setRecognitionListener(listener)
        onDispose {
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.destroy()
        }
    }
    val topInputFocusRequester = remember { searchFocusRequester }
    val launchVoiceSearch: () -> Unit = {
        val recognizer = speechRecognizer
        if (!isVoiceSearchAvailable || recognizer == null) {
            Toast.makeText(context, strVoiceUnavailable, Toast.LENGTH_SHORT).show()
        } else if (!recordAudioPermissionGranted) {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            isVoiceListening = true
            runCatching {
                recognizer.cancel()
                recognizer.startListening(buildRecognizeIntent())
            }.onFailure {
                isVoiceListening = false
                Toast.makeText(context, strVoiceUnavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Same poster size as Home/Discover so decode and memory-cache keys match.
    val posterCardStyle = remember(uiState.posterCardWidthDp, uiState.posterCardCornerRadiusDp) {
        PosterCardStyle(
            width = uiState.posterCardWidthDp.dp,
            height = (uiState.posterCardWidthDp * 1.5f).roundToInt().dp,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    val trimmedQuery = remember(uiState.query) { uiState.query.trim() }
    val trimmedSubmittedQuery = remember(uiState.submittedQuery) { uiState.submittedQuery.trim() }

    // Focus restore bookkeeping for the results grid â€” mirrors ClassicHomeContent pattern so
    // the grid keeps focus when placeholderâ†’real data transitions.
    var lastFocusedGridItemIndex by remember { mutableIntStateOf(-1) }
    val keyboardFirstKeyFocusRequester = remember { FocusRequester() }
    val resultsFirstItemFocusRequester = remember { FocusRequester() }
    var isKeyboardFocusActive by remember { mutableStateOf(false) }
    val keyboardSpaceKeyFocusRequester = remember { FocusRequester() }
    // How the merged grid is ordered: relevance/popularity (default), newest release, or rating.
    var sortMode by rememberSaveable { mutableStateOf(SearchSortMode.POPULARITY) }
    var sortExpanded by remember { mutableStateOf(false) }

    // The left input panel (search bar + virtual keyboard + recents) collapses while focus is
    // on the results grid, giving the posters the full width (4 -> 6 columns transition).
    val focusManager = LocalFocusManager.current
    var inputAreaActive by remember { mutableStateOf(true) }
    var pendingKeyboardFocus by remember { mutableStateOf(false) }
    LaunchedEffect(inputAreaActive, pendingKeyboardFocus) {
        if (inputAreaActive && pendingKeyboardFocus) {
            delay(50)
            val focused = runCatching { keyboardFirstKeyFocusRequester.requestFocus() }.getOrDefault(false)
            if (!focused) {
                focusManager.moveFocus(FocusDirection.Down)
            }
            pendingKeyboardFocus = false
        }
    }

    // Moves focus from the top input row down into the virtual keyboard, falling back to
    // native traversal if the explicit request fails (e.g. key not composed yet). Also used
    // by the search field's click handler, so it first brings the collapsed keyboard panel
    // back (inputAreaActive = true) before requesting focus on its first key.
    val requestKeyboardFocus: () -> Unit = {
        inputAreaActive = true
        coroutineScope.launch {
            repeat(2) { withFrameNanos { } }
            delay(50)
            val focused = runCatching { keyboardFirstKeyFocusRequester.requestFocus() }.getOrDefault(false)
            if (!focused) {
                focusManager.moveFocus(FocusDirection.Down)
            }
        }
    }

    // Recent searches shown under the keyboard, live-filtered by the typed query.
    val panelRecentSearches = remember(uiState.recentSearches, trimmedQuery) {
        if (trimmedQuery.isEmpty()) {
            uiState.recentSearches.take(PANEL_RECENT_SEARCH_LIMIT)
        } else {
            uiState.recentSearches
                .filter { it.contains(trimmedQuery, ignoreCase = true) }
                .take(PANEL_RECENT_SEARCH_LIMIT)
        }
    }

    // Single mixed grid of every result (movies + series): best title match first,
    // popular franchises ahead of deep cuts, sequels after their original in release
    // order â€” then re-ordered by the selected sort mode (release / rating) when chosen.
    // Ranking is O(nÂ²) with several regex passes per comparison: run it off the
    // UI thread or each addon batch freezes the search screen on slow sticks.
    val mergedResults by produceState(
        initialValue = emptyList<SearchGridEntry>(),
        uiState.catalogRows,
        trimmedQuery,
        sortMode
    ) {
        value = withContext(Dispatchers.Default) {
            val entries = uiState.catalogRows
                .flatMap { row -> row.items.map { SearchGridEntry(it, row.addonBaseUrl) } }
                .filter { !it.item.id.startsWith("__placeholder_") }
                .filter { entry ->
                    // Drop hits with no artwork at all: the card has nothing to render.
                    // Poster-only is enough (Anime/TVDB catalogs often have no backdrop).
                    val item = entry.item
                    !item.poster.isNullOrBlank() ||
                        !item.background.isNullOrBlank() ||
                        !item.landscapePoster.isNullOrBlank()
                }
            // Same title from several addons: keep the richest duplicate so ranking
            // sees the copy with poster/rating instead of the first addon's order.
            val entryByKey = LinkedHashMap<String, SearchGridEntry>(entries.size)
            entries.forEach { entry ->
                val key = entry.key()
                val current = entryByKey[key]
                when (current) {
                    null -> entryByKey[key] = entry
                    else -> {
                        val winner = if (entry.isRicherThan(current)) entry else current
                        val loser = if (entry.isRicherThan(current)) current else entry
                        // Poster copy wins the card, but never drop the other copy's
                        // rating: popularity drives the ranking of the merged grid.
                        entryByKey[key] = if (winner.item.imdbRating == null && loser.item.imdbRating != null) {
                            winner.copy(item = winner.item.copy(imdbRating = loser.item.imdbRating))
                        } else {
                            winner
                        }
                    }
                }
            }
            val ordered = orderSearchResults(
                sortMode,
                rankSearchResults(trimmedQuery, entryByKey.values.map { it.item }),
                trimmedQuery
            ).mapNotNull { item -> entryByKey["${item.apiType}:${item.id}"] }
            ordered
        }
    }

    val hasPendingUnsubmittedQuery = remember(trimmedQuery, trimmedSubmittedQuery) {
        trimmedQuery.length >= MIN_SEARCH_QUERY_LENGTH &&
            trimmedQuery != trimmedSubmittedQuery
    }
    val canMoveToResults = remember(trimmedSubmittedQuery, mergedResults) {
        trimmedSubmittedQuery.length >= MIN_SEARCH_QUERY_LENGTH && mergedResults.isNotEmpty()
    }
    val submitCurrentQuery: (String) -> Unit = { submittedQuery ->
        viewModel.onEvent(SearchEvent.SubmitSearch)
        focusResults = false
        if (submittedQuery.length >= MIN_SEARCH_QUERY_LENGTH) {
            pendingFocusMoveToResultsQuery = submittedQuery
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows =
                trimmedSubmittedQuery.length >= MIN_SEARCH_QUERY_LENGTH &&
                    uiState.catalogRows.any { row -> row.items.isNotEmpty() }
        } else {
            pendingFocusMoveToResultsQuery = null
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows = false
        }
    }
    val handleQueryChanged: (String) -> Unit = { nextQuery ->
        val previousQuery = uiState.query.trim()
        val trimmedNextQuery = nextQuery.trim()
        val selectedSuggestion = trimmedNextQuery.length >= MIN_SEARCH_QUERY_LENGTH &&
            trimmedNextQuery != trimmedSubmittedQuery &&
            uiState.suggestions.any { it.equals(trimmedNextQuery, ignoreCase = true) } &&
            trimmedNextQuery.startsWith(previousQuery, ignoreCase = true) &&
            trimmedNextQuery.length - previousQuery.length > 1

        focusResults = false
        pendingFocusMoveToResultsQuery = null
        pendingFocusMoveSawSearching = false
        pendingFocusMoveHadExistingSearchRows = false
        viewModel.onEvent(SearchEvent.QueryChanged(nextQuery))
        if (selectedSuggestion) {
            submitCurrentQuery(trimmedNextQuery)
        }
    }
    val submitRecentSearch: (String) -> Unit = { recentQuery ->
        val trimmedRecentQuery = recentQuery.trim()
        if (trimmedRecentQuery.isNotEmpty()) {
            viewModel.onEvent(SearchEvent.QueryChanged(trimmedRecentQuery))
            submitCurrentQuery(trimmedRecentQuery)
        }
    }

    LaunchedEffect(
        pendingFocusMoveToResultsQuery,
        pendingFocusMoveSawSearching,
        pendingFocusMoveHadExistingSearchRows,
        uiState.isSearching,
        uiState.submittedQuery,
        canMoveToResults
    ) {
        val pendingQuery = pendingFocusMoveToResultsQuery ?: return@LaunchedEffect
        val currentSubmittedQuery = uiState.submittedQuery.trim()
        if (currentSubmittedQuery != pendingQuery) return@LaunchedEffect

        if (uiState.isSearching) {
            pendingFocusMoveSawSearching = true
            return@LaunchedEffect
        }

        val shouldRequireSeenSearching = pendingFocusMoveHadExistingSearchRows
        if ((shouldRequireSeenSearching && !pendingFocusMoveSawSearching) || !canMoveToResults) {
            return@LaunchedEffect
        }

        // Use explicit first-item focus for deterministic landing on row 1 / column 1.
        delay(80)
        focusResults = true
        pendingFocusMoveToResultsQuery = null
        pendingFocusMoveSawSearching = false
        pendingFocusMoveHadExistingSearchRows = false
    }

    LaunchedEffect(topInputFocusRequester) {
        if (viewModel.hasSavedSearchFocus) return@LaunchedEffect
        var attempt = 0
        while (attempt < MAX_INITIAL_FOCUS_ATTEMPTS) {
            repeat(2) { withFrameNanos { } }
            val focused = runCatching { topInputFocusRequester.requestFocus() }.getOrDefault(false)
            if (focused) break
            attempt++
            delay(50)
        }
    }

    LaunchedEffect(Unit) {
        if (viewModel.hasSavedSearchFocus) {
            // The results grid is expected to restore focus itself. If it never shows up
            // (e.g. empty state), fall back to the search field so the D-pad stays usable.
            delay(600)
            if (restoringSearchFocus.value) {
                runCatching { topInputFocusRequester.requestFocus() }
                restoringSearchFocus.value = false
                viewModel.hasSavedSearchFocus = false
            }
        }
    }

    // Push search suggestions to the native keyboard suggestion bar
    LaunchedEffect(uiState.suggestions) {
        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return@LaunchedEffect
        val completions = uiState.suggestions.mapIndexed { index, name ->
            CompletionInfo(index.toLong(), index, name)
        }.toTypedArray()
        imm.displayCompletions(view, completions)
    }

    var isScreenActive by remember { mutableStateOf(true) }
    val latestPendingDiscoverRestore by rememberUpdatedState(pendingDiscoverRestoreOnResume)
    val latestShouldKeepSearchFocus by rememberUpdatedState(
        focusResults || uiState.isSearching || isVoiceListening
    )
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isScreenActive = true
                if (latestPendingDiscoverRestore) {
                    restoreDiscoverFocus = true
                    pendingDiscoverRestoreOnResume = false
                } else if (viewModel.hasSavedSearchFocus || didRestoreSearchFocus.value) {
                    // Returning from details â€” don't steal focus, the results grid already
                    // restored it or will restore it via its focused item index.
                    didRestoreSearchFocus.value = false
                } else if (!latestShouldKeepSearchFocus) {
                    coroutineScope.launch {
                        repeat(2) { withFrameNanos { } }
                        runCatching { searchFocusRequester.requestFocus() }
                    }
                }
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                isScreenActive = false
                keyboardController?.hide()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .recompositionHighlighter()
    ) {
        // Full-width search banner: always visible, spanning the whole page, with the clear
        // (X) button at the far end. Only the keyboard + recents panel below collapses.
        // Con il pannello laterale aperto (LEFT dai contenuti) il vetro coprirebbe
        // il primo pulsante della riga: "Scopri" resta tagliato a metà. La riga
        // scivola a destra finché il pannello è aperto e torna al posto da sola.
        val bannerStartInset by animateDpAsState(
            targetValue = if (LocalSidebarExpanded.current) {
                SIDEBAR_PANEL_CLEARANCE
            } else {
                // Rail icone chiuso: 48dp non bastava, "Scopri" veniva coperto
                // dal nero della barra laterale. 72dp tiene la riga dentro la
                // zona visibile pur restando allineata al resto della pagina.
                SIDEBAR_COLLAPSED_CLEARANCE
            },
            label = "searchBannerStartInset"
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(1f)
                .padding(
                    start = bannerStartInset,
                    end = NuvioTheme.spacing.xxxl,
                    top = NuvioTheme.spacing.lg,
                    bottom = NuvioTheme.spacing.lg
                ),
            contentAlignment = Alignment.Center
        ) {
            SearchInputField(
                query = uiState.query,
                canMoveToResults = canMoveToResults,
                voiceFocusRequester = if (isVoiceSearchAvailable) voiceFocusRequester else null,
                searchFocusRequester = searchFocusRequester,
                onSearchFieldFocusChanged = { focused ->
                    isSearchFieldFocused = focused
                    if (focused) inputAreaActive = true
                },
                onQueryChanged = handleQueryChanged,
                onSubmit = {
                    submitCurrentQuery(uiState.query.trim())
                },
                // Microfono rimosso su richiesta dell'utente: nella riga resta
                // solo il pulsante "Scopri".
                showVoiceSearch = false,
                isVoiceListening = isVoiceListening,
                voiceRmsLevel = voiceRmsLevel,
                onVoiceSearch = launchVoiceSearch,
                onMoveToResults = { focusResults = true },
                onMoveToKeyboard = requestKeyboardFocus,
                onOpenDiscover = onOpenDiscover,
                showDiscoverButton = uiState.discoverLocation == DiscoverLocation.IN_SEARCH,
                sortMode = sortMode,
                sortExpanded = sortExpanded,
                onSortExpandedChange = { sortExpanded = it },
                onSelectSort = { sortMode = it },
                isScreenActive = isScreenActive
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
        // Left: WuPlay-style virtual keyboard panel, always laid out even when it is collapsed
        // so the field keeps a stable home for focus restoration.
        AnimatedVisibility(
            visible = inputAreaActive,
            enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .width(SEARCH_LEFT_PANEL_WIDTH)
                    .fillMaxHeight()
                    .padding(start = NuvioTheme.spacing.xxxl, top = NuvioTheme.spacing.lg)
                    // Bigger keys + up to four recents can exceed the panel height on
                    // 540dp-tall Fire TVs: scroll instead of clipping, focus auto-scrolls.
                    .verticalScroll(rememberScrollState())
            ) {
                SearchVirtualKeyboard(
                    onKey = { key -> handleQueryChanged(uiState.query + key) },
                    onSpace = { handleQueryChanged(uiState.query + " ") },
                    onBackspace = { handleQueryChanged(uiState.query.dropLast(1)) },
                    onEnter = {
                        submitCurrentQuery(uiState.query.trim())
                        focusResults = true
                    },
                    firstKeyFocusRequester = keyboardFirstKeyFocusRequester,
                    // Only bind the right-arrow target while the grid is actually composed:
                    // an unattached FocusRequester fails the move silently (or worse).
                    resultsFocusRequester = if (mergedResults.isNotEmpty()) {
                        resultsFirstItemFocusRequester
                    } else {
                        null
                    },
                    spaceKeyFocusRequester = keyboardSpaceKeyFocusRequester,
                    onFocusChanged = { focused ->
                        isKeyboardFocusActive = focused
                        if (focused) inputAreaActive = true
                    },
                    onMoveToRecents = if (panelRecentSearches.isNotEmpty()) {
                        { runCatching { recentFirstItemFocusRequester.requestFocus() } }
                    } else {
                        null
                    },
                    onMoveToResults = if (mergedResults.isNotEmpty()) {
                        { focusResults = true }
                    } else {
                        null
                    }
                )

                if (panelRecentSearches.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                    RecentSearchesPanel(
                        recentSearches = panelRecentSearches,
                        onSearchSelected = submitRecentSearch,
                        onSectionFocusChanged = { focused ->
                            isRecentSearchSectionFocused = focused
                            if (focused) inputAreaActive = true
                        },
                        firstItemFocusRequester = recentFirstItemFocusRequester,
                        resultsFocusRequester = if (mergedResults.isNotEmpty()) {
                            resultsFirstItemFocusRequester
                        } else {
                            null
                        },
                        onSubmit = {
                            submitCurrentQuery(uiState.query.trim())
                            focusResults = true
                        }
                    )
                }
            }
        }

        // Right: results area. The left panel slides away while the grid holds focus, so the
        // posters expand from ~4 to ~6 per row without changing their size.
        val savedResultsScroll = remember(viewModel.hasSavedSearchFocus) {
            if (viewModel.hasSavedSearchFocus) viewModel.savedResultsScrollPosition else null
        }
        val resultsGridState = rememberLazyGridState(
            initialFirstVisibleItemIndex = savedResultsScroll?.first ?: 0,
            initialFirstVisibleItemScrollOffset = savedResultsScroll?.second ?: 0
        )
        // Skeleton only while there is nothing real to show. Once any hit arrives the grid
        // takes over even if slower catalogs are still loading (footer covers the rest).
        val showGhostSkeleton = (hasPendingUnsubmittedQuery || uiState.isSearching) &&
            mergedResults.isEmpty()

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds()
                .recompositionHighlighter()
        ) {
            when {
                showGhostSkeleton -> {
                    SearchResultsSkeletonGrid(
                        posterCardStyle = posterCardStyle,
                        modifier = Modifier
                    )
                }

                trimmedQuery.length < MIN_SEARCH_QUERY_LENGTH && !uiState.isSearching -> {
                    EmptyScreenState(
                        title = stringResource(R.string.search_start_title),
                        subtitle = stringResource(R.string.search_start_subtitle),
                        icon = Icons.Default.Search
                    )
                }

                mergedResults.isNotEmpty() -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        SingleSearchResultsGrid(
                            entries = mergedResults,
                            gridState = resultsGridState,
                            posterCardStyle = posterCardStyle,
                            showPosterLabels = uiState.posterLabelsEnabled,
                            isItemWatched = { item ->
                                val isSeries = item.apiType.equals("series", ignoreCase = true) ||
                                    item.apiType.equals("tv", ignoreCase = true)
                                if (isSeries) item.id in watchedSeriesIds else item.id in watchedMovieIds
                            },
                            entryFocusRequester = resultsFirstItemFocusRequester,
                            restorerFocusedIndex = if (restoringSearchFocus.value) viewModel.savedFocusItemIndex else -1,
                            focusedItemIndex = when {
                                restoringSearchFocus.value -> viewModel.savedFocusItemIndex
                                focusResults -> 0
                                else -> -1
                            },
                            onItemFocused = { itemIndex ->
                                if (focusResults) {
                                    focusResults = false
                                }
                                if (restoringSearchFocus.value) {
                                    restoringSearchFocus.value = false
                                    didRestoreSearchFocus.value = true
                                    viewModel.hasSavedSearchFocus = false
                                }
                                // User manually navigated to a result â€” cancel any
                                // pending auto-focus so it doesn't steal focus later.
                                pendingFocusMoveToResultsQuery = null
                                lastFocusedGridItemIndex = itemIndex
                                // The keyboard slides away while the results hold focus.
                                inputAreaActive = false
                            },
                            onItemClick = { id, type, addonBaseUrl ->
                                // Save focus state to ViewModel before navigating
                                viewModel.savedFocusItemIndex = lastFocusedGridItemIndex.coerceAtLeast(0)
                                viewModel.savedResultsScrollPosition =
                                    resultsGridState.firstVisibleItemIndex to resultsGridState.firstVisibleItemScrollOffset
                                viewModel.hasSavedSearchFocus = true
                                val clickedItem = uiState.catalogRows
                                    .flatMap { it.items }
                                    .firstOrNull { it.id == id }
                                HeroBackdropState.update(clickedItem?.backdropUrl)
                                onNavigateToDetail(id, type, addonBaseUrl)
                            },
                            onItemLongPress = { item, addonBaseUrl ->
                                viewModel.posterOptions.show(item, addonBaseUrl)
                            },
                            onFirstColumnLeftPress = {
                                inputAreaActive = true
                                pendingKeyboardFocus = true
                            },
                            showLoadingFooter = uiState.isSearching || hasPendingUnsubmittedQuery,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                (hasPendingUnsubmittedQuery || uiState.isSearching) -> {
                    SearchResultsSkeletonGrid(
                        posterCardStyle = posterCardStyle,
                        modifier = Modifier
                    )
                }

                uiState.error != null -> {
                    ErrorState(
                        message = uiState.error ?: stringResource(R.string.search_error_failed),
                        onRetry = { viewModel.onEvent(SearchEvent.Retry) }
                    )
                }

                else -> {
                    EmptyScreenState(
                        title = stringResource(R.string.search_no_results_title),
                        subtitle = stringResource(R.string.search_no_results_subtitle),
                        icon = Icons.Default.Search
                    )
                }
            }
        }
        }
    }

    val posterOptionsState by viewModel.posterOptions.state.collectAsState()
    com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost(
        state = posterOptionsState,
        controller = viewModel.posterOptions,
        onNavigateToDetail = { id, type, addonBaseUrl ->
            val clickedItem = uiState.catalogRows
                .flatMap { it.items }
                .firstOrNull { it.id == id }
                ?: uiState.discoverResults.firstOrNull { it.id == id }
            HeroBackdropState.update(clickedItem?.backdropUrl)
            onNavigateToDetail(id, type, addonBaseUrl)
        }
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchInputField(
    query: String,
    canMoveToResults: Boolean,
    voiceFocusRequester: FocusRequester?,
    searchFocusRequester: FocusRequester,
    onSearchFieldFocusChanged: (Boolean) -> Unit,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    showVoiceSearch: Boolean,
    isVoiceListening: Boolean,
    voiceRmsLevel: Float,
    onVoiceSearch: () -> Unit,
    onMoveToResults: () -> Unit,
    onMoveToKeyboard: (() -> Unit)?,
    onOpenDiscover: () -> Unit,
    showDiscoverButton: Boolean,
    sortMode: SearchSortMode,
    sortExpanded: Boolean,
    onSortExpandedChange: (Boolean) -> Unit,
    onSelectSort: (SearchSortMode) -> Unit,
    isScreenActive: Boolean = true
) {
    var isDiscoverButtonFocused by remember { mutableStateOf(false) }
    var isVoiceButtonFocused by remember { mutableStateOf(false) }
    var isClearButtonFocused by remember { mutableStateOf(false) }
    var isSearchFieldFocused by remember { mutableStateOf(false) }
    val fieldShape = RoundedCornerShape(NuvioTheme.radii.md)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showDiscoverButton) {
            IconButton(
                onClick = onOpenDiscover,
                modifier = Modifier
                    .onFocusChanged { isDiscoverButtonFocused = it.isFocused }
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                            keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                            onMoveToKeyboard != null
                        ) {
                            onMoveToKeyboard()
                            true
                        } else {
                            false
                        }
                    }
                    .size(NuvioTheme.spacing.xxl)
                    .border(
                        width = if (isDiscoverButtonFocused) NuvioTheme.spacing.xxs else NuvioTheme.spacing.hairline,
                        color = if (isDiscoverButtonFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border,
                        shape = fieldShape
                    )
                    .background(
                        color = NuvioTheme.colors.BackgroundCard,
                        shape = fieldShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Explore,
                    contentDescription = stringResource(R.string.cd_open_discover),
                    tint = NuvioTheme.colors.TextPrimary
                )
            }
        }

        if (showVoiceSearch) {
            IconButton(
                onClick = onVoiceSearch,
                modifier = Modifier
                    .then(
                        if (voiceFocusRequester != null) {
                            Modifier.focusRequester(voiceFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                    .onFocusChanged { isVoiceButtonFocused = it.isFocused }
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                            keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                            onMoveToKeyboard != null
                        ) {
                            onMoveToKeyboard()
                            true
                        } else {
                            false
                        }
                    }
                    // Stacco netto dal pulsante "Scopri" a sinistra: con lo
                    // spacing della Row (md) i due riquadri quasi si toccavano.
                    .padding(start = NuvioTheme.spacing.xl)
                    .size(NuvioTheme.spacing.xxl)
                    .border(
                        width = if (isVoiceButtonFocused || isVoiceListening) NuvioTheme.spacing.xxs else NuvioTheme.spacing.hairline,
                        color = if (isVoiceListening) NuvioTheme.colors.Secondary else if (isVoiceButtonFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border,
                        shape = fieldShape
                    )
                    .background(
                        color = if (isVoiceListening) NuvioTheme.colors.Secondary.copy(alpha = 0.15f) else NuvioTheme.colors.BackgroundCard,
                        shape = fieldShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = stringResource(R.string.cd_voice_search),
                    tint = if (isVoiceListening) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextPrimary
                )
            }
        }

        // Display-only field: the virtual keyboard is the only input method, so no IME is
        // ever shown (and the FireStick's native keyboard never pops up).
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .focusRequester(searchFocusRequester)
                .focusProperties {
                    canFocus = isScreenActive
                }
                .onFocusChanged { focusState ->
                    isSearchFieldFocused = focusState.isFocused
                    onSearchFieldFocusChanged(focusState.isFocused)
                }
                // Pointer clicks (mouse / air-mouse / touch) on the field open the in-app
                // virtual keyboard, instead of being ignored like a non-interactive Box.
                .fillMaxSize()
                .clickable { onMoveToKeyboard?.invoke() }
                .pointerInput(onMoveToKeyboard) {
                    detectTapGestures { onMoveToKeyboard?.invoke() }
                }
                .onPreviewKeyEvent { keyEvent ->
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER,
                        KeyEvent.KEYCODE_DPAD_CENTER -> {
                            if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                onSubmit()
                            }
                            return@onPreviewKeyEvent true
                        }

                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (onMoveToKeyboard != null) {
                                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                    onMoveToKeyboard()
                                }
                                return@onPreviewKeyEvent true
                            }
                            if (canMoveToResults) {
                                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                    onMoveToResults()
                                }
                                return@onPreviewKeyEvent true
                            }
                        }

                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (canMoveToResults &&
                                keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                            ) {
                                onMoveToResults()
                                return@onPreviewKeyEvent true
                            }
                        }
                    }
                    false
                }
                .background(
                    color = NuvioTheme.colors.BackgroundCard,
                    shape = fieldShape
                )
                .border(
                    width = if (isSearchFieldFocused) NuvioTheme.spacing.xxs else NuvioTheme.spacing.hairline,
                    color = if (isSearchFieldFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border,
                    shape = fieldShape
                )
        ) {
            Text(
                text = if (query.isEmpty()) {
                    stringResource(R.string.search_placeholder)
                } else {
                    query
                },
                style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                color = if (query.isEmpty()) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        if (query.isNotEmpty()) {
            IconButton(
                onClick = { onQueryChanged("") },
                modifier = Modifier
                    .onFocusChanged { isClearButtonFocused = it.isFocused }
                    .size(NuvioTheme.spacing.xxl)
                    .border(
                        width = if (isClearButtonFocused) NuvioTheme.spacing.xxs else NuvioTheme.spacing.hairline,
                        color = if (isClearButtonFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border,
                        shape = fieldShape
                    )
                    .background(
                        color = NuvioTheme.colors.BackgroundCard,
                        shape = fieldShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.cd_clear_search),
                    tint = NuvioTheme.colors.TextPrimary
                )
            }
        }

        // Sort menu: same dropdown the Library uses, parked at the end of the bar so it
        // never sits on top of the results grid (the old chip row did).
        DropdownPicker(
            modifier = Modifier.width(180.dp),
            title = stringResource(R.string.search_sort_title),
            value = stringResource(sortMode.labelRes),
            selectedValue = sortMode.name,
            expanded = sortExpanded,
            options = SearchSortMode.entries.map {
                DropdownOption(stringResource(it.labelRes), it.name)
            },
            onExpandedChange = onSortExpandedChange,
            onSelect = { option ->
                SearchSortMode.entries.firstOrNull { it.name == option.value }
                    ?.let(onSelectSort)
                onSortExpandedChange(false)
            }
        )
    }
}

private val SearchSortMode.labelRes: Int
    get() = when (this) {
        SearchSortMode.POPULARITY -> R.string.search_sort_popularity
        SearchSortMode.RELEASE -> R.string.search_sort_release
        SearchSortMode.RATING -> R.string.search_sort_rating
    }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RecentSearchesPanel(
    recentSearches: List<String>,
    onSearchSelected: (String) -> Unit,
    onSectionFocusChanged: (Boolean) -> Unit,
    firstItemFocusRequester: FocusRequester,
    resultsFocusRequester: FocusRequester? = null,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusGroup()
            .onFocusChanged { state ->
                onSectionFocusChanged(state.hasFocus || state.isFocused)
            },
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        Text(
            text = stringResource(R.string.search_recent_title),
            style = androidx.tv.material3.MaterialTheme.typography.titleSmall,
            color = NuvioTheme.colors.TextPrimary
        )

        recentSearches.forEachIndexed { index, recentQuery ->
            val isLast = index == recentSearches.lastIndex
            Button(
                onClick = { onSearchSelected(recentQuery) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .then(
                        if (index == 0) {
                            Modifier.focusRequester(firstItemFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                    .then(
                        // Right from a recent search jumps straight into the results grid,
                        // same as the virtual keyboard's last column.
                        if (resultsFocusRequester != null) {
                            Modifier.focusProperties { right = resultsFocusRequester }
                        } else {
                            Modifier
                        }
                    )
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && isLast) {
                            if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                onSubmit()
                            }
                            true
                        } else {
                            false
                        }
                    },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
            ) {
                Text(
                    text = recentQuery,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(
    ExperimentalTvMaterial3Api::class,
    ExperimentalComposeUiApi::class
)
@Composable
private fun SingleSearchResultsGrid(
    entries: List<SearchGridEntry>,
    gridState: LazyGridState,
    onItemClick: (String, String, String) -> Unit,
    modifier: Modifier = Modifier,
    posterCardStyle: PosterCardStyle = PosterCardDefaults.Style,
    showPosterLabels: Boolean = true,
    isItemWatched: (MetaPreview) -> Boolean = { false },
    onItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    focusedItemIndex: Int = -1,
    restorerFocusedIndex: Int = -1,
    onItemFocused: (itemIndex: Int) -> Unit = {},
    entryFocusRequester: FocusRequester? = null,
    onFirstColumnLeftPress: () -> Unit = {},
    showLoadingFooter: Boolean = false,
    interactive: Boolean = true
) {
    val itemFocusRequestersByKey = remember { mutableMapOf<String, FocusRequester>() }
    var lastRequestedFocusItemKey by remember { mutableStateOf<String?>(null) }
    var lastFocusedItemIndex by remember { mutableIntStateOf(-1) }

    val latestOnItemClick by rememberUpdatedState(onItemClick)
    val latestOnItemLongPress by rememberUpdatedState(onItemLongPress)
    val latestIsItemWatched by rememberUpdatedState(isItemWatched)
    val latestOnItemFocused by rememberUpdatedState(onItemFocused)
    val latestOnFirstColumnLeftPress by rememberUpdatedState(onFirstColumnLeftPress)

    LaunchedEffect(entries) {
        val validKeys = entries.mapTo(mutableSetOf()) { it.key() }
        itemFocusRequestersByKey.keys.retainAll(validKeys)
        if (lastRequestedFocusItemKey !in validKeys) {
            lastRequestedFocusItemKey = null
        }
        // A shorter result set (new query, dropped duplicates) can leave the last
        // focused index pointing past the end of the list: entry target and
        // focus restorer would then aim at a card that no longer exists.
        if (lastFocusedItemIndex >= entries.size) {
            lastFocusedItemIndex = -1
        }
    }

    // Restore focus from saved state when focusedItemIndex is set.
    LaunchedEffect(focusedItemIndex, entries) {
        if (!interactive) return@LaunchedEffect
        if (focusedItemIndex >= 0 && focusedItemIndex < entries.size) {
            val targetKey = entries[focusedItemIndex].key()
            if (lastRequestedFocusItemKey == targetKey) return@LaunchedEffect
            val requester = itemFocusRequestersByKey.getOrPut(targetKey) { FocusRequester() }
            repeat(2) { withFrameNanos { } }
            var focused = runCatching { requester.requestFocus() }.isSuccess
            if (!focused) {
                // The grid can be restored further down the list, leaving the
                // target card uncomposed: scroll it into view and retry instead
                // of leaving focus stuck on the keyboard.
                runCatching { gridState.scrollToItem(focusedItemIndex) }
                repeat(2) { withFrameNanos { } }
                focused = runCatching { requester.requestFocus() }.isSuccess
            }
            if (focused) {
                lastRequestedFocusItemKey = targetKey
            }
        } else {
            lastRequestedFocusItemKey = null
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl)
    ) {
        val horizontalSpacing = NuvioTheme.spacing.md
        val verticalSpacing = NuvioTheme.spacing.md
        val columns = run {
            val cols = (maxWidth + horizontalSpacing) /
                (posterCardStyle.width + horizontalSpacing)
            cols.toInt().coerceAtLeast(1)
        }
        // The keyboard's right-arrow FocusRequester lives on exactly one card. If
        // that card is off screen (restored scroll position, shorter result set)
        // the requester is never attached and the move fails silently, so aim it
        // at a card that is on screen right now.
        val visibleIndices = gridState.layoutInfo.visibleItemsInfo.map { it.index }
        val entryTargetIndex = (if (lastFocusedItemIndex >= 0 && lastFocusedItemIndex in visibleIndices) {
            lastFocusedItemIndex
        } else {
            visibleIndices.firstOrNull() ?: 0
        }).coerceIn(0, (entries.size - 1).coerceAtLeast(0))

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .focusGroup()
                .dpadRepeatThrottle()
                .focusRestorer {
                    if (!interactive) {
                        FocusRequester.Default
                    } else {
                        val idx = if (lastFocusedItemIndex >= 0) lastFocusedItemIndex else restorerFocusedIndex
                        val validIdx = idx.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
                        entries.getOrNull(validIdx)
                            ?.let { itemFocusRequestersByKey.getOrPut(it.key()) { FocusRequester() } }
                            ?: FocusRequester.Default
                    }
                },
            contentPadding = PaddingValues(top = NuvioTheme.spacing.xxl, bottom = NuvioTheme.spacing.xxl),
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing)
        ) {
            gridItemsIndexed(
                items = entries,
                key = { index, entry -> entry.key() },
                contentType = { _, entry -> entry.item.apiType }
            ) { index, entry ->
                val isFirstColumn = index % columns == 0
                val isEntryTarget = interactive && entryFocusRequester != null &&
                    index == entryTargetIndex
                val cardFocusRequester = if (interactive) {
                    remember(entry.key()) {
                        itemFocusRequestersByKey.getOrPut(entry.key()) { FocusRequester() }
                    }
                } else {
                    null
                }

                GridContentCard(
                    item = entry.item,
                    onClick = {
                        if (interactive) latestOnItemClick(entry.item.id, entry.item.apiType, entry.addonBaseUrl)
                    },
                    posterCardStyle = posterCardStyle,
                    showLabel = showPosterLabels,
                    isWatched = latestIsItemWatched(entry.item),
                    focusRequester = cardFocusRequester,
                    // The keyboard/recents right-arrow target must live on the focusable
                    // Card: attaching it to the outer Column never receives focus.
                    extraFocusRequester = if (isEntryTarget) entryFocusRequester else null,
                    // Posters streaming in during live search: no fade-in animation, the
                    // per-frame invalidations jank the Fire TV while the grid fills up.
                    imageCrossfade = false,
                    onLongPress = {
                        if (interactive) latestOnItemLongPress(entry.item, entry.addonBaseUrl)
                    },
                    onFocused = {
                        if (interactive) {
                            if (lastFocusedItemIndex != index) {
                                lastFocusedItemIndex = index
                                latestOnItemFocused(index)
                            }
                        }
                    },
                    modifier = Modifier.then(
                        if (isFirstColumn && interactive) {
                            Modifier.onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
                                    keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                                ) {
                                    latestOnFirstColumnLeftPress()
                                    true
                                } else {
                                    false
                                }
                            }
                        } else {
                            Modifier
                        }
                    )
                )
            }

            if (showLoadingFooter) {
                item(key = "search_loading_more") {
                    // One shared brush: per-item shimmer brushes create an
                    // infinite transition each, invalidating every frame.
                    val shimmerBrush = rememberShimmerBrush()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = NuvioTheme.spacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing)
                    ) {
                        repeat(4) {
                            GhostPosterCard(
                                posterCardStyle = posterCardStyle,
                                shimmerBrush = shimmerBrush
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Non-focusable ghost poster shown while more search results are on the way. */
@Composable
private fun GhostPosterCard(posterCardStyle: PosterCardStyle, shimmerBrush: Brush) {
    Box(
        modifier = Modifier
            .width(posterCardStyle.width)
            .height(posterCardStyle.height)
            .background(shimmerBrush, shape = RoundedCornerShape(posterCardStyle.cornerRadius))
    )
}

/** Loading skeleton for the search results area: a couple of rows of shimmer ghost posters. */
@Composable
private fun SearchResultsSkeletonGrid(
    posterCardStyle: PosterCardStyle,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl)
    ) {
        val horizontalSpacing = NuvioTheme.spacing.md
        val columns = run {
            val cols = (maxWidth + horizontalSpacing) /
                (posterCardStyle.width + horizontalSpacing)
            cols.toInt().coerceAtLeast(1)
        }

        // One shared brush for the whole skeleton: a brush per item spins up an
        // infinite transition each, so every ghost card invalidated every frame.
        val shimmerBrush = rememberShimmerBrush()

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = NuvioTheme.spacing.xxl, bottom = NuvioTheme.spacing.xxl),
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            items(columns * 2) {
                GhostPosterCard(
                    posterCardStyle = posterCardStyle,
                    shimmerBrush = shimmerBrush
                )
            }
        }
    }
}

