@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.simkl.RewatchQuestion
import com.nuvio.tv.data.simkl.SimklRewatchConsentRepository
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
 * The difference from mobile is the timeout: TV cannot click outside a dialog, so the band closes
 * itself after [REWATCH_QUESTION_TIMEOUT_MS] without an interaction, silently, because the playback
 * underneath is what the user is actually looking at. An answer is only ever what a button press
 * said.
 */
@Composable
fun RewatchPromptOverlay(
    repository: SimklRewatchConsentRepository,
    modifier: Modifier = Modifier
) {
    val question by repository.question.collectAsStateWithLifecycle()

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
 * The question itself, drawn as the slim band the app already uses for its own notices.
 *
 * The question is not a dialog in the middle of the screen: it is a band on the top edge, built
 * from the same pieces and the same numbers as the new version banner in `com.nuvio.tv.updater.ui`
 * (`app/src/full/java/com/nuvio/tv/updater/ui/UpdateBanner.kt`): a `BackgroundElevated` container
 * with a single line below it, at least 76 dp high, 32 dp of horizontal and 10 dp of vertical
 * padding, a 28 dp icon on the left, then the text, and pill shaped buttons on the right. The banner
 * is the only notice the app has of its own, so the question looks like it and not like its own design.
 *
 * The dialog window stays, but it fills the screen, so focus and the Back button stay with the
 * question: the band is drawn at the top of it, not in the middle. Focus starts on the recording
 * answer and the right arrow moves to the other one, so the answer that writes to the account is
 * never the one picked by mistake.
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
    val declineFocusRequester = remember { FocusRequester() }
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
        Box(
            modifier = modifier
                .fillMaxSize()
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
                },
            contentAlignment = Alignment.TopCenter
        ) {
            RewatchQuestionBand(
                onGrant = onGrant,
                onDecline = onDecline,
                grantFocusRequester = grantFocusRequester,
                declineFocusRequester = declineFocusRequester
            )
        }
    }
}

/** The band itself: the same pieces, sizes and colours the updater banner is built from. */
@Composable
private fun RewatchQuestionBand(
    onGrant: () -> Unit,
    onDecline: () -> Unit,
    grantFocusRequester: FocusRequester,
    declineFocusRequester: FocusRequester
) {
    val containerColor = NuvioTheme.colors.BackgroundElevated
    val dividerColor = NuvioTheme.colors.Border

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(containerColor)
                drawRect(
                    color = dividerColor,
                    topLeft = Offset(0f, size.height - 1.dp.toPx()),
                    size = Size(width = size.width, height = 1.dp.toPx())
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .padding(horizontal = 32.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Repeat,
                contentDescription = null,
                tint = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.size(28.dp)
            )

            Text(
                text = stringResource(R.string.rewatch_prompt_title),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            /*
             * Both buttons keep left and right between themselves. The D pad would otherwise be able
             * to walk out of the band, because the band is a strip and not a box the focus is
             * trapped in, and the question would then be left unanswered behind whatever screen the
             * user moved to.
             */
            Button(
                onClick = onGrant,
                modifier = Modifier
                    .focusRequester(grantFocusRequester)
                    .focusProperties {
                        left = grantFocusRequester
                        right = declineFocusRequester
                        up = grantFocusRequester
                        down = grantFocusRequester
                    },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Secondary,
                    focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                    contentColor = NuvioTheme.colors.OnSecondary,
                    focusedContentColor = NuvioTheme.colors.OnSecondaryVariant
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = stringResource(R.string.rewatch_prompt_confirm),
                    fontWeight = FontWeight.SemiBold
                )
            }

            Button(
                onClick = onDecline,
                modifier = Modifier
                    .focusRequester(declineFocusRequester)
                    .focusProperties {
                        left = grantFocusRequester
                        right = declineFocusRequester
                        up = declineFocusRequester
                        down = declineFocusRequester
                    },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(stringResource(R.string.rewatch_prompt_dismiss))
            }
        }
    }
}
