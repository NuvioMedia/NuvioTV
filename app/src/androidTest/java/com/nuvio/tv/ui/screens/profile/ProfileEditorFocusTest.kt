package com.nuvio.tv.ui.screens.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertNotEquals
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileEditorFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val profile = UserProfile(1, "Existing profile", "#1E88E5")
    private val avatars = listOf(
        AvatarCatalogItem("one", "First avatar", "", "anime", 0),
        AvatarCatalogItem("two", "Second avatar", "", "anime", 1)
    )

    @Test
    fun createEditorMovesDirectlyBetweenControlsAndAvatarsAndRestoresFocus() {
        var showEditor by mutableStateOf(false)
        composeRule.setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize()) {
                    ProfileGrid(
                        profiles = listOf(profile),
                        activeProfileId = profile.id,
                        isManagementMode = false,
                        enabled = !showEditor,
                        canAddProfile = true,
                        profilePinEnabled = emptyMap(),
                        avatarImageUrlsById = emptyMap(),
                        onProfileFocused = {},
                        onProfileSelected = {},
                        onProfileLongPress = {},
                        onAddProfileClick = { showEditor = true }
                    )
                    if (showEditor) {
                        CreateProfileOverlay(
                            profiles = listOf(profile),
                            avatarCatalog = avatars,
                            isCreating = false,
                            onDismiss = { showEditor = false },
                            onCreateProfile = { _, _, _, _, _ -> }
                        )
                    }
                }
            }
        }

        val add = composeRule.onNodeWithText(context.getString(R.string.profile_add_new))
        add.performSemanticsAction(SemanticsActions.RequestFocus) { it() }.performClick()
        composeRule.onNodeWithText(profile.name).assertIsNotEnabled()

        val name = composeRule.onNode(hasSetTextAction())
        val avatar = composeRule.onNodeWithContentDescription("First avatar")
        val settings = composeRule.onNodeWithText(context.getString(R.string.profile_copy_settings_create_fresh))
        name.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        avatar.assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        name.assertIsFocused()

        settings.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        avatar.assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        settings.assertIsFocused()

        composeRule.onNodeWithText(context.getString(R.string.profile_cancel)).performClick()
        add.assertIsFocused()
    }

    @Test
    fun editEditorMovesDirectlyBetweenNameCancelAndAvatars() {
        composeRule.setContent {
            NuvioTheme {
                EditProfileOverlay(
                    profile = profile,
                    avatarCatalog = avatars,
                    profileBackgroundCatalog = emptyList(),
                    hasProfileBackgroundAccess = false,
                    isSaving = false,
                    avatarUrlResolver = { null },
                    onDismiss = {},
                    onSaveProfile = {}
                )
            }
        }

        val name = composeRule.onNode(hasSetTextAction())
        val avatar = composeRule.onNodeWithContentDescription("First avatar")
        val cancel = composeRule.onNodeWithText(context.getString(R.string.profile_cancel))
        name.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        avatar.assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        name.assertIsFocused()
        cancel.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        avatar.assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        cancel.assertIsFocused()
    }

    @Test
    fun avatarEntryTracksTheVisibleRowAndChangesWithCategory() {
        val catalog = (0 until 16).map { index ->
            AvatarCatalogItem("$index", "Avatar $index", "", "anime", index)
        } + AvatarCatalogItem("movie", "Movie avatar", "", "movie", 16)
        composeRule.setContent {
            NuvioTheme {
                CreateProfileOverlay(
                    profiles = listOf(profile),
                    avatarCatalog = catalog,
                    isCreating = false,
                    onDismiss = {},
                    onCreateProfile = { _, _, _, _, _ -> }
                )
            }
        }

        val name = composeRule.onNode(hasSetTextAction())
        val firstAvatar = composeRule.onNodeWithContentDescription("Avatar 0")
        name.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        firstAvatar.assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        val nextRowAvatar = composeRule.onAllNodes(isFocused()).fetchSemanticsNodes()
            .single().config[SemanticsProperties.ContentDescription].single()
        assertNotEquals("Avatar 0", nextRowAvatar)
        composeRule.onNodeWithContentDescription(nextRowAvatar)
            .assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        name.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithContentDescription(nextRowAvatar).assertIsFocused()

        composeRule.onNodeWithText(context.getString(R.string.profile_avatar_category_movie)).performClick()
        name.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithContentDescription("Movie avatar")
            .assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        name.assertIsFocused()
    }
}
