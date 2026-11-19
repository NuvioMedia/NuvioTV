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
import androidx.compose.ui.test.hasAnyDescendant
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
}
