@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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

/**
 * The rewatch question, drawn above whatever is on screen.
 *
 * This is the TV counterpart of mobile `RewatchPromptHost`. The question is asked before a playback
 * begins and only where Simkl would open a session for it, so the answer can be carried to the end
 * of the playback and decide there whether the scrobble asks the account for a rewatch. Nothing is
 * written while the question is open, and an unanswered one writes nothing at all.
 *
 * The question does not close itself: it leaves only when the user answers or backs out, because a
 * timeout closing it would start the playback without a decision.
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
        LaunchedEffect(active) { repository.markQuestionShown() }
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
    var allowDeclineFocus by remember(question) { mutableStateOf(false) }

    LaunchedEffect(question) {
        // The first focusable child would take the focus for the frames this request waits. Keep the
        // answer that does nothing out of the focus order until the recording answer owns it.
        runCatching { grantFocusRequester.requestFocusAfterFrames() }
        allowDeclineFocus = true
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

                Text(
                    text = stringResource(R.string.rewatch_prompt_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = NuvioTheme.spacing.sm)
                )

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // The answer that does nothing is first, exactly like Cancel in P2P consent.
                    Card(
                        onClick = onDecline,
                        modifier = Modifier
                            .weight(1f)
                            .focusProperties { canFocus = allowDeclineFocus },
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
