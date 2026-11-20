package com.nuvio.tv.ui.screens.collection

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSort
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.components.TvReorderList
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@OptIn(ExperimentalTvMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class CollectionFocusAndScrollTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun tvReorderList_tallHeaderRemountPreservesScrollAndRestoresExactRowFocus() {
        var showSubScreen by mutableStateOf(false)
        lateinit var capturedScrollState: ScrollState
        val items = (0 until 15).map { "item_$it" }

        composeRule.setContent {
            NuvioTheme {
                val scrollState = rememberScrollState()
                capturedScrollState = scrollState
                val requesters = remember { items.associateWith { FocusRequester() } }
                var pendingKey by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(showSubScreen, pendingKey) {
                    if (showSubScreen) return@LaunchedEffect
                    val key = pendingKey ?: return@LaunchedEffect
                    val req = requesters[key] ?: return@LaunchedEffect
                    requestFocusWhenReady(req)
                    if (pendingKey == key) {
                        pendingKey = null
                    }
                }

                if (showSubScreen) {
                    val closeFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { requestFocusWhenReady(closeFocus) }
                    Box(Modifier.fillMaxSize()) {
                        Button(
                            onClick = { showSubScreen = false },
                            modifier = Modifier
                                .testTag("close_subscreen")
                                .focusRequester(closeFocus)
                        ) {
                            Text("Close")
                        }
                    }
                } else {
                    TvReorderList(
                        items = items,
                        key = { it },
                        canMove = { false },
                        movingKey = null,
                        onStartMove = {},
                        onMoveBy = { _, _ -> null },
                        onConfirmMove = {},
                        onCancelMove = {},
                        modifier = Modifier.fillMaxSize(),
                        scrollState = scrollState,
                        ensureComposedKey = pendingKey,
                        header = {
                            // Tall header (> 1 viewport) like FolderEditorContent
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1200.dp)
                            ) {
                                Text("Header")
                            }
                        }
                    ) { item, _, _, _ ->
                        Button(
                            onClick = {
                                pendingKey = item
                                showSubScreen = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(90.dp)
                                .testTag("row_$item")
                                .focusRequester(requesters.getValue(item))
                        ) {
                            Text(item)
                        }
                    }
                }
            }
        }

        // Scroll past the tall header so item_2 is visible in the viewport
        composeRule.runOnIdle {
            assertTrue("Expected scrollable content", capturedScrollState.maxValue > 1200)
        }
        val targetScroll = (capturedScrollState.maxValue * 0.55f).toInt()
        composeRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                capturedScrollState.scrollTo(targetScroll)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("row_item_2")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        val scrollBefore = capturedScrollState.value
        assertTrue("Scroll should stay past header before opening sub-screen", scrollBefore > 1000)

        // Open sub-screen from item_2 via D-pad Center
        composeRule.onNodeWithTag("row_item_2").performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("close_subscreen").assertIsFocused()

        // Close sub-screen via D-pad Center and verify exact row focus & preserved scroll (no jump!)
        composeRule.onNodeWithTag("close_subscreen").performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("row_item_2").assertIsFocused()
        assertTrue(
            "Scroll should stay at same position without jumping (was $scrollBefore, now ${capturedScrollState.value})",
            abs(capturedScrollState.value - scrollBefore) <= 24
        )
    }

    @Test
    fun catalogPicker_focusesFirstCatalogAndKeepsFocusOnToggle() {
        val catalogs = (0 until 8).map { index ->
            AvailableCatalog(
                addonId = "addon_$index",
                addonName = "Addon $index",
                type = "movie",
                catalogId = "cat_$index",
                catalogName = "Catalog $index"
            )
        }
        var added by mutableStateOf<List<CollectionSource>>(emptyList())

        composeRule.setContent {
            NuvioTheme {
                CatalogPickerContent(
                    catalogs = catalogs,
                    alreadyAdded = added,
                    onToggle = { catalog ->
                        val exists = added.any {
                            it is AddonCatalogCollectionSource && it.catalogId == catalog.catalogId
                        }
                        added = if (exists) {
                            added.filterNot {
                                it is AddonCatalogCollectionSource && it.catalogId == catalog.catalogId
                            }
                        } else {
                            added + AddonCatalogCollectionSource(
                                addonId = catalog.addonId,
                                type = catalog.type,
                                catalogId = catalog.catalogId
                            )
                        }
                    },
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Catalog 0").assertIsFocused()

        // Toggle Catalog 0 via D-pad Center and verify focus stays right on Catalog 0
        composeRule.onNodeWithText("Catalog 0").performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        composeRule.waitForIdle()
        assertEquals(1, added.size)
        composeRule.onNodeWithText("Catalog 0").assertIsFocused()
    }

    @Test
    fun catalogPicker_emptyListFocusesDoneButton() {
        composeRule.setContent {
            NuvioTheme {
                CatalogPickerContent(
                    catalogs = emptyList(),
                    alreadyAdded = emptyList(),
                    onToggle = {},
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.collections_editor_done)).assertIsFocused()
    }

    @Test
    fun genrePicker_focusesSelectedGenreDeepInList() {
        val genres = (0 until 30).map { "Genre $it" }

        composeRule.setContent {
            NuvioTheme {
                GenrePickerContent(
                    title = "Movies",
                    selectedGenre = "Genre 22",
                    genreOptions = genres,
                    allowAll = true,
                    onSelect = {},
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Genre 22").assertIsFocused()
    }

    @Test
    fun emojiPicker_focusesSelectedEmojiInDeepCategory() {
        val targetEmoji = "🇹🇷"
        val description = context.getString(R.string.cd_emoji_option, targetEmoji)

        composeRule.setContent {
            NuvioTheme {
                EmojiPickerContent(
                    selectedEmoji = targetEmoji,
                    onSelect = {},
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(description).assertIsFocused()
    }

    @Test
    fun tmdbPicker_editingDiscoverModeFocusesDiscoverTabAndKeepsFocusOnModeSwitch() {
        var uiState by mutableStateOf(
            CollectionEditorUiState(
                isLoading = false,
                tmdbBuilderMode = TmdbBuilderMode.DISCOVER
            )
        )

        composeRule.setContent {
            NuvioTheme {
                TmdbSourcePickerContent(
                    uiState = uiState,
                    presets = emptyList(),
                    isEditing = true,
                    onModeChange = { uiState = uiState.copy(tmdbBuilderMode = it) },
                    onInputChange = {},
                    onTitleChange = {},
                    onMediaTypeChange = {},
                    onMediaBothChange = {},
                    onSortChange = {},
                    onFiltersChange = {},
                    onSearchCompanies = {},
                    onSearchCollections = {},
                    onAddSource = {},
                    onAddSources = {},
                    onAddFromInput = {},
                    onAddDiscover = {},
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        val discoverLabel = context.getString(R.string.collections_editor_tmdb_mode_custom)
        composeRule.onNodeWithText(discoverLabel).assertIsFocused()

        // Navigate left to Director tab, activate via D-pad Center, and verify focus stays on Director tab
        composeRule.onNodeWithText(discoverLabel).performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeRule.waitForIdle()
        val directorLabel = context.getString(R.string.collections_editor_tmdb_mode_director)
        composeRule.onNodeWithText(directorLabel).assertIsFocused()
        composeRule.onNodeWithText(directorLabel).performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        composeRule.waitForIdle()
        assertEquals(TmdbBuilderMode.DIRECTOR, uiState.tmdbBuilderMode)
        composeRule.onNodeWithText(directorLabel).assertIsFocused()
    }

    @Test
    fun traktPicker_focusesInputFieldSurfaceOnOpen() {
        composeRule.setContent {
            NuvioTheme {
                TraktSourcePickerContent(
                    uiState = CollectionEditorUiState(isLoading = false),
                    isEditing = false,
                    onInputChange = {},
                    onTitleChange = {},
                    onMediaTypeChange = {},
                    onMediaBothChange = {},
                    onSortChange = {},
                    onSortHowChange = {},
                    onSearch = {},
                    onAddFromInput = {},
                    onAddResult = {},
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        val placeholder = context.getString(R.string.collection_editor_trakt_search_placeholder)
        // Outer NuvioTextField Surface is focused (not opening soft keyboard until OK is pressed)
        composeRule.onNode(
            isFocused() and hasAnyDescendant(hasText(placeholder))
        ).assertExists()
    }

    @Test
    fun unsavedChangesDialog_focusesSaveWithoutYankingFocusBackAfterUserNavigates() {
        composeRule.setContent {
            NuvioTheme {
                UnsavedChangesDialog(
                    title = "Unsaved changes",
                    subtitle = "Save before leaving?",
                    canSave = true,
                    onSave = {},
                    onDiscard = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.waitForIdle()
        val saveLabel = context.getString(R.string.collections_editor_save)
        val cancelLabel = context.getString(R.string.collections_cancel)
        composeRule.onNodeWithText(saveLabel).assertIsFocused()

        // Move focus left to Cancel and confirm it stays there (not yanked back to Save)
        composeRule.onNodeWithText(saveLabel).performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(cancelLabel).assertIsFocused()
    }

    @Test
    fun importContent_switchingModeTabsKeepsFocusOnSelectedTab() {
        var mode by mutableStateOf(ImportMode.FILE)

        composeRule.setContent {
            NuvioTheme {
                ImportContent(
                    importText = "",
                    importError = null,
                    onTextChange = {},
                    onImport = {},
                    onPaste = {},
                    onBack = {},
                    importMode = mode,
                    onModeChange = { mode = it },
                    importUrl = "",
                    onUrlChange = {},
                    onFetchUrl = {},
                    validationResult = null,
                    isLoadingImport = false,
                    onValidate = {},
                    onPickFile = {},
                    onConfirmImport = {}
                )
            }
        }

        composeRule.waitForIdle()
        val fileLabel = context.getString(R.string.collections_mode_file)
        val urlLabel = context.getString(R.string.collections_mode_url)
        composeRule.onNodeWithText(fileLabel).assertIsFocused()

        // Navigate right to URL tab and activate it; focus must remain on URL tab
        composeRule.onNodeWithText(fileLabel).performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(urlLabel).assertIsFocused()
        composeRule.onNodeWithText(urlLabel).performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        composeRule.waitForIdle()
        assertEquals(ImportMode.URL, mode)
        composeRule.onNodeWithText(urlLabel).assertIsFocused()
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun tvReorderList_smoothBringIntoViewAndVariableHeights_noJumpOnRemount() {
        var showSubScreen by mutableStateOf(false)
        lateinit var capturedScrollState: ScrollState
        val items = (0 until 20).map { "src_$it" }

        composeRule.setContent {
            NuvioTheme {
                CompositionLocalProvider(
                    LocalBringIntoViewSpec provides NuvioScrollDefaults.smoothScrollSpec
                ) {
                    val scrollState = rememberScrollState()
                    val rowHeightCache = remember { mutableMapOf<String, Float>() }
                    capturedScrollState = scrollState
                    val requesters = remember { items.associateWith { FocusRequester() } }
                    var pendingKey by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(showSubScreen, pendingKey) {
                        if (showSubScreen) return@LaunchedEffect
                        val key = pendingKey ?: return@LaunchedEffect
                        val req = requesters[key] ?: return@LaunchedEffect
                        requestFocusWhenReady(req)
                        if (pendingKey == key) {
                            pendingKey = null
                        }
                    }

                    if (showSubScreen) {
                        val closeFocus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { requestFocusWhenReady(closeFocus) }
                        Box(Modifier.fillMaxSize()) {
                            Button(
                                onClick = { showSubScreen = false },
                                modifier = Modifier
                                    .testTag("close_picker")
                                    .focusRequester(closeFocus)
                            ) {
                                Text("Close")
                            }
                        }
                    } else {
                        TvReorderList(
                            items = items,
                            key = { it },
                            canMove = { false },
                            movingKey = null,
                            onStartMove = {},
                            onMoveBy = { _, _ -> null },
                            onConfirmMove = {},
                            onCancelMove = {},
                            modifier = Modifier.fillMaxSize(),
                            scrollState = scrollState,
                            ensureComposedKey = pendingKey,
                            rowHeightCache = rowHeightCache,
                            header = {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1000.dp)
                                ) {
                                    Text("Folder Header")
                                }
                            }
                        ) { item, _, _, _ ->
                            val index = item.removePrefix("src_").toInt()
                            // Variable heights like addon sources with/without genre controls
                            val rowHeight = if (index == 0) 72.dp else if (index % 2 == 0) 150.dp else 96.dp
                            Button(
                                onClick = {
                                    pendingKey = item
                                    showSubScreen = true
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(rowHeight)
                                    .testTag("src_row_$item")
                                    .focusRequester(requesters.getValue(item))
                            ) {
                                Text(item)
                            }
                        }
                    }
                }
            }
        }

        // Scroll down to src_12 and focus it so smoothScrollSpec settles at equilibrium
        composeRule.runOnIdle {
            kotlinx.coroutines.runBlocking {
                capturedScrollState.scrollTo((capturedScrollState.maxValue * 0.7f).toInt())
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("src_row_src_12")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        val scrollBefore = capturedScrollState.value
        assertTrue("Expected deep scroll before opening picker", scrollBefore > 1500)

        // Open picker and immediately return
        composeRule.onNodeWithTag("src_row_src_12").performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("close_picker").assertIsFocused()

        composeRule.onNodeWithTag("close_picker").performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("src_row_src_12").assertIsFocused()
        assertTrue(
            "Smooth BringIntoView remount should preserve scroll without jumping (was $scrollBefore, now ${capturedScrollState.value})",
            abs(capturedScrollState.value - scrollBefore) <= 32
        )
    }

    @Test
    fun nuvioTextField_dpadDownAndUpMoveDirectlyBetweenFieldsWithoutGhostStops() {
        composeRule.setContent {
            NuvioTheme {
                val firstFieldFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { requestFocusWhenReady(firstFieldFocus) }
                Column(modifier = Modifier.fillMaxSize()) {
                    NuvioTextField(
                        value = "",
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth().testTag("field_1"),
                        placeholder = "First field",
                        focusRequester = firstFieldFocus
                    )
                    NuvioTextField(
                        value = "",
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth().testTag("field_2"),
                        placeholder = "Second field"
                    )
                    Button(
                        onClick = {},
                        modifier = Modifier.testTag("after_fields_btn")
                    ) {
                        Text("Next")
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("field_1").assertIsFocused()

        // Single DPAD DOWN must move directly to field_2 (not inner BasicTextField of field_1)
        composeRule.onNodeWithTag("field_1").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("field_2").assertIsFocused()

        // Single DPAD DOWN must move directly to button below
        composeRule.onNodeWithTag("field_2").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("after_fields_btn").assertIsFocused()

        // Single DPAD UP must return directly to field_2 Surface
        composeRule.onNodeWithTag("after_fields_btn").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("field_2").assertIsFocused()

        // Single DPAD UP must return directly to field_1 Surface
        composeRule.onNodeWithTag("field_2").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("field_1").assertIsFocused()
    }

    @Test
    fun genrePicker_dpadUpFromDeepSelectedGenreMovesToPreviousGenreNotBackButton() {
        val genres = (0 until 30).map { "Genre $it" }

        composeRule.setContent {
            NuvioTheme {
                GenrePickerContent(
                    title = "Movies",
                    selectedGenre = "Genre 22",
                    genreOptions = genres,
                    allowAll = true,
                    onSelect = {},
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Genre 22").assertIsFocused()

        composeRule.onNodeWithContentDescription("Genre 22").performKeyInput {
            pressKey(Key.DirectionUp)
        }
        composeRule.waitForIdle()

        val backLabel = context.getString(R.string.collections_editor_back)
        composeRule.onNodeWithText(backLabel).assertIsNotFocused()
        composeRule.onNodeWithContentDescription("Genre 21").assertIsFocused()
    }

    @Test
    fun emojiPicker_dpadUpFromDeepCategoryMovesToPreviousCategoryNotBackButton() {
        val targetEmoji = "🇹🇷"
        val description = context.getString(R.string.cd_emoji_option, targetEmoji)

        composeRule.setContent {
            NuvioTheme {
                EmojiPickerContent(
                    selectedEmoji = targetEmoji,
                    onSelect = {},
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(description).assertIsFocused()

        composeRule.onNodeWithContentDescription(description).performKeyInput {
            pressKey(Key.DirectionUp)
        }
        composeRule.waitForIdle()

        val backLabel = context.getString(R.string.collections_editor_back)
        composeRule.onNodeWithText(backLabel).assertIsNotFocused()
    }

    @Test
    fun traktPicker_dpadDownSelectsActiveOptionButtonsNotRightmostButtons() {
        composeRule.setContent {
            NuvioTheme {
                TraktSourcePickerContent(
                    uiState = CollectionEditorUiState(
                        isLoading = false,
                        traktMediaType = TmdbCollectionMediaType.MOVIE,
                        traktMediaBoth = false,
                        traktSortBy = "rank",
                        traktSortHow = "asc"
                    ),
                    isEditing = false,
                    onInputChange = {},
                    onTitleChange = {},
                    onMediaTypeChange = {},
                    onMediaBothChange = {},
                    onSortChange = {},
                    onSortHowChange = {},
                    onSearch = {},
                    onAddFromInput = {},
                    onAddResult = {},
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        val inputPlaceholder = context.getString(R.string.collection_editor_trakt_search_placeholder)
        val titlePlaceholder = context.getString(R.string.collection_editor_trakt_name_placeholder)
        val movieLabel = context.getString(R.string.type_movie)
        val sortListOrderLabel = context.getString(R.string.collections_editor_sort_list_order)
        val ascLabel = context.getString(R.string.collections_editor_trakt_ascending)
        val searchLabel = context.getString(R.string.collections_editor_tmdb_search)

        val inputSurface = composeRule.onNode(isFocused() and hasAnyDescendant(hasText(inputPlaceholder)))
        inputSurface.assertExists()

        // Down -> Display Title field (single step, no ghost stop)
        inputSurface.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        val titleSurface = composeRule.onNode(isFocused() and hasAnyDescendant(hasText(titlePlaceholder)))
        titleSurface.assertExists()

        // Down -> Type row must land on selected "Movie" (not rightmost "Both")
        titleSurface.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(movieLabel).assertIsFocused()

        // Down -> Sort row must land on selected "List Order" (not rightmost "Votes")
        composeRule.onNodeWithText(movieLabel).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(sortListOrderLabel).assertIsFocused()

        // Down -> Direction row must land on selected "Ascending" (not rightmost "Descending")
        composeRule.onNodeWithText(sortListOrderLabel).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(ascLabel).assertIsFocused()

        // Down -> Action row must land on "Search"
        composeRule.onNodeWithText(ascLabel).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(searchLabel).assertIsFocused()
    }

    @Test
    fun tmdbPicker_discoverModeDpadDownLandsOnSelectedTypeAndSortNotRightmostButtons() {
        composeRule.setContent {
            NuvioTheme {
                TmdbSourcePickerContent(
                    uiState = CollectionEditorUiState(
                        isLoading = false,
                        tmdbBuilderMode = TmdbBuilderMode.DISCOVER,
                        tmdbMediaType = TmdbCollectionMediaType.MOVIE,
                        tmdbMediaBoth = false,
                        tmdbSortBy = TmdbCollectionSort.POPULAR_DESC.value
                    ),
                    presets = emptyList(),
                    isEditing = false,
                    onModeChange = {},
                    onInputChange = {},
                    onTitleChange = {},
                    onMediaTypeChange = {},
                    onMediaBothChange = {},
                    onSortChange = {},
                    onFiltersChange = {},
                    onSearchCompanies = {},
                    onSearchCollections = {},
                    onAddSource = {},
                    onAddSources = {},
                    onAddFromInput = {},
                    onAddDiscover = {},
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        val discoverLabel = context.getString(R.string.collections_editor_tmdb_mode_custom)
        val titlePlaceholder = context.getString(R.string.collections_editor_tmdb_tv_title_placeholder)
        val movieLabel = context.getString(R.string.type_movie)
        val popularLabel = context.getString(R.string.tmdb_entity_rail_popular)

        composeRule.onNodeWithText(discoverLabel).assertIsFocused()

        // Down from mode tab -> Display Title
        composeRule.onNodeWithText(discoverLabel).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        val titleSurface = composeRule.onNode(isFocused() and hasAnyDescendant(hasText(titlePlaceholder)))
        titleSurface.assertExists()

        // Down from full-width Display Title -> selected "Movie" (NOT rightmost "Both")
        titleSurface.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(movieLabel).assertIsFocused()

        // Down from "Movie" -> selected "Popular" (NOT rightmost "Recent")
        composeRule.onNodeWithText(movieLabel).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(popularLabel).assertIsFocused()
    }

    @Test
    fun traktPicker_horizontalDpadDoesNotEscapeOptionRowsOrFields() {
        composeRule.setContent {
            NuvioTheme {
                TraktSourcePickerContent(
                    uiState = CollectionEditorUiState(
                        isLoading = false,
                        traktMediaType = TmdbCollectionMediaType.MOVIE,
                        traktMediaBoth = false,
                        traktSortBy = "rank",
                        traktSortHow = "asc"
                    ),
                    isEditing = false,
                    onInputChange = {},
                    onTitleChange = {},
                    onMediaTypeChange = {},
                    onMediaBothChange = {},
                    onSortChange = {},
                    onSortHowChange = {},
                    onSearch = {},
                    onAddFromInput = {},
                    onAddResult = {},
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        val inputPlaceholder = context.getString(R.string.collection_editor_trakt_search_placeholder)
        val movieLabel = context.getString(R.string.type_movie)
        val seriesLabel = context.getString(R.string.type_series)
        val bothLabel = context.getString(R.string.collection_editor_choice_both)

        val inputSurface = composeRule.onNode(isFocused() and hasAnyDescendant(hasText(inputPlaceholder)))
        inputSurface.assertExists()

        // Left/Right on standalone input field must not escape to Back or elsewhere
        inputSurface.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.waitForIdle()
        composeRule.onNode(isFocused() and hasAnyDescendant(hasText(inputPlaceholder))).assertExists()

        inputSurface.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        composeRule.onNode(isFocused() and hasAnyDescendant(hasText(inputPlaceholder))).assertExists()

        // Move down to Type row ("Movie")
        inputSurface.performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(movieLabel).assertIsFocused()

        // Left at left edge of Type row stays on "Movie"
        composeRule.onNodeWithText(movieLabel).performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(movieLabel).assertIsFocused()

        // Right moves within Type row: Movie -> Series -> Both
        composeRule.onNodeWithText(movieLabel).performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(seriesLabel).assertIsFocused()

        composeRule.onNodeWithText(seriesLabel).performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(bothLabel).assertIsFocused()

        // Right at right edge of Type row stays on "Both" (never escapes to Back or another row)
        composeRule.onNodeWithText(bothLabel).performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(bothLabel).assertIsFocused()
    }

    @Test
    fun tmdbPicker_horizontalDpadAtLastModeTabDoesNotEscapeToBack() {
        composeRule.setContent {
            NuvioTheme {
                TmdbSourcePickerContent(
                    uiState = CollectionEditorUiState(
                        isLoading = false,
                        tmdbBuilderMode = TmdbBuilderMode.DISCOVER,
                        tmdbMediaType = TmdbCollectionMediaType.MOVIE,
                        tmdbMediaBoth = false,
                        tmdbSortBy = TmdbCollectionSort.POPULAR_DESC.value
                    ),
                    presets = emptyList(),
                    isEditing = false,
                    onModeChange = {},
                    onInputChange = {},
                    onTitleChange = {},
                    onMediaTypeChange = {},
                    onMediaBothChange = {},
                    onSortChange = {},
                    onFiltersChange = {},
                    onSearchCompanies = {},
                    onSearchCollections = {},
                    onAddSource = {},
                    onAddSources = {},
                    onAddFromInput = {},
                    onAddDiscover = {},
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        val discoverLabel = context.getString(R.string.collections_editor_tmdb_mode_custom)
        val directorLabel = context.getString(R.string.collections_editor_tmdb_mode_director)
        val backLabel = context.getString(R.string.collections_editor_back)

        composeRule.onNodeWithText(discoverLabel).assertIsFocused()

        // Right on last mode tab must stay on Discover (must NOT jump to Back)
        composeRule.onNodeWithText(discoverLabel).performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(discoverLabel).assertIsFocused()
        composeRule.onNodeWithText(backLabel).assertIsNotFocused()

        // Left on Discover must still move inside the LazyRow to Director
        composeRule.onNodeWithText(discoverLabel).performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(directorLabel).assertIsFocused()
    }

    @Test
    fun emojiPicker_horizontalDpadScrollsWithinRowAndStopsAtEdges() {
        val targetEmoji = "🇹🇷"
        val prevEmoji = "🇹🇳"
        val targetDescription = context.getString(R.string.cd_emoji_option, targetEmoji)
        val prevDescription = context.getString(R.string.cd_emoji_option, prevEmoji)

        composeRule.setContent {
            NuvioTheme {
                EmojiPickerContent(
                    selectedEmoji = targetEmoji,
                    onSelect = {},
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(targetDescription).assertIsFocused()

        // Left from deep initial emoji in Flags LazyRow MUST scroll to previous item ("🇹🇳")
        composeRule.onNodeWithContentDescription(targetDescription).performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(prevDescription).assertIsFocused()
    }

    @Test
    fun emojiPicker_horizontalDpadAtStartEdgeDoesNotEscapeToBack() {
        val firstEmoji = "🎬"
        val firstDescription = context.getString(R.string.cd_emoji_option, firstEmoji)
        val backLabel = context.getString(R.string.collections_editor_back)

        composeRule.setContent {
            NuvioTheme {
                EmojiPickerContent(
                    selectedEmoji = firstEmoji,
                    onSelect = {},
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(firstDescription).assertIsFocused()

        // Left at index 0 must stay on "🎬" (never jump to Back or another row)
        composeRule.onNodeWithContentDescription(firstDescription).performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(firstDescription).assertIsFocused()
        composeRule.onNodeWithText(backLabel).assertIsNotFocused()
    }

    @Test
    fun genrePicker_horizontalDpadOnCardsDoesNotEscapeToBack() {
        val genres = (1..10).map { "Genre $it" }
        val backLabel = context.getString(R.string.collections_editor_back)

        composeRule.setContent {
            NuvioTheme {
                GenrePickerContent(
                    title = "Addon Catalog",
                    selectedGenre = "Genre 1",
                    genreOptions = genres,
                    allowAll = true,
                    onSelect = {},
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Genre 1").assertIsFocused()

        composeRule.onNodeWithText("Genre 1").performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Genre 1").assertIsFocused()
        composeRule.onNodeWithText(backLabel).assertIsNotFocused()

        composeRule.onNodeWithText("Genre 1").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Genre 1").assertIsFocused()
        composeRule.onNodeWithText(backLabel).assertIsNotFocused()
    }
}
