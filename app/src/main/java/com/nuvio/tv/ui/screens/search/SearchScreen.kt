package com.nuvio.tv.ui.screens.search

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (catalogId: String, addonId: String, type: String) -> Unit = { _, _, _ -> },
    onOpenDiscover: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val voiceFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val discoverFirstItemFocusRequester = remember { FocusRequester() }
    var isSearchFieldAttached by remember { mutableStateOf(false) }
    var focusResults by remember { mutableStateOf(false) }
    var pendingFocusMoveToResultsQuery by remember { mutableStateOf<String?>(null) }
    var pendingFocusMoveSawSearching by remember { mutableStateOf(false) }
    var pendingFocusMoveHadExistingSearchRows by remember { mutableStateOf(false) }
    var pendingVoiceSearchResume by remember { mutableStateOf(false) }
    var discoverFocusedItemIndex by rememberSaveable { mutableStateOf(0) }
    var restoreDiscoverFocus by rememberSaveable { mutableStateOf(false) }
    var pendingDiscoverRestoreOnResume by rememberSaveable { mutableStateOf(false) }
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
                uiState.submittedQuery.trim().length >= 2 && uiState.catalogRows.any { it.items.isNotEmpty() }
        } else {
            Toast.makeText(context, "No speech detected. Try again.", Toast.LENGTH_SHORT).show()
        }
    }
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        pendingVoiceSearchResume = false
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val recognized = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
            .trim()
        onVoiceQueryResultState.value(recognized)
    }
    val voiceIntentAction = remember(context) {
        listOf(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH,
            RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE
        ).firstOrNull { action ->
            Intent(action).resolveActivity(context.packageManager) != null
        }
    }
    val isVoiceSearchAvailable = voiceIntentAction != null
    val topInputFocusRequester = remember(isVoiceSearchAvailable) {
        if (isVoiceSearchAvailable) voiceFocusRequester else searchFocusRequester
    }
    val launchVoiceSearch: () -> Unit = {
        val action = voiceIntentAction
        if (action == null) {
            Toast.makeText(context, "Voice search is unavailable on this device.", Toast.LENGTH_SHORT).show()
        } else {
            val intent = Intent(action).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to search")
            }
            pendingVoiceSearchResume = true
            runCatching { voiceLauncher.launch(intent) }.onFailure {
                pendingVoiceSearchResume = false
                Toast.makeText(context, "Voice search is unavailable on this device.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val posterCardStyle = remember(uiState.posterCardWidthDp, uiState.posterCardCornerRadiusDp) {
        val computedHeightDp = (uiState.posterCardWidthDp * 1.5f).roundToInt()
        PosterCardStyle(
            width = uiState.posterCardWidthDp.dp,
            height = computedHeightDp.dp,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    val trimmedQuery = remember(uiState.query) { uiState.query.trim() }
    val trimmedSubmittedQuery = remember(uiState.submittedQuery) { uiState.submittedQuery.trim() }
    val isDiscoverMode = remember(uiState.discoverEnabled, trimmedSubmittedQuery) {
        uiState.discoverEnabled && trimmedSubmittedQuery.isEmpty()
    }
    val hasPendingUnsubmittedQuery = remember(isDiscoverMode, trimmedQuery, trimmedSubmittedQuery) {
        !isDiscoverMode && trimmedQuery.length >= 2 && trimmedQuery != trimmedSubmittedQuery
    }
    val canMoveToResults = remember(
        isDiscoverMode,
        uiState.discoverResults,
        trimmedSubmittedQuery,
        uiState.catalogRows
    ) {
        if (isDiscoverMode) false else trimmedSubmittedQuery.length >= 2 && uiState.catalogRows.any { it.items.isNotEmpty() }
    }

    LaunchedEffect(focusResults, isDiscoverMode, uiState.discoverResults.size) {
        if (focusResults && isDiscoverMode && uiState.discoverResults.isNotEmpty()) {
            delay(100)
            runCatching { discoverFirstItemFocusRequester.requestFocus() }
            focusResults = false
            pendingFocusMoveToResultsQuery = null
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows = false
        }
    }

    LaunchedEffect(
        pendingFocusMoveToResultsQuery,
        pendingFocusMoveSawSearching,
        pendingFocusMoveHadExistingSearchRows,
        uiState.isSearching,
        uiState.submittedQuery,
        canMoveToResults,
        isDiscoverMode
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

        if (isDiscoverMode) {
            focusResults = true
        } else {
            // Use explicit first-item focus for deterministic landing on row 1 / column 1.
            delay(80)
            focusResults = true
        }
        pendingFocusMoveToResultsQuery = null
        pendingFocusMoveSawSearching = false
        pendingFocusMoveHadExistingSearchRows = false
    }

    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { topInputFocusRequester.requestFocus() }
    }

    val latestPendingDiscoverRestore by rememberUpdatedState(pendingDiscoverRestoreOnResume)
    val latestShouldKeepSearchFocus by rememberUpdatedState(
        focusResults || uiState.isSearching || pendingVoiceSearchResume
    )
    val latestVoiceSearchAvailable by rememberUpdatedState(isVoiceSearchAvailable)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (latestPendingDiscoverRestore) {
                    restoreDiscoverFocus = true
                    pendingDiscoverRestoreOnResume = false
                } else if (!latestShouldKeepSearchFocus) {
                    coroutineScope.launch {
                        repeat(2) { withFrameNanos { } }
                        runCatching {
                            if (latestVoiceSearchAvailable) {
                                voiceFocusRequester.requestFocus()
                            } else {
                                searchFocusRequester.requestFocus()
                            }
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background),
        contentAlignment = Alignment.TopCenter
    ) {
        if (isDiscoverMode) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 10.dp)
            ) {
                SearchInputField(
                    query = uiState.query,
                    canMoveToResults = canMoveToResults,
                    voiceFocusRequester = if (isVoiceSearchAvailable) voiceFocusRequester else null,
                    searchFocusRequester = searchFocusRequester,
                    onAttached = { isSearchFieldAttached = true },
                    onQueryChanged = {
                        focusResults = false
                        pendingFocusMoveToResultsQuery = null
                        pendingFocusMoveSawSearching = false
                        pendingFocusMoveHadExistingSearchRows = false
                        viewModel.onEvent(SearchEvent.QueryChanged(it))
                    },
                    onSubmit = {
                        val submittedQuery = uiState.query.trim()
                        viewModel.onEvent(SearchEvent.SubmitSearch)
                        focusResults = false
                        if (submittedQuery.length >= 2) {
                            pendingFocusMoveToResultsQuery = submittedQuery
                            pendingFocusMoveSawSearching = false
                            pendingFocusMoveHadExistingSearchRows =
                                trimmedSubmittedQuery.length >= 2 && uiState.catalogRows.any { row -> row.items.isNotEmpty() }
                        } else {
                            pendingFocusMoveToResultsQuery = null
                            pendingFocusMoveSawSearching = false
                            pendingFocusMoveHadExistingSearchRows = false
                        }
                    },
                    showVoiceSearch = isVoiceSearchAvailable,
                    onVoiceSearch = launchVoiceSearch,
                    onMoveToResults = { focusResults = true },
                    onOpenDiscover = onOpenDiscover,
                    keyboardController = keyboardController
                )

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyScreenState(
                        title = stringResource(R.string.search_start_title),
                        subtitle = stringResource(R.string.search_start_subtitle),
                        icon = Icons.Default.Search
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    SearchInputField(
                        query = uiState.query,
                        canMoveToResults = canMoveToResults,
                        voiceFocusRequester = if (isVoiceSearchAvailable) voiceFocusRequester else null,
                        searchFocusRequester = searchFocusRequester,
                        onAttached = { isSearchFieldAttached = true },
                        onQueryChanged = {
                            focusResults = false
                            pendingFocusMoveToResultsQuery = null
                            pendingFocusMoveSawSearching = false
                            pendingFocusMoveHadExistingSearchRows = false
                            viewModel.onEvent(SearchEvent.QueryChanged(it))
                        },
                        onSubmit = {
                            val submittedQuery = uiState.query.trim()
                            viewModel.onEvent(SearchEvent.SubmitSearch)
                            focusResults = false
                            if (submittedQuery.length >= 2) {
                                pendingFocusMoveToResultsQuery = submittedQuery
                                pendingFocusMoveSawSearching = false
                                pendingFocusMoveHadExistingSearchRows =
                                    trimmedSubmittedQuery.length >= 2 && uiState.catalogRows.any { row -> row.items.isNotEmpty() }
                            } else {
                                pendingFocusMoveToResultsQuery = null
                                pendingFocusMoveSawSearching = false
                                pendingFocusMoveHadExistingSearchRows = false
                            }
                        },
                        showVoiceSearch = isVoiceSearchAvailable,
                        onVoiceSearch = launchVoiceSearch,
                        onMoveToResults = {
                            focusResults = true
                        },
                        onOpenDiscover = onOpenDiscover,
                        keyboardController = keyboardController
                    )
                }

                if (trimmedSubmittedQuery.length < 2 || hasPendingUnsubmittedQuery) {
                    item {
                        Text(
                            text = stringResource(R.string.search_keyboard_hint),
                            style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                            color = NuvioColors.TextSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 52.dp)
                        )
                    }
                }

                when {
                    trimmedSubmittedQuery.length < 2 && !hasPendingUnsubmittedQuery -> {
                        item {
                            EmptyScreenState(
                                title = stringResource(R.string.search_start_title),
                                subtitle = if (uiState.discoverEnabled) {
                                    stringResource(R.string.search_start_subtitle)
                                } else {
                                    stringResource(R.string.search_start_subtitle_no_discover)
                                },
                                icon = Icons.Default.Search
                            )
                        }
                    }

                    uiState.isSearching && uiState.catalogRows.isEmpty() -> {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 80.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LoadingIndicator()
                            }
                        }
                    }

                    uiState.error != null && uiState.catalogRows.isEmpty() -> {
                        item {
                            ErrorState(
                                message = uiState.error ?: "Search failed",
                                onRetry = { viewModel.onEvent(SearchEvent.Retry) }
                            )
                        }
                    }

                    uiState.catalogRows.isEmpty() || uiState.catalogRows.none { it.items.isNotEmpty() } -> {
                        item {
                            EmptyScreenState(
                                title = "No Results",
                                subtitle = "Try searching with different keywords",
                                icon = Icons.Default.Search
                            )
                        }
                    }

                    else -> {
                        val visibleCatalogRows = uiState.catalogRows.filter { it.items.isNotEmpty() }

                        itemsIndexed(
                            items = visibleCatalogRows,
                            key = { index, item ->
                                "${item.addonId}_${item.type}_${item.catalogId}_${trimmedSubmittedQuery}_$index"
                            }
                        ) { index, catalogRow ->
                            CatalogRowSection(
                                catalogRow = catalogRow,
                                showPosterLabels = uiState.posterLabelsEnabled,
                                showAddonName = uiState.catalogAddonNameEnabled,
                                showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled,
                                enableRowFocusRestorer = false,
                                focusedItemIndex = if (focusResults && index == 0) 0 else -1,
                                onItemFocused = {
                                    if (focusResults) {
                                        focusResults = false
                                    }
                                },
                                onItemClick = { id, type, addonBaseUrl ->
                                    onNavigateToDetail(id, type, addonBaseUrl)
                                },
                                onSeeAll = {
                                    onNavigateToSeeAll(
                                        catalogRow.catalogId,
                                        catalogRow.addonId,
                                        catalogRow.apiType
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchInputField(
    query: String,
    canMoveToResults: Boolean,
    voiceFocusRequester: FocusRequester?,
    searchFocusRequester: FocusRequester,
    onAttached: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    showVoiceSearch: Boolean,
    onVoiceSearch: () -> Unit,
    onMoveToResults: () -> Unit,
    onOpenDiscover: () -> Unit,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?
) {
    var isDiscoverButtonFocused by remember { mutableStateOf(false) }
    var isVoiceButtonFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp)
            .onGloballyPositioned { onAttached() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onOpenDiscover,
            modifier = Modifier
                .onFocusChanged { isDiscoverButtonFocused = it.isFocused }
                .size(56.dp)
                .searchInputButtonChrome(
                    isFocused = isDiscoverButtonFocused,
                    fillColor = NuvioColors.BackgroundCard,
                    focusedBorderColor = NuvioColors.FocusRing,
                    unfocusedBorderColor = NuvioColors.Border
                )
        ) {
            Icon(
                imageVector = Icons.Default.Explore,
                contentDescription = "Open discover",
                tint = NuvioColors.TextPrimary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

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
                    .size(56.dp)
                    .searchInputButtonChrome(
                        isFocused = isVoiceButtonFocused,
                        fillColor = NuvioColors.BackgroundCard,
                        focusedBorderColor = NuvioColors.FocusRing,
                        unfocusedBorderColor = NuvioColors.Border
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Voice search",
                    tint = NuvioColors.TextPrimary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier
                .weight(1f)
                .focusRequester(searchFocusRequester)
                .onPreviewKeyEvent { keyEvent ->
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                onSubmit()
                            }
                            return@onPreviewKeyEvent true
                        }

                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (canMoveToResults) {
                                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                    onMoveToResults()
                                }
                                return@onPreviewKeyEvent true
                            }
                        }
                    }
                    false
                },
            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    onSubmit()
                    keyboardController?.hide()
                }
            ),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            placeholder = {
                Text(
                    text = stringResource(R.string.search_placeholder),
                    color = NuvioColors.TextTertiary
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = NuvioColors.BackgroundCard,
                unfocusedContainerColor = NuvioColors.BackgroundCard,
                focusedIndicatorColor = NuvioColors.FocusRing,
                unfocusedIndicatorColor = NuvioColors.Border,
                focusedTextColor = NuvioColors.TextPrimary,
                unfocusedTextColor = NuvioColors.TextPrimary,
                cursorColor = NuvioColors.FocusRing
            )
        )
    }
}

private fun Modifier.searchInputButtonChrome(
    isFocused: Boolean,
    fillColor: Color,
    focusedBorderColor: Color,
    unfocusedBorderColor: Color,
    cornerRadius: Dp = 12.dp
): Modifier = this.drawWithCache {
    val radius = cornerRadius.toPx()
    val borderWidthPx = if (isFocused) 2.dp.toPx() else 1.dp.toPx()
    val borderColor = if (isFocused) focusedBorderColor else unfocusedBorderColor
    val stroke = Stroke(width = borderWidthPx)
    onDrawBehind {
        drawRoundRect(
            color = fillColor,
            cornerRadius = CornerRadius(radius, radius)
        )
        drawRoundRect(
            color = borderColor,
            cornerRadius = CornerRadius(radius, radius),
            style = stroke
        )
    }
}
