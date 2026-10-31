@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.simkl.RewatchQuestion
import com.nuvio.tv.data.simkl.SimklRewatchConsentRepository
import com.nuvio.tv.ui.screens.ambient.AmbientDialogEffect
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * How long the question waits before it leaves the screen unanswered.
 *
 * Something has to close it on a TV, where nothing can be tapped outside a dialog, and a question
 * that stays open over a playback blocks what the user is watching. Walking away leaves no answer
 * behind, so the next playback of the item asks again.
 */
internal const val REWATCH_QUESTION_TIMEOUT_MS = 8_000L

/** What an answer to the question does to it. */
internal enum class RewatchPromptKeyOutcome {
    /** The question stays open, the key belongs to the buttons. */
    KEEP,

    /** The question closes without an answer, the same as walking away from it. */
    IGNORE
}

/**
 * What one key press does to the open question.
 *
 * Down is the key that closes the question unanswered. A TV has no way to tap outside a dialog, and
 * down is the key a player opens its own controls with, so it has to leave the question alone
 * without deciding anything: not answering is not a no. Key up and every other key stay with the
 * buttons, which own left, right and the click.
 */
internal fun rewatchPromptKeyOutcome(keyCode: Int, action: Int): RewatchPromptKeyOutcome {
    if (action != KeyEvent.ACTION_DOWN) return RewatchPromptKeyOutcome.KEEP
    return if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
        RewatchPromptKeyOutcome.IGNORE
    } else {
        RewatchPromptKeyOutcome.KEEP
    }
}

/**
 * The rewatch question, drawn above whatever is on screen.
 *
 * This is the TV counterpart of mobile `RewatchPromptHost`. The question is asked before a playback
 * begins and only where Simkl would open a session for it, so the answer can be carried to the end
 * of the playback and decide there whether the scrobble asks the account for a rewatch. Nothing is
 * written while the question is open, and an unanswered one writes nothing at all.
 *
 * The difference from mobile is the timeout: TV cannot click outside a dialog, so the question
 * closes itself after [REWATCH_QUESTION_TIMEOUT_MS] without an interaction, silently. An answer is
 * only ever what a button press said.
 */
@Composable
fun RewatchPromptOverlay(
    repository: SimklRewatchConsentRepository,
    modifier: Modifier = Modifier,
    isLocalHost: Boolean = false
) {
    val question by repository.question.collectAsStateWithLifecycle()
    val localHostActive by repository.localHostActive.collectAsStateWithLifecycle()

    if (!isLocalHost && localHostActive) return

    question?.let { active ->
        RewatchQuestion(
            question = active,
            onGrant = repository::grant,
            onDecline = repository::decline,
            onLeave = repository::dismiss,
            modifier = modifier
        )
    }
}

/**
 * The question itself, drawn exactly like the P2P consent dialog.
 *
 * The two screens are the only pre-play interruptions in the app, so they use one visual language:
 * the same 520 dp container, headline, card row, focus ring and scale. The answers are ordered
 * like the consent dialog too: the answer that does nothing is first, and the answer that starts
 * the run is second, takes the accent only while focused and owns the initial focus.
 */
@Composable
private fun RewatchQuestion(
    question: RewatchQuestion,
    onGrant: () -> Unit,
    onDecline: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val grantFocusRequester = remember { FocusRequester() }
    var interactionCount by remember(question) { mutableIntStateOf(0) }

    // The timer restarts on every key press: "no interaction" is measured from the last one, so the
    // question cannot close while the user is still moving towards an answer.
    LaunchedEffect(question, interactionCount) {
        delay(REWATCH_QUESTION_TIMEOUT_MS)
        onLeave()
    }

    LaunchedEffect(question) {
        runCatching { grantFocusRequester.requestFocusAfterFrames() }
    }

    Dialog(
        onDismissRequest = onLeave,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AmbientDialogEffect(onLeave)
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(NuvioTheme.radii.xl))
                .background(NuvioTheme.colors.BackgroundCard)
        ) {
            Column(
                modifier = Modifier
                    .width(520.dp)
                    .padding(NuvioTheme.spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.rewatch_prompt_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            if (native.action == KeyEvent.ACTION_DOWN) interactionCount += 1
                            when (rewatchPromptKeyOutcome(native.keyCode, native.action)) {
                                RewatchPromptKeyOutcome.KEEP -> false
                                RewatchPromptKeyOutcome.IGNORE -> {
                                    onLeave()
                                    true
                                }
                            }
                        }
                ) {
                    // The answer that does nothing is first, exactly like Cancel in P2P consent.
                    Card(
                        onClick = onDecline,
                        modifier = Modifier
                            .weight(1f),
                        colors = CardDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundElevated,
                            focusedContainerColor = NuvioTheme.colors.BackgroundElevated
                        ),
                        border = CardDefaults.border(
                            focusedBorder = Border(
                                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                        ),
                        shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.md)),
                        scale = CardDefaults.scale(focusedScale = 1.05f)
                    ) {
                        Text(
                            text = stringResource(R.string.rewatch_prompt_dismiss),
                            style = MaterialTheme.typography.titleMedium,
                            color = NuvioTheme.colors.TextPrimary,
                            modifier = Modifier
                                .padding(horizontal = NuvioTheme.spacing.lg, vertical = 14.dp)
                                .fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }

                    // The answer that starts the run takes the accent, like Enable in P2P consent.
                    var startFocused by remember { mutableStateOf(false) }
                    Card(
                        onClick = onGrant,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(grantFocusRequester)
                            .onFocusChanged { startFocused = it.isFocused },
                        colors = CardDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundElevated,
                            focusedContainerColor = NuvioTheme.colors.Secondary
                        ),
                        border = CardDefaults.border(
                            focusedBorder = Border(
                                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                        ),
                        shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.md)),
                        scale = CardDefaults.scale(focusedScale = 1.05f)
                    ) {
                        Text(
                            text = stringResource(R.string.rewatch_prompt_confirm),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (startFocused) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary,
                            modifier = Modifier
                                .padding(horizontal = NuvioTheme.spacing.lg, vertical = 14.dp)
                                .fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
